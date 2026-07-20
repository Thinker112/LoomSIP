package org.loomsip.dialog;

/** Dialog-owned action emitted when the current RFC 4028 timer reaches its deadline. */
public enum SessionTimerAction {
    /** Local refresher must start the configured UPDATE or re-INVITE refresh. */
    REFRESH,
    /** Remote refresher missed its deadline; the Dialog must expire. */
    EXPIRE
}
