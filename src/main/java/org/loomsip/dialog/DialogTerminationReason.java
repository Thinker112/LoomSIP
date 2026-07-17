package org.loomsip.dialog;

/** Reason recorded when a Dialog reaches Terminated. */
public enum DialogTerminationReason {
    /** Protocol or application requested termination. */
    EXPLICIT,
    /** A non-success final response ended an Early Dialog. */
    NON_SUCCESS_FINAL_RESPONSE,
    /** The dialog-forming INVITE transaction ended with an Early Dialog remaining. */
    INVITE_TRANSACTION_TERMINATED,
    /** Owning Dialog manager is closing. */
    MANAGER_SHUTDOWN,
    /** Mailbox or execution infrastructure failed. */
    INFRASTRUCTURE_FAILURE
}
