package org.loomsip.transaction.event;

import org.loomsip.transaction.timer.SipTimer;

import java.util.Objects;

/**
 * Scheduled timer event carrying a generation used to reject stale callbacks.
 *
 * @param timer SIP transaction timer
 * @param generation timer generation allocated when scheduled
 */
public record TimerExpired(
        SipTimer timer,
        long generation
) implements TransactionEvent {

    /**
     * Validates and creates a timer event.
     *
     * @throws NullPointerException if {@code timer} is {@code null}
     * @throws IllegalArgumentException if generation is not positive
     */
    public TimerExpired {
        Objects.requireNonNull(timer, "timer");
        if (generation <= 0) {
            throw new IllegalArgumentException("timer generation must be positive");
        }
    }
}
