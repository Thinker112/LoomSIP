package org.loomsip.dialog;

/** Local endpoint role used to interpret From/To direction. */
public enum DialogRole {
    /** Local endpoint initiated the dialog-forming request. */
    UAC,
    /** Local endpoint received the dialog-forming request. */
    UAS
}
