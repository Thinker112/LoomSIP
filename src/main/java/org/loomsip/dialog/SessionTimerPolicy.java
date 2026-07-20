package org.loomsip.dialog;

/** Local RFC 4028 minimum and preferred session interval policy. */
public record SessionTimerPolicy(int minimumSeconds, int preferredSeconds, SessionRefreshMethod refreshMethod) {

    /** RFC 4028 default minimum session interval. */
    public static final int RFC_MINIMUM_SECONDS = 90;

    /** Conservative UPDATE-based default policy. */
    public static final SessionTimerPolicy DEFAULT = new SessionTimerPolicy(
            RFC_MINIMUM_SECONDS,
            1800,
            SessionRefreshMethod.UPDATE
    );

    /** Validates interval policy and refresh method. */
    public SessionTimerPolicy {
        if (minimumSeconds < RFC_MINIMUM_SECONDS || preferredSeconds < minimumSeconds) {
            throw new IllegalArgumentException("invalid Session Timer interval policy");
        }
        java.util.Objects.requireNonNull(refreshMethod, "refreshMethod");
    }
}
