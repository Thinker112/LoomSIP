package org.loomsip.subscription;

import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.Objects;

/** Stable RFC 3265 subscription identity within one local endpoint role. */
public record SubscriptionId(String callId, String localTag, String remoteTag, EventHeaderValue event) {

    /** Validates SIP correlation fields and the Event package discriminator. */
    public SubscriptionId {
        callId = requireText(callId, "callId");
        localTag = requireText(localTag, "localTag");
        remoteTag = requireText(remoteTag, "remoteTag");
        Objects.requireNonNull(event, "event");
    }

    /**
     * Derives the local UAC subscription identity from one inbound NOTIFY.
     *
     * <p>An inbound request's To tag identifies the local endpoint while its
     * From tag identifies the notifier. Transaction routing must validate the
     * request before using this helper to mutate subscription state.</p>
     *
     * @param headers inbound NOTIFY headers
     * @return local subscription identity
     * @throws SipHeaderValueException if a required correlation header is absent or malformed
     */
    public static SubscriptionId fromIncomingNotify(SipHeaders headers) throws SipHeaderValueException {
        return new SubscriptionId(
                SipHeaderValues.callId(headers),
                SipHeaderValues.toTag(headers).orElseThrow(
                        () -> new SipHeaderValueException("NOTIFY is missing local To tag")
                ),
                SipHeaderValues.fromTag(headers).orElseThrow(
                        () -> new SipHeaderValueException("NOTIFY is missing remote From tag")
                ),
                SipHeaderValues.event(headers)
        );
    }

    /** Derives the local UAC identity after a successful initial SUBSCRIBE response. */
    public static SubscriptionId fromSubscribeResponse(SipRequest request, SipResponse response)
            throws SipHeaderValueException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        return new SubscriptionId(
                SipHeaderValues.callId(request.headers()),
                SipHeaderValues.fromTag(request.headers()).orElseThrow(
                        () -> new SipHeaderValueException("SUBSCRIBE is missing local From tag")
                ),
                SipHeaderValues.toTag(response.headers()).orElseThrow(
                        () -> new SipHeaderValueException("SUBSCRIBE response is missing remote To tag")
                ),
                SipHeaderValues.event(request.headers())
        );
    }

    /** Derives one local UAS identity from an accepted initial SUBSCRIBE and generated local To tag. */
    public static SubscriptionId fromIncomingSubscribe(SipRequest request, String localTag)
            throws SipHeaderValueException {
        Objects.requireNonNull(request, "request");
        return new SubscriptionId(
                SipHeaderValues.callId(request.headers()),
                requireText(localTag, "localTag"),
                SipHeaderValues.fromTag(request.headers()).orElseThrow(
                        () -> new SipHeaderValueException("SUBSCRIBE is missing remote From tag")
                ),
                SipHeaderValues.event(request.headers())
        );
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must not be blank or contain whitespace");
        }
        return value;
    }
}
