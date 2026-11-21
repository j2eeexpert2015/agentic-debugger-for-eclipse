package org.learningfromexperience.bootfixture.orders;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class SkippedOrderTest {
    @Test
    @Disabled("P2-05 fixture deliberately selects a disabled method")
    void selectedButDisabled() {
        fail("Disabled test body unexpectedly executed");
    }
}
