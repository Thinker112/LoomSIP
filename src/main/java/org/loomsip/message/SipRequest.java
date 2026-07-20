package org.loomsip.message;

import java.util.Objects;

/**
 * Immutable SIP request including its request-line, headers, and body.
 *
 * @param method request method, including extension methods
 * @param requestUri target URI from the request-line
 * @param version SIP protocol version
 * @param headers immutable ordered request headers
 * @param body immutable binary request body
 */
public record SipRequest(
        SipMethod method,
        SipUri requestUri,
        SipVersion version,
        SipHeaders headers,
        SipBody body
) implements SipMessage {

    /**
     * Validates and creates a complete request.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public SipRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    /**
     * Creates an empty-body SIP/2.0 request.
     *
     * @param method request method
     * @param requestUri request target
     * @param headers immutable ordered headers
     * @throws NullPointerException if any argument is {@code null}
     */
    public SipRequest(SipMethod method, SipUri requestUri, SipHeaders headers) {
        this(method, requestUri, SipVersion.SIP_2_0, headers, SipBody.empty());
    }

    /**
     * Creates a mutable copy builder initialized from this immutable request.
     *
     * @return independent request copy builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Structured copy builder for retrying or adapting an immutable request.
     *
     * <p>The builder starts from a valid source request. Header removal and
     * replacement use SIP compact-name equivalence and never manipulate the
     * encoded wire message as text.</p>
     */
    public static final class Builder {

        private SipMethod method;
        private SipUri requestUri;
        private SipVersion version;
        private SipHeaders headers;
        private SipBody body;

        private Builder(SipRequest source) {
            Objects.requireNonNull(source, "source");
            method = source.method();
            requestUri = source.requestUri();
            version = source.version();
            headers = source.headers();
            body = source.body();
        }

        /**
         * Replaces the request method.
         *
         * @param method replacement method
         * @return this builder
         */
        public Builder method(SipMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Replaces the request URI.
         *
         * @param requestUri replacement request URI
         * @return this builder
         */
        public Builder requestUri(SipUri requestUri) {
            this.requestUri = Objects.requireNonNull(requestUri, "requestUri");
            return this;
        }

        /**
         * Replaces the SIP version.
         *
         * @param version replacement version
         * @return this builder
         */
        public Builder version(SipVersion version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        /**
         * Replaces the complete immutable header collection.
         *
         * @param headers replacement headers
         * @return this builder
         */
        public Builder headers(SipHeaders headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        /**
         * Removes every header equivalent to the supplied name.
         *
         * @param name standard or compact header name
         * @return this builder
         */
        public Builder removeHeader(String name) {
            headers = headers.without(name);
            return this;
        }

        /**
         * Replaces all equivalent headers with one value.
         *
         * @param name header name
         * @param value replacement unfolded value
         * @return this builder
         */
        public Builder replaceHeader(String name, String value) {
            headers = headers.withReplaced(name, value);
            return this;
        }

        /**
         * Appends one header without replacing existing equivalent fields.
         *
         * @param name header name
         * @param value unfolded header value
         * @return this builder
         */
        public Builder addHeader(String name, String value) {
            headers = headers.toBuilder().add(name, value).build();
            return this;
        }

        /**
         * Replaces the immutable body.
         *
         * @param body replacement body
         * @return this builder
         */
        public Builder body(SipBody body) {
            this.body = Objects.requireNonNull(body, "body");
            return this;
        }

        /**
         * Builds an independent immutable request.
         *
         * @return rebuilt request
         */
        public SipRequest build() {
            return new SipRequest(method, requestUri, version, headers, body);
        }
    }
}
