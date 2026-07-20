package org.loomsip.auth;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/** Cryptographically strong hexadecimal cnonce generator. */
public final class SecureCnonceGenerator implements CnonceGenerator {

    private final SecureRandom random;

    /** Creates a generator using a new JDK {@link SecureRandom} instance. */
    public SecureCnonceGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a generator using an externally owned random source.
     *
     * @param random secure random source
     */
    public SecureCnonceGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Returns 128 bits of random data encoded as lowercase hexadecimal. */
    @Override
    public String nextCnonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
