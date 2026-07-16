package org.loomsip.transaction.timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Base timing constants from which transaction timers are derived.
 *
 * @param t1 estimated round-trip time
 * @param t2 maximum retransmission interval for non-INVITE requests and INVITE responses
 * @param t4 maximum duration a message remains in the network
 */
public record SipTimerConfig(Duration t1, Duration t2, Duration t4) {

    /** RFC default timing constants: T1=500ms, T2=4s, T4=5s. */
    public static final SipTimerConfig DEFAULT = new SipTimerConfig(
            Duration.ofMillis(500),
            Duration.ofSeconds(4),
            Duration.ofSeconds(5)
    );

    /**
     * Validates and creates timer configuration.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if a duration is non-positive or T2 is below T1
     */
    public SipTimerConfig {
        Objects.requireNonNull(t1, "t1");
        Objects.requireNonNull(t2, "t2");
        Objects.requireNonNull(t4, "t4");
        if (t1.isZero() || t1.isNegative()
                || t2.isZero() || t2.isNegative()
                || t4.isZero() || t4.isNegative()) {
            throw new IllegalArgumentException("T1, T2, and T4 must be positive");
        }
        if (t2.compareTo(t1) < 0) {
            throw new IllegalArgumentException("T2 must not be shorter than T1");
        }
    }

    /**
     * Returns the standard 64*T1 total transaction timeout.
     *
     * @return 64 times T1
     * @throws ArithmeticException if the duration overflows
     */
    public Duration sixtyFourT1() {
        return t1.multipliedBy(64);
    }
}
