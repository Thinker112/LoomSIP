package org.loomsip.auth;

/** Indicates that a UAC authentication coordinator was closed before completion. */
public final class ClientAuthenticationClosedException extends IllegalStateException {

    /** Creates the standard close failure. */
    public ClientAuthenticationClosedException() {
        super("client authentication coordinator is closed");
    }
}
