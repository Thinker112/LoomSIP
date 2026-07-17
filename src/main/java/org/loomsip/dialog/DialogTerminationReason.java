package org.loomsip.dialog;

/** Reason recorded when a Dialog reaches Terminated. */
public enum DialogTerminationReason {
    /** Protocol or application requested termination. */
    EXPLICIT,
    /** Owning Dialog manager is closing. */
    MANAGER_SHUTDOWN,
    /** Mailbox or execution infrastructure failed. */
    INFRASTRUCTURE_FAILURE
}
