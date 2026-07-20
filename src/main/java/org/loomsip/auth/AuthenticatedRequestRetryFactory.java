package org.loomsip.auth;

import org.loomsip.message.SipRequest;

import java.util.concurrent.CompletionStage;

/**
 * Rebuilds one authenticated SIP request before a new transaction attempt.
 *
 * <p>The factory owns new Via branch and CSeq allocation. For an in-Dialog
 * request it must delegate CSeq allocation to the owning Dialog Mailbox. The
 * coordinator applies the calculated Authorization field after this factory
 * returns, so the factory must not depend on a previous authorization value.</p>
 */
@FunctionalInterface
public interface AuthenticatedRequestRetryFactory {

    /**
     * Rebuilds an immutable request for a selected Digest challenge.
     *
     * @param previousRequest request from the challenged attempt
     * @param scope origin or proxy authentication scope
     * @param challenge selected parsed challenge
     * @param authorization calculated authorization parameters
     * @return asynchronous rebuilt request with new branch/CSeq; the coordinator applies authorization
     */
    CompletionStage<SipRequest> rebuild(
            SipRequest previousRequest,
            DigestAuthenticationScope scope,
            DigestChallenge challenge,
            DigestAuthorization authorization
    );
}
