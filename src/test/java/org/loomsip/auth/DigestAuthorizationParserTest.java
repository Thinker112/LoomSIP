package org.loomsip.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestAuthorizationParserTest {

    private final DigestAuthorizationParser parser = new DigestAuthorizationParser();

    @Test
    void parsesSupportedQopAuthAuthorization() {
        DigestAuthorizationRequest authorization = parser.parse(
                "Digest username=\"alice\", realm=\"office\", nonce=\"n\", uri=\"sip:bob@example.com\", "
                        + "response=\"0123456789abcdef0123456789abcdef\", algorithm=MD5, qop=auth, "
                        + "nc=00000001, cnonce=\"c\", opaque=\"o\\\"p\""
        );

        assertEquals("alice", authorization.username());
        assertEquals(DigestAlgorithm.MD5, authorization.algorithm());
        assertEquals(DigestQop.AUTH, authorization.qop());
        assertEquals("00000001", authorization.nonceCount());
        assertEquals("o\"p", authorization.opaque().orElseThrow());
        assertTrue(authorization.toString().contains("alice"));
    }

    @Test
    void rejectsUnsupportedQopAndIncompleteQopFields() {
        assertThrows(DigestUnsupportedChallengeException.class, () -> parser.parse(
                "Digest username=\"alice\", realm=\"office\", nonce=\"n\", uri=\"sip:bob\", "
                        + "response=\"0123456789abcdef0123456789abcdef\", qop=auth-int, nc=00000001, cnonce=\"c\""
        ));
        assertThrows(DigestChallengeParseException.class, () -> parser.parse(
                "Digest username=\"alice\", realm=\"office\", nonce=\"n\", uri=\"sip:bob\", "
                        + "response=\"0123456789abcdef0123456789abcdef\", qop=auth, nc=00000001"
        ));
    }
}
