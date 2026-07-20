package org.loomsip.dialog;

/** Result of correlating one inbound PRACK to a UAS reliable provisional response. */
public enum PrackValidation {
    /** RAck matched the currently pending reliable provisional response. */
    ACCEPTED,
    /** No pending response or a mismatched RAck requires a 481 response. */
    MISMATCH
}
