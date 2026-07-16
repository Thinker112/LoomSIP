package org.loomsip.transaction;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.ViaHeaderValue;

import java.util.Objects;

/**
 * Derives modern and legacy transaction keys from immutable SIP messages.
 *
 * <pre>{@code
 * SipRequest / SipResponse
 *           |
 *           v
 * +-----------------------+
 * | TransactionKeyFactory |
 * | - top Via             |
 * | - CSeq method         |
 * | - ACK/CANCEL mapping  |
 * +-----------+-----------+
 *             |
 *        +----+----+
 *        |         |
 *        v         v
 * Rfc3261Key   LegacyKey
 *        |         |
 *        +----+----+
 *             v
 * TransactionRepository
 * }</pre>
 */
public final class TransactionKeyFactory {

    private TransactionKeyFactory() {
    }

    /**
     * Creates the transaction's own key from a request.
     *
     * @param request request containing Via, CSeq, From, To, and Call-ID
     * @return RFC 3261 or legacy transaction key
     * @throws TransactionKeyException if routing headers are inconsistent or malformed
     */
    public static TransactionKey fromRequest(SipRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        return fromRequest(request, request.method());
    }

    /**
     * Creates the lookup key used for an inbound server request.
     *
     * <p>Non-2xx ACK uses the INVITE method key. A lookup miss indicates that
     * the ACK belongs to a 2xx response path and must be forwarded to TU/Dialog.</p>
     *
     * @param request inbound request
     * @return server-transaction lookup key
     * @throws TransactionKeyException if routing headers are inconsistent or malformed
     */
    public static TransactionKey forServerLookup(SipRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        SipMethod keyMethod = SipMethod.ACK.equals(request.method()) ? SipMethod.INVITE : request.method();
        return fromRequest(request, keyMethod);
    }

    /**
     * Derives the original INVITE key related to an INVITE, ACK, or CANCEL request.
     *
     * @param request related request
     * @return key using INVITE as its matching method
     * @throws TransactionKeyException if the method is unrelated or headers are malformed
     */
    public static TransactionKey relatedInvite(SipRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        if (!SipMethod.INVITE.equals(request.method())
                && !SipMethod.ACK.equals(request.method())
                && !SipMethod.CANCEL.equals(request.method())) {
            throw new TransactionKeyException("method is not related to an INVITE transaction: " + request.method());
        }
        return fromRequest(request, SipMethod.INVITE);
    }

    /**
     * Creates a client-transaction lookup key from a response.
     *
     * <p>Responses cannot provide the Request-URI required for legacy matching,
     * so this milestone accepts only RFC 3261 magic-cookie branches here.</p>
     *
     * @param response inbound response
     * @return RFC 3261 client transaction key
     * @throws TransactionKeyException if routing fields are malformed or legacy
     */
    public static Rfc3261TransactionKey fromResponse(SipResponse response)
            throws TransactionKeyException {
        Objects.requireNonNull(response, "response");
        try {
            ViaHeaderValue via = SipHeaderValues.topVia(response.headers());
            CSeqHeaderValue cseq = SipHeaderValues.cseq(response.headers());
            String branch = via.branch().orElseThrow(
                    () -> new TransactionKeyException("response top Via has no branch")
            );
            if (!via.hasMagicCookie()) {
                throw new TransactionKeyException("legacy response transaction matching is not supported");
            }
            return modernKey(via, branch, cseq.method());
        } catch (SipHeaderValueException | IllegalArgumentException exception) {
            throw new TransactionKeyException("cannot derive response transaction key", exception);
        }
    }

    private static TransactionKey fromRequest(SipRequest request, SipMethod keyMethod)
            throws TransactionKeyException {
        try {
            ViaHeaderValue via = SipHeaderValues.topVia(request.headers());
            CSeqHeaderValue cseq = SipHeaderValues.cseq(request.headers());
            if (!cseq.method().equals(request.method())) {
                throw new TransactionKeyException(
                        "request method " + request.method() + " does not match CSeq method " + cseq.method()
                );
            }

            if (via.hasMagicCookie()) {
                String branch = via.branch().orElseThrow();
                return modernKey(via, branch, keyMethod);
            }
            return new LegacyTransactionKey(
                    request.requestUri(),
                    SipHeaderValues.fromTag(request.headers()),
                    SipHeaderValues.toTag(request.headers()),
                    SipHeaderValues.callId(request.headers()),
                    cseq.sequenceNumber(),
                    SipHeaderValues.topViaRawValue(request.headers()),
                    keyMethod
            );
        } catch (SipHeaderValueException | IllegalArgumentException exception) {
            throw new TransactionKeyException("cannot derive request transaction key", exception);
        }
    }

    private static Rfc3261TransactionKey modernKey(
            ViaHeaderValue via,
            String branch,
            SipMethod method
    ) {
        SentBy normalizedSentBy = new SentBy(
                via.sentBy().host(),
                via.sentBy().effectivePort(via.transport())
        );
        return new Rfc3261TransactionKey(branch, normalizedSentBy, method);
    }
}
