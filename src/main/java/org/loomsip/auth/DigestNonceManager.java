package org.loomsip.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded server nonce registry with expiry and atomic qop replay protection.
 *
 * <pre>{@code
 * UAS authentication gate
 *          |
 *          v
 * Digest Nonce Manager
 *   | issue / validate / consume nc |
 *          |
 *          v
 * active nonce + username/cnonce count state
 * }</pre>
 *
 * <p>All mutable nonce and nonce-count state is guarded by one monitor. Digest
 * hash calculation remains outside this component; callers first validate the
 * nonce, verify the response hash, then atomically consume the nonce count.</p>
 */
public final class DigestNonceManager implements AutoCloseable {

    private final Object monitor = new Object();
    private final ServerAuthenticationPolicy policy;
    private final SecureRandom random;
    private final Clock clock;
    private final Map<String, ActiveNonce> active = new HashMap<>();
    private boolean closed;

    /**
     * Creates a manager using secure randomness and the system UTC clock.
     *
     * @param policy owning server authentication policy
     */
    public DigestNonceManager(ServerAuthenticationPolicy policy) {
        this(policy, new SecureRandom(), Clock.systemUTC());
    }

    /**
     * Creates a manager with externally supplied randomness and clock for tests.
     *
     * @param policy owning server authentication policy
     * @param random cryptographically secure nonce source
     * @param clock time source used for expiry checks
     */
    public DigestNonceManager(ServerAuthenticationPolicy policy, SecureRandom random, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Issues one nonce for the strongest currently configured algorithm.
     *
     * @return active server nonce
     * @throws IllegalStateException if the manager is closed
     */
    public DigestNonce issue() {
        return issue(policy.preferredAlgorithm());
    }

    /**
     * Issues one nonce bound to a configured algorithm.
     *
     * @param algorithm challenge algorithm
     * @return active server nonce
     * @throws IllegalArgumentException if algorithm is not enabled by policy
     * @throws IllegalStateException if the manager is closed
     */
    public DigestNonce issue(DigestAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        if (!policy.allowedAlgorithms().contains(algorithm)) {
            throw new IllegalArgumentException("Digest algorithm is not enabled by policy");
        }
        synchronized (monitor) {
            ensureOpen();
            Instant now = clock.instant();
            pruneExpired(now);
            if (active.size() >= policy.maxActiveNonces()) {
                active.values().stream()
                        .min(Comparator.comparing(value -> value.nonce.expiresAt()))
                        .ifPresent(oldest -> active.remove(oldest.nonce.value()));
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String value = HexFormat.of().formatHex(bytes);
            java.util.Arrays.fill(bytes, (byte) 0);
            DigestNonce nonce = new DigestNonce(
                    value,
                    policy.realm(),
                    algorithm,
                    policy.charset(),
                    now.plus(policy.nonceLifetime())
            );
            active.put(value, new ActiveNonce(nonce));
            return nonce;
        }
    }

    /**
     * Validates nonce ownership and expiry without consuming a nonce count.
     *
     * @param value client-presented nonce value
     * @param realm client-presented realm
     * @param algorithm client-presented algorithm
     * @return status and active nonce metadata; stale nonces are removed after detection
     */
    public DigestNonceValidation validate(String value, String realm, DigestAlgorithm algorithm) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(algorithm, "algorithm");
        synchronized (monitor) {
            if (closed) {
                return DigestNonceValidation.withoutNonce(DigestNonceStatus.UNKNOWN);
            }
            ActiveNonce selected = active.get(value);
            if (selected == null || !selected.nonce.realm().equals(realm)
                    || selected.nonce.algorithm() != algorithm) {
                return DigestNonceValidation.withoutNonce(DigestNonceStatus.UNKNOWN);
            }
            if (!clock.instant().isBefore(selected.nonce.expiresAt())) {
                active.remove(value);
                return DigestNonceValidation.withoutNonce(DigestNonceStatus.STALE);
            }
            return new DigestNonceValidation(DigestNonceStatus.VALID, java.util.Optional.of(selected.nonce));
        }
    }

    /**
     * Atomically accepts a strictly increasing qop nonce count after digest verification.
     *
     * @param value client-presented nonce
     * @param realm client-presented realm
     * @param algorithm client-presented algorithm
     * @param username authenticated username
     * @param cnonce client nonce
     * @param nonceCount positive qop nonce count
     * @return valid, stale, unknown, or replayed status
     */
    public DigestNonceStatus consumeNonceCount(
            String value,
            String realm,
            DigestAlgorithm algorithm,
            String username,
            String cnonce,
            long nonceCount
    ) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(cnonce, "cnonce");
        if (nonceCount <= 0 || nonceCount > 0xffff_ffffL) {
            throw new IllegalArgumentException("nonceCount must be between 1 and 0xffffffff");
        }
        synchronized (monitor) {
            DigestNonceValidation validation = validate(value, realm, algorithm);
            if (validation.status() != DigestNonceStatus.VALID) {
                return validation.status();
            }
            ActiveNonce selected = active.get(value);
            ReplayKey key = new ReplayKey(username, cnonce);
            Long previous = selected.nonceCounts.get(key);
            if (previous != null && nonceCount <= previous) {
                return DigestNonceStatus.REPLAYED;
            }
            if (previous == null && selected.nonceCounts.size() >= policy.maxReplayUsersPerNonce()) {
                return DigestNonceStatus.REPLAYED;
            }
            selected.nonceCounts.put(key, nonceCount);
            return DigestNonceStatus.VALID;
        }
    }

    /**
     * Returns the count of currently active nonces after expiry pruning.
     *
     * @return active nonce count
     */
    public int activeNonceCount() {
        synchronized (monitor) {
            pruneExpired(clock.instant());
            return active.size();
        }
    }

    /** Clears active nonces and rejects further nonce issuance. */
    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            active.clear();
        }
    }

    private void pruneExpired(Instant now) {
        active.values().removeIf(value -> !now.isBefore(value.nonce.expiresAt()));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Digest nonce manager is closed");
        }
    }

    private static final class ActiveNonce {

        private final DigestNonce nonce;
        private final Map<ReplayKey, Long> nonceCounts = new HashMap<>();

        private ActiveNonce(DigestNonce nonce) {
            this.nonce = nonce;
        }
    }

    private record ReplayKey(String username, String cnonce) {
    }
}
