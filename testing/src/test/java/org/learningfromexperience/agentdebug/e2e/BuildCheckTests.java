package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code build-check} (extended suite, OPEN-34): a program or test whose project cannot run as written is refused
 * before anything starts, with a message the agent can act on, instead of a JVM that ends with
 * {@code ClassNotFoundException} or Eclipse's blocking "Errors exist in project" question. The tests break the build of
 * the test workspace's copy of {@code phase-one-debugging} (test commands {@code TEST_WRITE_SOURCE},
 * {@code TEST_DELETE_CLASS_FILE}; {@code examples/} in the repository is never touched) and repair it afterwards.
 *
 * @author Ayan Dutta
 */
@DisplayName("Build check: a project that cannot run as written is refused before the start")
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class BuildCheckTests extends EclipseIntegrationTest {
    private static final String BROKEN_FILE = "src/main/java/org/learningfromexperience/debugfixtures/BrokenOnPurpose.java";
    private static final String BROKEN_SOURCE = "package org.learningfromexperience.debugfixtures;\n\n"
            + "/** Added and removed by BuildCheckTests (OPEN-34): a compile error on purpose. */\n"
            + "class BrokenOnPurpose {\n    int value = \"not a number\";\n}\n";

    @Override String group() { return "build-check"; }

    @BeforeAll
    void repairLeftovers() throws Exception {
        Map<String, Object> probe = bridge.call("TEST_BUILD_PROJECT", true, JAVA_PROJECT);
        require("success".equals(probe.get("status")),
                "The test Eclipse has no TEST_BUILD_PROJECT command: deploy a current build. Reply: " + probe);
        bridge.call("TEST_DELETE_SOURCE", false, JAVA_PROJECT, BROKEN_FILE); // an interrupted earlier run
        require(num(bridge.call("TEST_BUILD_PROJECT", false, JAVA_PROJECT).get("errors")) == 0,
                "The fixture project has compile errors before the test");
    }

    @AfterAll
    void repairBuild() throws IOException {
        if (bridge == null) return;
        bridge.call("TEST_DELETE_SOURCE", true, JAVA_PROJECT, BROKEN_FILE);
        bridge.call("TEST_BUILD_PROJECT", true, JAVA_PROJECT);
    }

    /** A compile error anywhere in the project: the start is refused, naming the file and line; after the fix it works. */
    @Test
    @DisplayName("A project with compile errors is refused, naming the error")
    void compileErrorRefusesStart() throws Exception {
        int configurations = configurationCount();
        bridge.call("TEST_WRITE_SOURCE", false, JAVA_PROJECT, BROKEN_FILE, BROKEN_SOURCE);
        Map<String, Object> refused = bridge.call("START_JAVA_APPLICATION", true,
                JAVA_PROJECT, JAVA_PKG + "StepFilterApplication", investigation());
        put("refused", refused);
        String message = str(refused.get("message"));
        require("error".equals(refused.get("status")), "Started although the project does not compile: " + refused);
        require(message.contains("compile error") && message.contains("BrokenOnPurpose.java:5"),
                "The refusal names the error and where it is: " + message);
        require(configurationCount() == configurations, "The refusal created no launch configuration");
        // The user fixes it: the same start works.
        bridge.call("TEST_DELETE_SOURCE", false, JAVA_PROJECT, BROKEN_FILE);
        String launch = startJava("StepFilterApplication", investigation());
        put("startedAfterFix", launch);
    }

    /** No compiled class (cleaned outside Eclipse): refused after one build attempt; after a real build it works. */
    @Test
    @DisplayName("A program without a compiled class is refused, saying the project is not built")
    void missingClassRefusesStart() throws Exception {
        Map<String, Object> deleted = bridge.call("TEST_DELETE_CLASS_FILE", false, JAVA_PROJECT, JAVA_PKG + "NestedObjectApplication");
        put("deletedClass", deleted);
        require(Boolean.TRUE.equals(deleted.get("deleted")), "The compiled class existed before: " + deleted);
        Map<String, Object> refused = bridge.call("START_JAVA_APPLICATION", true,
                JAVA_PROJECT, JAVA_PKG + "NestedObjectApplication", investigation());
        put("refused", refused);
        require("error".equals(refused.get("status")), "Started without a compiled class: " + refused);
        require(str(refused.get("message")).contains("is not built"), "The refusal says the project is not built: " + refused);
        // The user builds the project: the same start works.
        bridge.call("TEST_BUILD_PROJECT", false, JAVA_PROJECT);
        String launch = startJava("NestedObjectApplication", investigation());
        put("startedAfterBuild", launch);
    }

    private int configurationCount() throws IOException {
        return arr(bridge.call("LIST_RUN_CONFIGURATIONS", false).get("configurations")).size();
    }
}
