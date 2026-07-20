package org.loomsip.auth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Asynchronous lookup boundary for one-use UAC Digest credentials. */
@FunctionalInterface
public interface ClientCredentialProvider {

    /**
     * Finds a credential matching one origin/proxy challenge.
     *
     * <p>The returned credential transfers ownership to the caller and must not
     * be reused by the provider. An empty result means the target is not
     * configured with a matching credential.</p>
     *
     * @param request immutable credential lookup key
     * @return asynchronous optional credential result
     */
    CompletionStage<Optional<ClientDigestCredential>> find(ClientCredentialRequest request);
}
