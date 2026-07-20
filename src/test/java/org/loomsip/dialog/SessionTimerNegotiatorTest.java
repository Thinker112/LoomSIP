package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.header.SessionExpiresHeaderValue;
import org.loomsip.message.header.SessionRefresher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionTimerNegotiatorTest {

    @Test
    void normalizesDefaultRefresherAndSelectsRefreshMethod() {
        SessionTimerNegotiator.NegotiatedSessionTimer negotiated = new SessionTimerNegotiator().negotiate(
                new SessionExpiresHeaderValue(1800, Optional.empty()),
                SessionTimerPolicy.DEFAULT
        );

        assertEquals(SessionRefresher.UAC, negotiated.refresher());
        assertEquals(SessionRefreshMethod.UPDATE, negotiated.refreshMethod());
    }

    @Test
    void reportsMinSeForTooSmallInterval() {
        SessionIntervalTooSmallException exception = assertThrows(
                SessionIntervalTooSmallException.class,
                () -> new SessionTimerNegotiator().negotiate(
                        new SessionExpiresHeaderValue(90, Optional.of(SessionRefresher.UAS)),
                        new SessionTimerPolicy(120, 1800, SessionRefreshMethod.UPDATE)
                )
        );
        assertEquals(120, exception.minimumSeconds());
    }
}
