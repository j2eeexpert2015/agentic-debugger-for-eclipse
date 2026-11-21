package org.learningfromexperience.debugfixtures;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderCalculatorTest {
    @Test
    void appliesTenPercentDiscount() {
        assertEquals(108.0, OrderCalculator.calculate(1057, 120.0, 10), 0.001);
    }
}
