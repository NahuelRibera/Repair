package dev.repair.api.motochat;

import dev.repair.api.common.TooManyRequestsException;
import dev.repair.api.config.ChatRateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window limiter, one window per authenticated user. State is local to
 * this instance, which is sufficient for a single-instance deployment.
 */
@Component
public class ChatRateLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final int maxRequests;
    private final long windowNanos;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimiter(ChatRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ChatRateLimiter(ChatRateLimitProperties properties, Clock clock) {
        this.maxRequests = properties.maxRequests();
        this.windowNanos = Duration.ofSeconds(properties.windowSeconds()).toNanos();
        this.clock = clock;
    }

    /** Records a request for the user, or throws {@link TooManyRequestsException} when over the limit. */
    public void acquire(long userId) {
        long now = nowNanos();
        if (windows.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        long[] retryAfterNanos = {0};
        windows.compute(userId, (id, window) -> {
            Deque<Long> w = window == null ? new ArrayDeque<>() : window;
            evictExpired(w, now);
            if (w.size() >= maxRequests) {
                retryAfterNanos[0] = w.peekFirst() + windowNanos - now;
            } else {
                w.addLast(now);
            }
            return w;
        });
        if (retryAfterNanos[0] > 0) {
            long seconds = Math.max(1, (retryAfterNanos[0] + 999_999_999L) / 1_000_000_000L);
            throw new TooManyRequestsException("Too many chat requests. Please wait before trying again.", seconds);
        }
    }

    private void sweep(long now) {
        for (Long userId : windows.keySet()) {
            windows.computeIfPresent(userId, (id, w) -> {
                evictExpired(w, now);
                return w.isEmpty() ? null : w;
            });
        }
    }

    private void evictExpired(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowNanos) {
            window.pollFirst();
        }
    }

    private long nowNanos() {
        java.time.Instant i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }
}
