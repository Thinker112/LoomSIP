package org.loomsip.auth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Asynchronous lookup boundary for UAS precomputed Digest HA1 records. */
@FunctionalInterface
public interface DigestCredentialRepository {

    /**
     * Finds a precomputed credential matching the claimed Digest identity.
     *
     * <p>An empty result must not reveal whether the username exists; callers
     * answer it with the same generic challenge used for invalid credentials.</p>
     *
     * @param realm challenged protection space
     * @param username claimed Digest username
     * @param algorithm requested Digest algorithm
     * @return asynchronous matching HA1 record, or empty
     */
    CompletionStage<Optional<DigestCredentialRecord>> find(
            String realm,
            String username,
            DigestAlgorithm algorithm
    );
}
