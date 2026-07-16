package org.loomsip.transaction.invite;

/** Result of associating an inbound CANCEL with an INVITE server transaction. */
public enum CancelMatch {
    /** A related IST was found; CANCEL receives 200. */
    MATCHED,
    /** No related IST was found; CANCEL receives 481. */
    UNMATCHED
}
