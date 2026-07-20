package org.loomsip.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WriteQueueLimitsTest {

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new WriteQueueLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new WriteQueueLimits(1, 0));
    }
}
