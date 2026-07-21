package org.loomsip.refer;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.header.ReferSubHeaderValue;
import org.loomsip.message.header.ReferToHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;

/**
 * Parsed inbound RFC 3515 REFER request presented to the transfer application.
 *
 * <pre>{@code
 * inbound REFER --> Refer-To / Refer-Sub parser --> ReferRequest --> TU handler
 * }</pre>
 *
 * @param request immutable original REFER request
 * @param referTo structured target to which the recipient is referred
 * @param referSub requested implicit refer-subscription preference
 */
public record ReferRequest(SipRequest request, ReferToHeaderValue referTo, ReferSubHeaderValue referSub) {

    /** Validates the immutable REFER request and parsed RFC 3515 headers. */
    public ReferRequest {
        request = Objects.requireNonNull(request, "request");
        if (!SipMethod.REFER.equals(request.method())) {
            throw new IllegalArgumentException("ReferRequest requires a REFER request");
        }
        Objects.requireNonNull(referTo, "referTo");
        Objects.requireNonNull(referSub, "referSub");
    }

    /**
     * Parses the required REFER headers once at the protocol boundary.
     *
     * @param request inbound REFER request
     * @return structured REFER request
     * @throws SipHeaderValueException if Refer-To or Refer-Sub is malformed
     */
    public static ReferRequest parse(SipRequest request) throws SipHeaderValueException {
        Objects.requireNonNull(request, "request");
        if (!SipMethod.REFER.equals(request.method())) {
            throw new IllegalArgumentException("ReferRequest requires a REFER request");
        }
        return new ReferRequest(request, SipHeaderValues.referTo(request.headers()), SipHeaderValues.referSub(request.headers()));
    }
}
