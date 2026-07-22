package org.loomsip.stack;

/** Asynchronous observer for Stack lifecycle changes and failures. */
public interface SipStackListener {
    /** @param snapshot state after a lifecycle transition */
    default void onStateChanged(StackStateSnapshot snapshot) { }
    /** @param snapshot diagnostic state at failure time @param cause root failure */
    default void onFailure(StackStateSnapshot snapshot, Throwable cause) { }
}
