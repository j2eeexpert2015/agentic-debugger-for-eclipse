package org.learningfromexperience.eclipse.agentdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the MCP server's socket read timeouts (TO-U1 to TO-U3). Each timeout must
 * be longer than Eclipse's deadline for the same command, so Eclipse's BRIDGE_TIMEOUT reply always
 * arrives before the client gives up (CORE-13, CORE-14). The command lists themselves are kept in
 * sync by BR-U8 in the plugin. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ReadTimeoutTest {

    @Test @DisplayName("TO-U1 ordinary commands wait 30 s (Eclipse answers within 15-25 s)")
    void toU1() {
        assertEquals(30_000, DebugMcpServer.readTimeoutMillis("GET_THREADS abc"));
        assertEquals(30_000, DebugMcpServer.readTimeoutMillis("SET_BREAKPOINT abc 12"));
        assertEquals(30_000, DebugMcpServer.readTimeoutMillis("LIST_BREAKPOINTS"));
    }

    @Test @DisplayName("TO-U2 launches, restarts and finish wait 125 s (Eclipse deadline 120 s)")
    void toU2() {
        for (String command : new String[]{"START_JAVA_APPLICATION a b", "START_JUNIT_TEST a b c", "START_DEBUG a",
                "RESTART_DEBUG a b", "FINISH_INVESTIGATION a"})
            assertEquals(125_000, DebugMcpServer.readTimeoutMillis(command), command);
    }

    @Test @DisplayName("TO-U3 WAIT_FOR_STOP waits 75 s: longer than the 60 s maximum wait and Eclipse's 70 s deadline")
    void toU3() {
        int timeout = DebugMcpServer.readTimeoutMillis("WAIT_FOR_STOP abc 60000");
        assertEquals(75_000, timeout);
        assertTrue(timeout > 70_000);
    }
}
