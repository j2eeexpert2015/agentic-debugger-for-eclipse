package org.learningfromexperience.agentdebug.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code investigation-isolation}: one debugging investigation never stops or changes another one's program.
 * This protects the user's own debug sessions and those of a second agent.
 *
 * @author Ayan Dutta
 */
@DisplayName("Investigation isolation: one investigation never touches another one's program")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class InvestigationIsolationTests extends EclipseIntegrationTest {

    @Override String group() { return "investigation-isolation"; }

    /**
     * Investigation A starts a program; investigation B uses the same running program (reusedSession) and finishes.
     * A's program keeps running, and B, now finished, can no longer restart it.
     */
    @Test
    @DisplayName("A borrowed program keeps running when the borrower finishes")
    void borrowedAppSurvivesOtherFinish() throws Exception {
        String launch = startJava("LongRunningApplication", investigation());
        String borrower = begin("NORMAL");
        boolean finished = false;
        try {
            Map<String, Object> reused = bridge.call("START_JAVA_APPLICATION", false,
                    JAVA_PROJECT, JAVA_PKG + "LongRunningApplication", borrower);
            require(Boolean.TRUE.equals(reused.get("reusedSession")) && launch.equals(str(reused.get("launchName"))),
                    "B did not use A's running program: " + reused);
            require("success".equals(bridge.call("FINISH_INVESTIGATION", false, borrower).get("status")), "B's finish failed");
            finished = true;
            require(alive(launch), "B's finish ended A's program");
            Map<String, Object> restart = bridge.call("RESTART_DEBUG", true, launch, borrower);
            require("error".equals(restart.get("status")), "A finished investigation restarted another one's program: " + restart);
            require(alive(launch), "The refused restart ended A's program");
            put("borrowedProgramKept", true);
            put("restartByFinishedRefused", true);
        } finally {
            if (!finished) bridge.call("FINISH_INVESTIGATION", true, borrower);
        }
    }

    /**
     * Two investigations each start their own Spring Boot application (Orders and Inventory, different ports, both
     * running together); finishing the Orders investigation ends only Orders, Inventory keeps running.
     */
    @Test
    @DisplayName("Finishing one application leaves the other running")
    void twoAppsStayIndependent() throws Exception {
        String ordersOwner = null, inventoryOwner = null;
        boolean ordersClean = false, inventoryClean = false;
        try {
            ordersOwner = begin("NORMAL");
            String orders = startProgram(BOOT_PROJECT, ORDERS_MAIN, ordersOwner);
            int ordersPort = waitReady(orders, Duration.ofSeconds(100));
            inventoryOwner = begin("NORMAL");
            String inventory = startProgram(BOOT_PROJECT, INVENTORY_MAIN, inventoryOwner);
            int inventoryPort = waitReady(inventory, Duration.ofSeconds(100));
            require(!orders.equals(inventory), "Both applications share one launch");
            require(ordersPort != inventoryPort, "Both applications report the same HTTP port");
            require(alive(orders) && alive(inventory), "Both applications must run together");
            ordersClean = finish(ordersOwner, evidence, "ordersCleanup");
            ordersOwner = null;
            require(ordersClean, "The Orders investigation did not clean up");
            require(alive(inventory), "Finishing the Orders investigation ended Inventory");
            put("ordersPort", ordersPort);
            put("inventoryPort", inventoryPort);
            put("inventoryKeptAfterOrdersFinish", true);
        } finally {
            if (ordersOwner != null) ordersClean = finish(ordersOwner, evidence, "ordersCleanup");
            if (inventoryOwner != null) inventoryClean = finish(inventoryOwner, evidence, "inventoryCleanup");
            put("cleanup", ordersClean && inventoryClean);
        }
    }
}
