package org.loomsip.auth;

import java.util.Locale;

/** Supported HTTP Digest algorithms for SIP authentication. */
public enum DigestAlgorithm {
    /** RFC 3261 interoperability algorithm. */
    MD5("MD5", "MD5", 1),
    /** Modern digest algorithm defined for SIP by RFC 8760. */
    SHA_256("SHA-256", "SHA-256", 2);

    private final String wireName;
    private final String jcaName;
    private final int strength;

    DigestAlgorithm(String wireName, String jcaName, int strength) {
        this.wireName = wireName;
        this.jcaName = jcaName;
        this.strength = strength;
    }

    /**
     * Parses a Digest algorithm token.
     *
     * @param value algorithm token from a challenge
     * @return supported algorithm
     * @throws DigestUnsupportedChallengeException if the token is unsupported
     */
    public static DigestAlgorithm parse(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MD5" -> MD5;
            case "SHA-256" -> SHA_256;
            default -> throw new DigestUnsupportedChallengeException(
                    "unsupported Digest algorithm: " + value
            );
        };
    }

    /**
     * Returns the token emitted in an Authorization header.
     *
     * @return protocol algorithm token
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Returns the Java Cryptography Architecture algorithm name.
     *
     * @return JCA message-digest name
     */
    public String jcaName() {
        return jcaName;
    }

    /**
     * Returns the relative security preference used for challenge selection.
     *
     * @return larger values represent stronger supported algorithms
     */
    public int strength() {
        return strength;
    }
}
