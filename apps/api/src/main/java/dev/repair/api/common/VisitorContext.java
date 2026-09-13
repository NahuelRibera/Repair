package dev.repair.api.common;

import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * The anonymous visitor identity for the current request, resolved by
 * {@link VisitorCookieInterceptor} from an opaque session cookie. Every
 * conversation endpoint must check ownership against this id instead of
 * trusting any id supplied in the request body/path — see
 * ConversationOwnershipException.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
public class VisitorContext {

    private UUID visitorId;

    public UUID getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(UUID visitorId) {
        this.visitorId = visitorId;
    }
}
