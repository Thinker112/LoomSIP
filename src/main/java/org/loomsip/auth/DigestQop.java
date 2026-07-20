package org.loomsip.auth;

/** Digest quality-of-protection values supported by the initial implementation. */
public enum DigestQop {
    /** Header-only digest protection; message-body hashing is not required. */
    AUTH("auth");

    private final String wireName;

    DigestQop(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the token emitted in an Authorization header.
     *
     * @return protocol qop token
     */
    public String wireName() {
        return wireName;
    }
}
