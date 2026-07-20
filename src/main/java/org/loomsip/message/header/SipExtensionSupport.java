package org.loomsip.message.header;

import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Parses and writes comma-separated SIP extension option tags. */
public final class SipExtensionSupport {

    /** RFC 3262 option tag for reliable provisional responses. */
    public static final String RELIABLE_PROVISIONAL = "100rel";

    private SipExtensionSupport() {
    }

    /**
     * Tests whether an extension token occurs in Supported or Require headers.
     *
     * @param headers SIP headers
     * @param headerName Supported or Require header name
     * @param token case-insensitive extension token
     * @return whether the token is present
     */
    public static boolean contains(SipHeaders headers, String headerName, String token) {
        return tokens(headers, headerName).contains(normalizeToken(token));
    }

    /**
     * Returns unique normalized tokens from all matching extension headers.
     *
     * @param headers SIP headers
     * @param headerName Supported or Require header name
     * @return immutable normalized option tags
     * @throws IllegalArgumentException if a list item is not a SIP token
     */
    public static Set<String> tokens(SipHeaders headers, String headerName) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(headerName, "headerName");
        Set<String> tokens = new LinkedHashSet<>();
        for (SipHeader header : headers.all(headerName)) {
            for (String item : header.value().split(",", -1)) {
                tokens.add(normalizeToken(item.strip()));
            }
        }
        return Set.copyOf(tokens);
    }

    /**
     * Returns a copy with a token appended to a Supported or Require field.
     *
     * @param headers source headers
     * @param headerName Supported or Require header name
     * @param token extension option tag
     * @return source headers when already present, otherwise a copy with one appended field
     */
    public static SipHeaders withAdded(SipHeaders headers, String headerName, String token) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(headerName, "headerName");
        String normalized = normalizeToken(token);
        if (contains(headers, headerName, normalized)) {
            return headers;
        }
        return headers.toBuilder().add(headerName, normalized).build();
    }

    private static String normalizeToken(String value) {
        Objects.requireNonNull(value, "token");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SIP extension token must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 32 || character >= 127 || "()<>@,;:\\\"/[]?={}".indexOf(character) >= 0) {
                throw new IllegalArgumentException("invalid SIP extension token");
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
