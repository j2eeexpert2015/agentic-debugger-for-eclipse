package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for {@link JUnitFailure}: what the agent gets from a failed test's trace (OPEN-25, JF-U1 to
 * JF-U4). No Eclipse needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class JUnitFailureTest {

    /** The failure of the fixture's OrderServiceTest.calculatesDiscount (AssertJ on BigDecimal), as JUnit 5 reports it. */
    static final String ASSERTJ_TRACE = """
            org.opentest4j.AssertionFailedError:\s
            expected: 108.00
             but was: 120.00
            when comparing values using 'BigDecimalComparator'
            \tat org.assertj.core.api.AbstractBigDecimalAssert.isEqualByComparingTo(AbstractBigDecimalAssert.java:640)
            \tat org.learningfromexperience.bootfixture.orders.OrderServiceTest.calculatesDiscount(OrderServiceTest.java:19)
            \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
            \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:767)
            """;

    static final String JUNIT_TRACE = """
            org.opentest4j.AssertionFailedError: expected: <108.0> but was: <120.0>
            \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
            \tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:686)
            \tat org.learningfromexperience.debugfixtures.OrderCalculatorTest.appliesTenPercentDiscount(OrderCalculatorTest.java:9)
            """;

    static final String EXCEPTION_TRACE = """
            java.lang.IllegalArgumentException: Quantity must be positive
            \tat org.learningfromexperience.bootfixture.orders.OrderService.quote(OrderService.java:17)
            \tat org.learningfromexperience.bootfixture.orders.OrderServiceTest.rejectsZero(OrderServiceTest.java:24)
            \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
            """;

    @Test @DisplayName("JF-U1 the failure location is the first frame outside the test framework and the JDK")
    void jfU1() {
        assertEquals("OrderServiceTest.calculatesDiscount:19", JUnitFailure.location(ASSERTJ_TRACE).label(),
                "an AssertJ failure: the test line, not AssertJ's");
        assertEquals("org.learningfromexperience.bootfixture.orders.OrderServiceTest",
                JUnitFailure.location(ASSERTJ_TRACE).className());
        assertEquals("OrderCalculatorTest.appliesTenPercentDiscount:9", JUnitFailure.location(JUNIT_TRACE).label());
        assertEquals("OrderService.quote:17", JUnitFailure.location(EXCEPTION_TRACE).label(),
                "an exception: the application line that threw it");
        assertNull(JUnitFailure.location("java.lang.AssertionError\n\tat org.junit.Assert.fail(Assert.java:89)"));
        assertNull(JUnitFailure.location(null));
    }

    @Test @DisplayName("JF-U2 expected and actual: Eclipse's values first, else read from a JUnit or AssertJ message")
    void jfU2() {
        assertArrayEquals(new String[] {"108.00", "120.00"}, JUnitFailure.expectedActual(ASSERTJ_TRACE, null, null));
        assertArrayEquals(new String[] {"108.0", "120.0"}, JUnitFailure.expectedActual(JUNIT_TRACE, "", null));
        assertArrayEquals(new String[] {"108.00", "120.00"},
                JUnitFailure.expectedActual(JUNIT_TRACE, "108.00", "120.00"), "Eclipse's values win");
        assertArrayEquals(new String[] {null, null}, JUnitFailure.expectedActual(EXCEPTION_TRACE, null, null));
    }

    @Test @DisplayName("JF-U3 the message is the trace's header on one line, shortened")
    void jfU3() {
        assertEquals("org.opentest4j.AssertionFailedError: expected: 108.00 but was: 120.00 when comparing values using"
                + " 'BigDecimalComparator'", JUnitFailure.message(ASSERTJ_TRACE));
        assertEquals("java.lang.IllegalArgumentException: Quantity must be positive", JUnitFailure.message(EXCEPTION_TRACE));
        String longMessage = JUnitFailure.message("java.lang.AssertionError: " + "x".repeat(900));
        assertEquals(JUnitFailure.MAX_MESSAGE, longMessage.length());
        assertTrue(longMessage.endsWith("…"));
    }

    @Test @DisplayName("JF-U4 the short trace keeps the header and the first frames and counts the rest")
    void jfU4() {
        String shortTrace = JUnitFailure.shortTrace(ASSERTJ_TRACE, 2);
        assertTrue(shortTrace.startsWith("org.opentest4j.AssertionFailedError:"), shortTrace);
        assertTrue(shortTrace.contains("OrderServiceTest.calculatesDiscount(OrderServiceTest.java:19)"), shortTrace);
        assertTrue(!shortTrace.contains("ReflectionUtils") && shortTrace.endsWith("... 2 more frames"), shortTrace);
        assertEquals("", JUnitFailure.shortTrace(null, 8));
    }
}
