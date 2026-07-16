package org.loomsip.message;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Creates response templates from the correlation fields of a SIP request.
 *
 * <p>This utility does not create or manage a server transaction. Applications
 * remain responsible for adding response-specific fields such as Contact,
 * Allow, or authentication challenges.</p>
 */
public final class SipResponses {

    private SipResponses() {
    }

    /**
     * Creates an empty-body response while preserving the request's To field.
     *
     * <p>This overload is suitable for responses that intentionally do not add
     * a local To tag, such as a 100 response. Final dialog-forming responses
     * should use the overload that accepts {@code localTag}.</p>
     *
     * @param request request being answered
     * @param statusCode response status in the range 100 through 699
     * @param reasonPhrase response reason phrase
     * @return response containing Via, From, To, Call-ID, and CSeq fields
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a required correlation header is missing
     */
    public static SipResponse createResponse(
            SipRequest request,
            int statusCode,
            String reasonPhrase
    ) {
        return createResponseInternal(request, statusCode, reasonPhrase, null);
    }

    /**
     * Creates an empty-body response and adds a local tag to To when absent.
     *
     * @param request request being answered
     * @param statusCode response status in the range 100 through 699
     * @param reasonPhrase response reason phrase
     * @param localTag local To tag token used when the request has no To tag
     * @return response containing Via, From, To, Call-ID, and CSeq fields
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a required header is missing or the tag is invalid
     */
    public static SipResponse createResponse(
            SipRequest request,
            int statusCode,
            String reasonPhrase,
            String localTag
    ) {
        Objects.requireNonNull(localTag, "localTag");
        return createResponseInternal(request, statusCode, reasonPhrase, localTag);
    }

    private static SipResponse createResponseInternal(
            SipRequest request,
            int statusCode,
            String reasonPhrase,
            String localTag
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reasonPhrase, "reasonPhrase");
        if (localTag != null) {
            SipSyntax.requireToken(localTag, "local tag");
        }

        SipHeaders.Builder responseHeaders = SipHeaders.builder();
        List<SipHeader> vias = request.headers().all("Via");
        if (vias.isEmpty()) {
            throw new IllegalArgumentException("request has no Via header");
        }
        responseHeaders.addAll(vias);
        responseHeaders.add(requiredHeader(request, "From"));

        SipHeader to = requiredHeader(request, "To");
        String toValue = localTag == null ? to.value() : withTag(to.value(), localTag);
        responseHeaders.add(new SipHeader(to.name(), toValue));
        responseHeaders.add(requiredHeader(request, "Call-ID"));
        responseHeaders.add(requiredHeader(request, "CSeq"));

        return new SipResponse(
                request.version(),
                statusCode,
                reasonPhrase,
                responseHeaders.build(),
                SipBody.empty()
        );
    }

    private static SipHeader requiredHeader(SipRequest request, String name) {
        return request.headers().first(name).orElseThrow(
                () -> new IllegalArgumentException("request has no " + name + " header")
        );
    }

    private static String withTag(String toValue, String localTag) {
        if (hasParameter(toValue, "tag")) {
            return toValue;
        }
        return toValue + ";tag=" + localTag;
    }

    private static boolean hasParameter(String value, String expectedName) {
        int closingAngle = value.lastIndexOf('>');
        int position = closingAngle >= 0 ? closingAngle + 1 : value.indexOf(';');
        if (position < 0) {
            return false;
        }

        while (position < value.length()) {
            int semicolon = value.indexOf(';', position);
            if (semicolon < 0) {
                return false;
            }
            int nameStart = semicolon + 1;
            while (nameStart < value.length() && Character.isWhitespace(value.charAt(nameStart))) {
                nameStart++;
            }
            int nameEnd = nameStart;
            while (nameEnd < value.length()) {
                char character = value.charAt(nameEnd);
                if (character == '=' || character == ';' || Character.isWhitespace(character)) {
                    break;
                }
                nameEnd++;
            }
            if (value.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT).equals(expectedName)) {
                return true;
            }
            position = nameEnd;
        }
        return false;
    }
}
