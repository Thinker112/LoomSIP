package org.loomsip.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestChallengeParserTest {

    private final DigestChallengeParser parser = new DigestChallengeParser();

    @Test
    void parsesQuotedValuesEscapesAndSupportedOptions() {
        DigestChallenge challenge = parser.parse(
                "Digest realm=\"office\\\"edge\", nonce=\"nonce-1\", opaque=\"opaque\\\\value\", "
                        + "algorithm=SHA-256, qop=\"auth, auth-int\", stale=true, charset=UTF-8"
        );

        assertEquals("office\"edge", challenge.realm());
        assertEquals("nonce-1", challenge.nonce());
        assertEquals("opaque\\value", challenge.opaque().orElseThrow());
        assertEquals(DigestAlgorithm.SHA_256, challenge.algorithm());
        assertEquals(DigestCharset.UTF_8, challenge.charset());
        assertTrue(challenge.stale());
        assertTrue(challenge.supportsAuthQop());
        assertTrue(challenge.qopOptions().contains("auth-int"));
    }

    @Test
    void defaultsToMd5AndLeavesMissingQopUnsupportedForCoordinatorSelection() {
        DigestChallenge challenge = parser.parse("Digest realm=\"example\", nonce=\"n\"");

        assertEquals(DigestAlgorithm.MD5, challenge.algorithm());
        assertFalse(challenge.supportsAuthQop());
    }

    @Test
    void rejectsMalformedRequiredOrDuplicateParameters() {
        assertThrows(DigestChallengeParseException.class,
                () -> parser.parse("Digest nonce=\"n\""));
        assertThrows(DigestChallengeParseException.class,
                () -> parser.parse("Digest realm=\"r\", nonce=\"n\", realm=\"again\""));
        assertThrows(DigestUnsupportedChallengeException.class,
                () -> parser.parse("Digest realm=\"r\", nonce=\"n\", algorithm=SHA-512-256"));
    }
}
