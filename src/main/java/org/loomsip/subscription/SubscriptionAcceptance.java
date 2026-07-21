package org.loomsip.subscription;

/** Application decision for one UAS SUBSCRIBE request. */
public record SubscriptionAcceptance(int statusCode, String reasonPhrase, int expiresSeconds) {

    /** Validates SIP response status, phrase, and non-negative accepted expiry. */
    public SubscriptionAcceptance {
        if (statusCode < 200 || statusCode > 699 || reasonPhrase == null || reasonPhrase.isBlank() || expiresSeconds < 0) {
            throw new IllegalArgumentException("invalid Subscription acceptance");
        }
    }

    /** @return whether this decision accepts the subscription */
    public boolean accepted() {
        return statusCode >= 200 && statusCode < 300;
    }
}
