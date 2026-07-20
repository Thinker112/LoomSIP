package org.loomsip.auth;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/** Cryptographically strong hexadecimal SIP To-tag generator. */
public final class SecureServerTagGenerator implements ServerTagGenerator {

    private final SecureRandom random;

    /** Creates a generator using a new JDK {@link SecureRandom} instance. */
    public SecureServerTagGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a generator using an externally owned secure random source.
     *
     * @param random random source
     */
    public SecureServerTagGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Returns 96 random bits as a valid lowercase hexadecimal tag token. */
    @Override
    public String nextTag() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
