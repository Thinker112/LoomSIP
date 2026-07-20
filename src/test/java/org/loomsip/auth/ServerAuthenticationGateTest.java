package org.loomsip.auth;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteServerState;
import org.loomsip.transport.TransportContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAuthenticationGateTest {

    @Test
    void authenticatesPrecomputedHa1AndRejectsReplay() throws Exception {
        GateFixture fixture = fixture(Duration.ofMinutes(1));
        SipRequest initial = request(null);

        ServerAuthenticationResult first = await(fixture.gate.authenticate(initial));
        assertEquals(ServerAuthenticationDisposition.CHALLENGED, first.disposition());
        SipResponse challenge = first.challenge().orElseThrow();
        assertEquals(401, challenge.statusCode());
        DigestChallenge parsed = new DigestChallengeParser().parse(
                challenge.headers().firstValue("WWW-Authenticate").orElseThrow()
        );
        assertEquals(DigestAlgorithm.MD5, parsed.algorithm());

        SipRequest authenticated = request(authorization(parsed, "00000001", "client-nonce"));
        assertEquals(ServerAuthenticationDisposition.AUTHENTICATED,
                await(fixture.gate.authenticate(authenticated)).disposition());
        assertEquals(ServerAuthenticationDisposition.CHALLENGED,
                await(fixture.gate.authenticate(authenticated)).disposition());
    }

    @Test
    void expiredNonceProducesStaleChallenge() throws Exception {
        GateFixture fixture = fixture(Duration.ofSeconds(2));
        SipResponse initialChallenge = await(fixture.gate.authenticate(request(null))).challenge().orElseThrow();
        DigestChallenge parsed = new DigestChallengeParser().parse(
                initialChallenge.headers().firstValue("WWW-Authenticate").orElseThrow()
        );
        fixture.clock.advance(Duration.ofSeconds(2));

        SipResponse stale = await(fixture.gate.authenticate(
                request(authorization(parsed, "00000001", "client-nonce"))
        )).challenge().orElseThrow();

        String header = stale.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertTrue(header.contains("stale=true"));
    }

    @Test
    void authenticatesSha256PrecomputedHa1() {
        GateFixture fixture = fixture(Duration.ofMinutes(1), DigestAlgorithm.SHA_256);
        DigestChallenge challenge = new DigestChallengeParser().parse(
                await(fixture.gate.authenticate(request(null))).challenge().orElseThrow()
                        .headers().firstValue("WWW-Authenticate").orElseThrow()
        );

        assertEquals(DigestAlgorithm.SHA_256, challenge.algorithm());
        assertEquals(ServerAuthenticationDisposition.AUTHENTICATED,
                await(fixture.gate.authenticate(
                        request(authorization(challenge, "00000001", "client-nonce"))
                )).disposition());
    }

    @Test
    void inviteListenerChallengesBeforeDownstreamDialogOrTuListener() {
        GateFixture fixture = fixture(Duration.ofMinutes(1));
        AtomicInteger downstreamInvites = new AtomicInteger();
        List<SipResponse> responses = new ArrayList<>();
        InviteServerListener gated = fixture.gate.inviteListener(new InviteServerListener() {
            @Override
            public void onInvite(InviteServerHandle transaction, SipRequest request, TransportContext context) {
                downstreamInvites.incrementAndGet();
            }
        }, Runnable::run);
        InviteServerHandle transaction = new RecordingInviteHandle(responses);

        gated.onInvite(transaction, request(null), null);

        assertEquals(0, downstreamInvites.get());
        assertEquals(1, responses.size());
        assertEquals(401, responses.getFirst().statusCode());
    }

    @Test
    void wrongResponseUsesGenericChallengeWithoutCredentialDetails() {
        GateFixture fixture = fixture(Duration.ofMinutes(1));
        DigestChallenge parsed = new DigestChallengeParser().parse(
                await(fixture.gate.authenticate(request(null))).challenge().orElseThrow()
                        .headers().firstValue("WWW-Authenticate").orElseThrow()
        );
        SipRequest wrong = request(
                "Digest username=\"alice\", realm=\"office\", nonce=\"" + parsed.nonce()
                        + "\", uri=\"sip:bob@example.com\", response=\"00000000000000000000000000000000\", "
                        + "algorithm=MD5, qop=auth, nc=00000001, cnonce=\"client-nonce\""
        );

        SipResponse response = await(fixture.gate.authenticate(wrong)).challenge().orElseThrow();
        assertEquals(401, response.statusCode());
        assertFalse(response.reasonPhrase().toLowerCase(java.util.Locale.ROOT).contains("password"));
        assertFalse(response.reasonPhrase().toLowerCase(java.util.Locale.ROOT).contains("user"));
    }

    private static GateFixture fixture(Duration nonceLifetime) {
        return fixture(nonceLifetime, DigestAlgorithm.MD5);
    }

    private static GateFixture fixture(Duration nonceLifetime, DigestAlgorithm digestAlgorithm) {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        ServerAuthenticationPolicy policy = new ServerAuthenticationPolicy(
                "office",
                Set.of(digestAlgorithm),
                DigestCharset.ISO_8859_1,
                nonceLifetime,
                16,
                8,
                Optional.empty()
        );
        DigestNonceManager nonces = new DigestNonceManager(policy, new java.security.SecureRandom(), clock);
        String ha1 = hash(digestAlgorithm, "alice:office:secret");
        DigestCredentialRepository repository = (realm, username, algorithm) ->
                CompletableFuture.completedFuture(
                        realm.equals("office") && username.equals("alice") && algorithm == policy.preferredAlgorithm()
                                ? Optional.of(new DigestCredentialRecord("alice", "office", algorithm, ha1))
                                : Optional.empty()
                );
        return new GateFixture(
                new ServerAuthenticationGate(
                        policy,
                        repository,
                        nonces,
                        new DigestAuthorizationParser(),
                        new DigestVerifier(),
                        () -> "server-tag"
                ),
                clock
        );
    }

    private static SipRequest request(String authorization) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-auth")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "server-auth@example.com")
                .add("CSeq", "1 OPTIONS");
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:bob@example.com"), headers.build());
    }

    private static String authorization(DigestChallenge challenge, String nonceCount, String cnonce) {
        String ha1 = hash(challenge.algorithm(), "alice:office:secret");
        String response = DigestCalculator.responseFromHa1(
                challenge.algorithm(),
                StandardCharsets.ISO_8859_1,
                ha1,
                "OPTIONS",
                "sip:bob@example.com",
                challenge.nonce(),
                nonceCount,
                cnonce,
                DigestQop.AUTH
        );
        return "Digest username=\"alice\", realm=\"office\", nonce=\"" + challenge.nonce()
                + "\", uri=\"sip:bob@example.com\", response=\"" + response
                + "\", algorithm=" + challenge.algorithm().wireName()
                + ", qop=auth, nc=" + nonceCount + ", cnonce=\"" + cnonce + "\"";
    }

    private static String hash(String text) {
        return hash(DigestAlgorithm.MD5, text);
    }

    private static String hash(DigestAlgorithm algorithm, String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm.jcaName()).digest(text.getBytes(StandardCharsets.ISO_8859_1))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private record GateFixture(ServerAuthenticationGate gate, MutableClock clock) {
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class RecordingInviteHandle implements InviteServerHandle {

        private final List<SipResponse> responses;

        private RecordingInviteHandle(List<SipResponse> responses) {
            this.responses = responses;
        }

        @Override
        public TransactionKey key() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InviteServerState state() {
            return InviteServerState.PROCEEDING;
        }

        @Override
        public void sendResponse(SipResponse response) {
            responses.add(response);
        }

        @Override
        public CompletionStage<Void> terminated() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
