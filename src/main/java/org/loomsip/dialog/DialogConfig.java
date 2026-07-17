package org.loomsip.dialog;

/**
 * Capacity limits for one Dialog manager.
 *
 * @param dialogs maximum active Dialogs
 * @param mailboxCapacity maximum queued events per Dialog
 * @param callbackCapacity maximum queued TU callbacks per Dialog
 */
public record DialogConfig(int dialogs, int mailboxCapacity, int callbackCapacity) {

    /** Conservative initial capacities. */
    public static final DialogConfig DEFAULT = new DialogConfig(10_000, 256, 64);

    /** Validates that every capacity is positive. */
    public DialogConfig {
        if (dialogs <= 0 || mailboxCapacity <= 0 || callbackCapacity <= 0) {
            throw new IllegalArgumentException("all Dialog capacities must be positive");
        }
    }
}
