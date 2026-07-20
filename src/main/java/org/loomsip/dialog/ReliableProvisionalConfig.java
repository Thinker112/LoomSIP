package org.loomsip.dialog;

/** Capacity limits for RFC 3262 reliable provisional response exchanges. */
public record ReliableProvisionalConfig(int exchanges, int mailboxCapacity, int maxUacRSeqs) {

    /** Conservative initial limits. */
    public static final ReliableProvisionalConfig DEFAULT = new ReliableProvisionalConfig(10_000, 64, 32);

    /** Validates all exchange and queue limits. */
    public ReliableProvisionalConfig {
        if (exchanges <= 0 || mailboxCapacity <= 0 || maxUacRSeqs <= 0) {
            throw new IllegalArgumentException("reliable provisional capacities must be positive");
        }
    }
}
