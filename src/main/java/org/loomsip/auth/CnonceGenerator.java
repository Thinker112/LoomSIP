package org.loomsip.auth;

/** Generates one opaque client nonce for a Digest Authorization calculation. */
@FunctionalInterface
public interface CnonceGenerator {

    /**
     * Generates one non-empty client nonce.
     *
     * @return nonce suitable for a quoted Authorization parameter
     */
    String nextCnonce();
}
