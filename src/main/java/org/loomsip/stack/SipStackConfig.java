package org.loomsip.stack;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable lifecycle settings for one {@link LoomSipStack}.
 *
 * @param shutdownTimeout maximum time {@link LoomSipStack#close()} waits for asynchronous close completion
 */
public record SipStackConfig(Duration shutdownTimeout) {

    /** Default lifecycle settings suitable for one embedded stack instance. */
    public static final SipStackConfig DEFAULT = new SipStackConfig(Duration.ofSeconds(10));

    /** Validates a positive bounded shutdown wait. */
    public SipStackConfig {
        shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
    }
}
