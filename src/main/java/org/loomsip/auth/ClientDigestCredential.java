package org.loomsip.auth;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-use UAC credential whose password characters are cleared on close.
 *
 * <p>A {@link ClientCredentialProvider} transfers ownership of each returned
 * instance to the authentication coordinator. Providers must not reuse an
 * instance after returning it.</p>
 */
public final class ClientDigestCredential implements AutoCloseable {

    private final String username;
    private final char[] password;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates one credential from a defensively copied password.
     *
     * @param username Digest username
     * @param password password characters; caller retains ownership of its array
     */
    public ClientDigestCredential(String username, char[] password) {
        this.username = Objects.requireNonNull(username, "username");
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be empty");
        }
        this.password = Arrays.copyOf(Objects.requireNonNull(password, "password"), password.length);
    }

    /**
     * Returns the immutable username.
     *
     * @return username supplied to Digest calculation
     */
    public String username() {
        return username;
    }

    /**
     * Returns a temporary defensive copy of the password characters.
     *
     * <p>The returned array belongs to the caller and should be cleared after
     * use. Calling this method after {@link #close()} is rejected.</p>
     *
     * @return independent password copy
     */
    public char[] copyPassword() {
        if (closed.get()) {
            throw new IllegalStateException("Digest credential is closed");
        }
        return Arrays.copyOf(password, password.length);
    }

    /** Clears this instance's password characters exactly once. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Arrays.fill(password, '\0');
        }
    }

    /** Returns a diagnostic form that intentionally excludes password material. */
    @Override
    public String toString() {
        return "ClientDigestCredential[username=" + username + "]";
    }
}
