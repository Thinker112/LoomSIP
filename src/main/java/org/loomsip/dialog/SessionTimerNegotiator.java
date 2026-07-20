package org.loomsip.dialog;

import org.loomsip.message.header.SessionExpiresHeaderValue;
import org.loomsip.message.header.SessionRefresher;

import java.util.Objects;

/** Stateless RFC 4028 interval and refresher-role negotiation helper. */
public final class SessionTimerNegotiator {

    /** Creates a stateless Session Timer negotiator. */
    public SessionTimerNegotiator() {
    }

    /**
     * Validates one requested Session-Expires value against local policy.
     *
     * @param requested peer Session-Expires header
     * @param policy local interval and method policy
     * @return normalized negotiated session parameters
     * @throws SessionIntervalTooSmallException when a 422 with Min-SE is required
     */
    public NegotiatedSessionTimer negotiate(
            SessionExpiresHeaderValue requested,
            SessionTimerPolicy policy
    ) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(policy, "policy");
        if (requested.intervalSeconds() < policy.minimumSeconds()) {
            throw new SessionIntervalTooSmallException(policy.minimumSeconds());
        }
        return new NegotiatedSessionTimer(
                requested.intervalSeconds(),
                requested.refresher().orElse(SessionRefresher.UAC),
                policy.refreshMethod()
        );
    }

    /** Immutable result retained by the later Dialog-owned timer state machine. */
    public record NegotiatedSessionTimer(
            int intervalSeconds,
            SessionRefresher refresher,
            SessionRefreshMethod refreshMethod
    ) {

        /** Validates negotiated timer parameters. */
        public NegotiatedSessionTimer {
            if (intervalSeconds <= 0) {
                throw new IllegalArgumentException("session interval must be positive");
            }
            Objects.requireNonNull(refresher, "refresher");
            Objects.requireNonNull(refreshMethod, "refreshMethod");
        }
    }
}
