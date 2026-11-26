package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for what GitHub Copilot is told about our MCP server ({@link CopilotRegistration}, OPEN-23).
 * No Eclipse and no Copilot needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CopilotRegistrationTest {

    @Test
    @DisplayName("CP-U1 the JSON names one stdio server, eclipse-debugger, run as java -jar <jar>; Windows paths escaped")
    void cpU1() {
        String json = CopilotRegistration.json(Path.of("C:\\Program Files\\eclipse\\jre\\bin\\java.exe"),
                Path.of("C:\\Users\\a b\\.eclipse-agentdebug\\agentic-debugger-mcp.jar"), null);
        assertEquals("{\"servers\":{\"eclipse-debugger\":{\"type\":\"stdio\","
                + "\"command\":\"C:\\\\Program Files\\\\eclipse\\\\jre\\\\bin\\\\java.exe\","
                + "\"args\":[\"-jar\",\"C:\\\\Users\\\\a b\\\\.eclipse-agentdebug\\\\agentic-debugger-mcp.jar\"]}}}",
                json);
    }

    @Test
    @DisplayName("CP-U2 the bridge port goes into env only when it is not the default 8765")
    void cpU2() {
        Path java = Path.of("/opt/java/bin/java");
        Path jar = Path.of("/home/u/.eclipse-agentdebug/agentic-debugger-mcp.jar");
        assertFalse(CopilotRegistration.json(java, jar, "8765").contains("env"), "default port: no env");
        assertFalse(CopilotRegistration.json(java, jar, " ").contains("env"), "blank: no env");
        assertTrue(CopilotRegistration.json(java, jar, "21766").endsWith(
                ",\"env\":{\"AGENTDEBUG_PORT\":\"21766\"}}}}"), "another port: env with the port");
    }

    @Test
    @DisplayName("CP-U3 the jar folder is ~/.eclipse-agentdebug for the default port and a folder of its own otherwise")
    void cpU3() {
        Path home = Path.of("/home/u");
        assertEquals(home.resolve(".eclipse-agentdebug"), CopilotRegistration.runtimeFolder(home, null));
        assertEquals(home.resolve(".eclipse-agentdebug"), CopilotRegistration.runtimeFolder(home, "8765"));
        assertEquals(home.resolve(".eclipse-agentdebug").resolve("port-21766"),
                CopilotRegistration.runtimeFolder(home, "21766"));
        assertEquals(home.resolve(".eclipse-agentdebug").resolve("port-21766"),
                CopilotRegistration.runtimeFolder(home, "21766/../x"), "only digits are kept");
    }
}
