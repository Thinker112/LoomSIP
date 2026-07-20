package org.loomsip.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestCalculatorTest {

    @Test
    void calculatesRfc2617Md5QopAuthVector() {
        String response = DigestCalculator.response(
                DigestAlgorithm.MD5,
                StandardCharsets.ISO_8859_1,
                "Mufasa",
                "testrealm@host.com",
                "Circle Of Life".toCharArray(),
                "GET",
                "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093",
                "00000001",
                "0a4f113b",
                DigestQop.AUTH
        );

        assertEquals("6629fae49393a05397450978507c4ef1", response);
    }

    @Test
    void calculatesSha256AndRendersEscapedAuthorizationWithoutPassword() throws Exception {
        String ha1 = hash("SHA-256", "alice:realm:secret");
        String ha2 = hash("SHA-256", "OPTIONS:sip:bob@example.com");
        String expected = hash("SHA-256", ha1 + ":nonce:00000001:cnonce:auth:" + ha2);
        String actual = DigestCalculator.response(
                DigestAlgorithm.SHA_256,
                StandardCharsets.UTF_8,
                "alice",
                "realm",
                "secret".toCharArray(),
                "OPTIONS",
                "sip:bob@example.com",
                "nonce",
                "00000001",
                "cnonce",
                DigestQop.AUTH
        );

        assertEquals(expected, actual);
        DigestAuthorization authorization = new DigestAuthorization(
                "ali\"ce",
                "realm",
                "nonce",
                "sip:bob@example.com",
                actual,
                DigestAlgorithm.SHA_256,
                DigestQop.AUTH,
                "00000001",
                "cnonce",
                Optional.of("opaque\\value")
        );

        assertTrue(authorization.wireValue().contains("username=\"ali\\\"ce\""));
        assertTrue(authorization.wireValue().contains("opaque=\"opaque\\\\value\""));
        assertFalse(authorization.toString().contains("secret"));
        assertFalse(authorization.toString().contains(actual));
    }

    private static String hash(String algorithm, String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }
}
