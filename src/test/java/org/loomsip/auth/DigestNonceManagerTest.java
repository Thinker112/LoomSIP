package org.loomsip.auth;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestNonceManagerTest {

    @Test
    void atomicallyRejectsRepeatedOrDecreasingNonceCounts() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        ServerAuthenticationPolicy policy = policy(Duration.ofMinutes(1));
        DigestNonceManager manager = new DigestNonceManager(policy, new SecureRandom(), clock);
        DigestNonce nonce = manager.issue(DigestAlgorithm.MD5);

        assertEquals(DigestNonceStatus.VALID,
                manager.validate(nonce.value(), "office", DigestAlgorithm.MD5).status());
        assertEquals(DigestNonceStatus.VALID, manager.consumeNonceCount(
                nonce.value(), "office", DigestAlgorithm.MD5, "alice", "c", 1
        ));
        assertEquals(DigestNonceStatus.REPLAYED, manager.consumeNonceCount(
                nonce.value(), "office", DigestAlgorithm.MD5, "alice", "c", 1
        ));
        assertThrows(IllegalArgumentException.class, () -> manager.consumeNonceCount(
                nonce.value(), "office", DigestAlgorithm.MD5, "alice", "c", 0
        ));
        assertEquals(DigestNonceStatus.VALID, manager.consumeNonceCount(
                nonce.value(), "office", DigestAlgorithm.MD5, "alice", "c", 2
        ));
    }

    @Test
    void marksExpiredNonceStaleAndClearsStateOnClose() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        DigestNonceManager manager = new DigestNonceManager(
                policy(Duration.ofSeconds(5)),
                new SecureRandom(),
                clock
        );
        DigestNonce nonce = manager.issue(DigestAlgorithm.MD5);
        clock.advance(Duration.ofSeconds(5));

        assertEquals(DigestNonceStatus.STALE,
                manager.validate(nonce.value(), "office", DigestAlgorithm.MD5).status());
        assertEquals(0, manager.activeNonceCount());
        manager.close();
        assertThrows(IllegalStateException.class, manager::issue);
    }

    private static ServerAuthenticationPolicy policy(Duration lifetime) {
        return new ServerAuthenticationPolicy(
                "office",
                Set.of(DigestAlgorithm.MD5),
                DigestCharset.ISO_8859_1,
                lifetime,
                8,
                4,
                Optional.empty()
        );
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
}
