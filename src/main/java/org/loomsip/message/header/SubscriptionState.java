package org.loomsip.message.header;

/** RFC 3265 Subscription-State tokens supported by the core subscription state machine. */
public enum SubscriptionState {
    /** Subscription is awaiting activation. */
    PENDING("pending"),
    /** Subscription is active. */
    ACTIVE("active"),
    /** Subscription has ended permanently. */
    TERMINATED("terminated");

    private final String wireValue;

    SubscriptionState(String wireValue) {
        this.wireValue = wireValue;
    }

    /** @return lower-case Subscription-State token */
    public String wireValue() {
        return wireValue;
    }

    /** Parses one case-insensitive RFC 3265 state token. */
    public static SubscriptionState parse(String value) {
        for (SubscriptionState state : values()) {
            if (state.wireValue.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unsupported Subscription-State: " + value);
    }
}
