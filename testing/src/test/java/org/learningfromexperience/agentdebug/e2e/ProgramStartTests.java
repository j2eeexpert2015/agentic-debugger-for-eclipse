package org.learningfromexperience.agentdebug.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code program-start}: finding and starting Java programs and JUnit tests, and refusing unknown ones
 * without starting anything.
 *
 * @author Ayan Dutta
 */
@DisplayName("Program start: finding and starting Java programs and JUnit tests")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class ProgramStartTests extends EclipseIntegrationTest {

    @Override String group() { return "program-start"; }

    /** Both Spring Boot mains of the fixture project are listed exactly once; listing changes no session. */
    @Test
    @DisplayName("Finds both Spring Boot main classes, once each, without changing any session")
    void findsMainClasses() throws Exception {
        Object before = sessions();
        List<Map<String, Object>> apps = rows(bridge.call("LIST_JAVA_APPLICATIONS", false).get("applications"));
        long orders = apps.stream().filter(a -> BOOT_PROJECT.equals(a.get("project")) && ORDERS_MAIN.equals(a.get("mainType"))).count();
        long inventory = apps.stream().filter(a -> BOOT_PROJECT.equals(a.get("project")) && INVENTORY_MAIN.equals(a.get("mainType"))).count();
        require(orders == 1 && inventory == 1, "Both Boot mains listed once (orders=" + orders + ", inventory=" + inventory + ")");
        require(before.equals(sessions()), "Listing programs changed the debug sessions");
        put("programsListed", apps.size());
    }

    /** An unknown project and main class are refused, and no session is started. */
    @Test
    @DisplayName("Refuses an unknown program and starts nothing")
    void rejectsUnknownProgram() throws Exception {
        Object before = sessions();
        Map<String, Object> reply = bridge.call("START_JAVA_APPLICATION", true,
                "agentdebug-nonexistent-project", "agentdebug.Nonexistent", "agentdebug-invalid-investigation");
        require("error".equals(reply.get("status")), "Unknown program unexpectedly started: " + reply);
        require(before.equals(sessions()), "A refused start changed the debug sessions");
        put("message", reply.get("message"));
    }

    /** A JUnit test method is found, started alone and stops at a breakpoint in the code it tests. */
    @Test
    @DisplayName("Runs one JUnit test method alone and stops in the code it tests")
    void runsOneJunitMethod() throws Exception {
        String type = JAVA_PKG + "OrderCalculatorTest";
        require(rows(bridge.call("LIST_JUNIT_TESTS", false).get("tests")).stream()
                .anyMatch(t -> type.equals(t.get("typeName")) && "appliesTenPercentDiscount".equals(t.get("methodName"))),
                "JUnit test OrderCalculatorTest.appliesTenPercentDiscount not listed");
        String investigation = investigation();
        bridge.call("SET_BREAKPOINT", false, JAVA_PKG + "OrderCalculator",
                javaLine("OrderCalculator", "double discountRate ="), investigation);
        String launch = str(bridge.call("START_JUNIT_TEST", false, JAVA_PROJECT, type, "appliesTenPercentDiscount",
                investigation).get("launchName"));
        Frame stop = awaitStop(launch, JAVA_PKG + "OrderCalculator", null, -1, Duration.ofSeconds(30), null, null);
        put("stoppedIn", stop.method() + ":" + stop.line());
    }

    /** A JUnit method that does not exist is refused, and no session is started. */
    @Test
    @DisplayName("Refuses a JUnit method that does not exist and starts nothing")
    void rejectsUnknownJunitMethod() throws Exception {
        Object before = sessions();
        Map<String, Object> reply = bridge.call("START_JUNIT_TEST", true, BOOT_PROJECT, ORDERS_PKG + "OrderServiceTest",
                "agentdebugMethodThatDoesNotExist", investigation());
        require("error".equals(reply.get("status")), "Missing JUnit method unexpectedly launched: " + reply);
        require(before.equals(sessions()), "A refused JUnit start changed the debug sessions");
        put("message", reply.get("message"));
    }
}
