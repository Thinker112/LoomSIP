package org.loomsip.transport;

/**
 * Per-connection bounds for writes accepted by a reliable transport.
 *
 * @param maxPendingWritesPerConnection maximum incomplete writes per connection
 * @param maxPendingWriteBytesPerConnection maximum encoded bytes per connection
 */
public record WriteQueueLimits(
        int maxPendingWritesPerConnection,
        long maxPendingWriteBytesPerConnection
) {

    /** Defaults suitable for a normal SIP connection without unbounded buffering. */
    public static final WriteQueueLimits DEFAULT = new WriteQueueLimits(
            1_024,
            8L * 1024 * 1024
    );

    /** Validates write count and byte limits. */
    public WriteQueueLimits {
        if (maxPendingWritesPerConnection <= 0) {
            throw new IllegalArgumentException("maxPendingWritesPerConnection must be positive");
        }
        if (maxPendingWriteBytesPerConnection <= 0) {
            throw new IllegalArgumentException("maxPendingWriteBytesPerConnection must be positive");
        }
    }
}
