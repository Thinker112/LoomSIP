package org.loomsip.exchange;

/** Indicates that a logical request was closed before a final response. */
public final class ClientRequestExchangeClosedException extends IllegalStateException {

    /** Creates a closed-exchange failure. */
    public ClientRequestExchangeClosedException() {
        super("logical client request exchange closed before completion");
    }
}
