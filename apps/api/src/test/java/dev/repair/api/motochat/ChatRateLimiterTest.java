package dev.repair.api.motochat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.repair.api.common.TooManyRequestsException;
import dev.repair.api.config.ChatRateLimitProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChatRateLimiterTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    };
    private final ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(2, 60), clock);

    @Test
    void allowsRequestsUpToTheLimit() {
        assertDoesNotThrow(() -> limiter.acquire(1));
        assertDoesNotThrow(() -> limiter.acquire(1));
    }

    @Test
    void rejectsBeyondTheLimitWithRemainingWindowAsRetryAfter() {
        limiter.acquire(1);
        millis.addAndGet(20_000);
        limiter.acquire(1);
        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class, () -> limiter.acquire(1));
        assertEquals(40, ex.retryAfterSeconds());
    }

    @Test
    void usersHaveSeparateAllowances() {
        limiter.acquire(1);
        limiter.acquire(1);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(1));
        assertDoesNotThrow(() -> limiter.acquire(2));
    }

    @Test
    void rejectedRequestsDoNotExtendTheWindow() {
        limiter.acquire(1);
        limiter.acquire(1);
        millis.addAndGet(30_000);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(1));
        millis.addAndGet(30_000);
        assertDoesNotThrow(() -> limiter.acquire(1));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRateLimitProperties(0, 60));
        assertThrows(IllegalArgumentException.class, () -> new ChatRateLimitProperties(1, 0));
    }

    @Test
    void concurrentRequestsNeverExceedTheLimit() throws Exception {
        ChatRateLimiter shared = new ChatRateLimiter(new ChatRateLimitProperties(10, 60), clock);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    shared.acquire(1);
                    allowed.incrementAndGet();
                } catch (TooManyRequestsException ignored) {
                    // expected for everything past the limit
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(10, allowed.get());
    }
}
