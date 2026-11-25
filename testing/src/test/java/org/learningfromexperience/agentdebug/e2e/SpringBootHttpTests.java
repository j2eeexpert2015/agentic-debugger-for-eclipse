package org.learningfromexperience.agentdebug.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Group {@code spring-boot-http}: debugging a real HTTP request to the Orders Spring Boot fixture. The request is
 * sent by the test HTTP client ({@link JavaFixtureQuoteClient}); the tests run only with {@code -Dfixture.post.approved=true}.
 *
 * @author Ayan Dutta
 */
@DisplayName("Spring Boot HTTP: debugging a real HTTP request")
@EnabledIfSystemProperty(named = "fixture.post.approved", matches = "true",
        disabledReason = "Sends HTTP requests to the local test application: add -Dfixture.post.approved=true")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class SpringBootHttpTests extends EclipseIntegrationTest {
    private static final String SERVICE = ORDERS_PKG + "OrderService";
    private static final String CONTROLLER = ORDERS_PKG + "OrderController";

    @Override String group() { return "spring-boot-http"; }

    /**
     * POST /orders/quote (2 × 60 with 10 % discount) stops in OrderService.quote with percent=10 and rate=0, the
     * first wrong value; after resuming, the response is HTTP 200 with the wrong total 120.00. The test HTTP client
     * also refuses to send anything without approval.
     */
    @Test
    @DisplayName("Wrong quote: stops at the first wrong value, response 120.00")
    void wrongQuoteValueFound() throws Exception {
        String investigation = investigation();
        int line = bootLine(SERVICE, "BigDecimal subtotal =");
        bridge.call("SET_BREAKPOINT", false, SERVICE, line, investigation);
        String launch = startProgram(BOOT_PROJECT, ORDERS_MAIN, investigation);
        Path folder = Files.createTempDirectory("agentdebug-quote-");
        Path receipt = folder.resolve("result.json");
        Path log = folder.resolve("client.log");
        Process client = startFixtureClient(launch, receipt, true, false, log);
        try {
            Frame stop = awaitStop(launch, SERVICE, null, line, Duration.ofSeconds(155), client, log);
            requireLocal(launch, stop.thread(), "percent", "10");
            requireLocal(launch, stop.thread(), "rate", "0");
            put("firstWrongValue", "percent=10, rate=0");
            bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "RESUME", investigation);
            require(client.waitFor(45, TimeUnit.SECONDS), "HTTP client timed out: " + clientLog(log));
            require(client.exitValue() == 0, "HTTP client failed: " + clientLog(log));
            Map<String, Object> result = obj(Json.parse(Files.readString(receipt)));
            require(num(result.get("status")) == 200, "Expected HTTP 200: " + result);
            require(str(result.get("body")).contains("120.00"), "Expected the wrong total 120.00: " + result);
            put("httpStatus", result.get("status"));
            put("httpBody", result.get("body"));
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            put("httpClientLog", log.toString());
            Files.deleteIfExists(receipt);
        }
        // The test client refuses a request without approval, before any bridge or network call.
        Path refused = folder.resolve("must-not-exist.json");
        Process denied = startFixtureClient(launch, refused, false, false, folder.resolve("refused-client.log"));
        require(denied.waitFor(10, TimeUnit.SECONDS), "The unapproved client did not exit");
        require(denied.exitValue() != 0 && !Files.exists(refused), "An HTTP request without approval was sent");
        put("unapprovedRequestRefused", true);
    }

    /**
     * POST /orders/quote with quantity 0 stops where OrderService throws (quantity=0) and then in the controller's
     * error handler; after resuming both, the response is HTTP 400 "Quantity must be positive" from the URL of this launch.
     */
    @Test
    @DisplayName("Invalid quantity: stops at the throw and the handler, response 400")
    void validationErrorReturns400() throws Exception {
        String investigation = investigation();
        int throwLine = bootLine(SERVICE, "throw new IllegalArgumentException(\"Quantity must be positive\")");
        int handlerLine = bootLine(CONTROLLER, "return new ErrorResponse(error.getMessage());");
        bridge.call("SET_BREAKPOINT", false, SERVICE, throwLine, investigation);
        bridge.call("SET_BREAKPOINT", false, CONTROLLER, handlerLine, investigation);
        String launch = startProgram(BOOT_PROJECT, ORDERS_MAIN, investigation);
        int serverPort = waitReady(launch, Duration.ofSeconds(75));
        put("serverPort", serverPort);
        Path folder = Files.createTempDirectory("agentdebug-validation-");
        Path receipt = folder.resolve("invalid-quantity.json");
        Path log = folder.resolve("client.log");
        Process client = startFixtureClient(launch, receipt, true, true, log);
        try {
            Frame atThrow = awaitStop(launch, SERVICE, null, throwLine, Duration.ofSeconds(45), client, log);
            requireLocal(launch, atThrow.thread(), "quantity", "0");
            bridge.call("CONTROL_DEBUG", false, launch, atThrow.thread(), "RESUME", investigation);
            Frame atHandler = awaitStop(launch, CONTROLLER, null, handlerLine, Duration.ofSeconds(45), client, log);
            bridge.call("CONTROL_DEBUG", false, launch, atHandler.thread(), "RESUME", investigation);
            require(client.waitFor(35, TimeUnit.SECONDS), "HTTP client timed out: " + clientLog(log));
            require(client.exitValue() == 0, "HTTP client failed: " + clientLog(log));
            Map<String, Object> result = obj(Json.parse(Files.readString(receipt)));
            require(num(result.get("status")) == 400, "Expected HTTP 400: " + result);
            require(str(result.get("body")).contains("Quantity must be positive"), "Wrong error response: " + result);
            require(("http://127.0.0.1:" + serverPort + "/orders/quote").equals(result.get("url")),
                    "The request did not go to this launch: " + result.get("url"));
            put("httpStatus", result.get("status"));
            put("httpBody", result.get("body"));
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            put("httpClientLog", log.toString());
            Files.deleteIfExists(receipt);
        }
    }
}
