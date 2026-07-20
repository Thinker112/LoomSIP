package org.loomsip.auth;

import org.loomsip.message.SipRequest;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Stateless calculator for supported SIP Digest {@code qop=auth} responses.
 *
 * <pre>{@code
 * HA1 = H(username : realm : password)
 * HA2 = H(method : digest-uri)
 * response = H(HA1 : nonce : nc : cnonce : auth : HA2)
 * }</pre>
 */
public final class DigestCalculator {

    /** Creates a stateless Digest calculator. */
    public DigestCalculator() {
    }

    /**
     * Creates one Authorization value for an immutable SIP request.
     *
     * @param challenge selected server challenge offering {@code qop=auth}
     * @param username credential username
     * @param password credential password characters; callers retain ownership
     * @param request immutable request being retried
     * @param nonceCount positive nonce-count value no greater than {@code 0xffffffff}
     * @param cnonce client nonce
     * @return immutable Authorization parameters
     */
    public DigestAuthorization authorize(
            DigestChallenge challenge,
            String username,
            char[] password,
            SipRequest request,
            long nonceCount,
            String cnonce
    ) {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cnonce, "cnonce");
        if (!challenge.supportsAuthQop()) {
            throw new DigestUnsupportedChallengeException("Digest challenge does not offer qop=auth");
        }
        if (nonceCount <= 0 || nonceCount > 0xffff_ffffL) {
            throw new IllegalArgumentException("nonceCount must be between 1 and 0xffffffff");
        }
        String uri = request.requestUri().toString();
        String nonceCountValue = String.format(Locale.ROOT, "%08x", nonceCount);
        String response = response(
                challenge.algorithm(),
                challenge.charset().charset(),
                username,
                challenge.realm(),
                password,
                request.method().value(),
                uri,
                challenge.nonce(),
                nonceCountValue,
                cnonce,
                DigestQop.AUTH
        );
        return new DigestAuthorization(
                username,
                challenge.realm(),
                challenge.nonce(),
                uri,
                response,
                challenge.algorithm(),
                DigestQop.AUTH,
                nonceCountValue,
                cnonce,
                challenge.opaque()
        );
    }

    static String response(
            DigestAlgorithm algorithm,
            Charset charset,
            String username,
            String realm,
            char[] password,
            String method,
            String uri,
            String nonce,
            String nonceCount,
            String cnonce,
            DigestQop qop
    ) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(nonceCount, "nonceCount");
        Objects.requireNonNull(cnonce, "cnonce");
        Objects.requireNonNull(qop, "qop");
        char[] passwordCopy = Arrays.copyOf(password, password.length);
        try {
            String ha1 = hashHa1(algorithm, charset, username, realm, passwordCopy);
            String ha2 = hash(algorithm, charset, method + ':' + uri);
            return hash(
                    algorithm,
                    charset,
                    ha1 + ':' + nonce + ':' + nonceCount + ':' + cnonce + ':' + qop.wireName()
                            + ':' + ha2
            );
        } finally {
            Arrays.fill(passwordCopy, '\0');
        }
    }

    static String responseFromHa1(
            DigestAlgorithm algorithm,
            Charset charset,
            String ha1,
            String method,
            String uri,
            String nonce,
            String nonceCount,
            String cnonce,
            DigestQop qop
    ) {
        Objects.requireNonNull(ha1, "ha1");
        String ha2 = hash(algorithm, charset, method + ':' + uri);
        return hash(
                algorithm,
                charset,
                ha1 + ':' + nonce + ':' + nonceCount + ':' + cnonce + ':' + qop.wireName() + ':' + ha2
        );
    }

    private static String hash(DigestAlgorithm algorithm, Charset charset, String input) {
        byte[] encoded = input.getBytes(charset);
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.jcaName());
            return HexFormat.of().formatHex(digest.digest(encoded));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK lacks Digest algorithm " + algorithm.wireName(), exception);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String hashHa1(
            DigestAlgorithm algorithm,
            Charset charset,
            String username,
            String realm,
            char[] password
    ) {
        byte[] usernameBytes = username.getBytes(charset);
        byte[] realmBytes = realm.getBytes(charset);
        byte[] separator = { ':' };
        ByteBuffer passwordBuffer = charset.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[passwordBuffer.remaining()];
        passwordBuffer.get(passwordBytes);
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.jcaName());
            digest.update(usernameBytes);
            digest.update(separator);
            digest.update(realmBytes);
            digest.update(separator);
            return HexFormat.of().formatHex(digest.digest(passwordBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK lacks Digest algorithm " + algorithm.wireName(), exception);
        } finally {
            Arrays.fill(usernameBytes, (byte) 0);
            Arrays.fill(realmBytes, (byte) 0);
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }
}
