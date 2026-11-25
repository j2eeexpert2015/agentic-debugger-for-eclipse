package org.learningfromexperience.agentdebug.e2e;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * The extended suite (22 tests): abandoned-session cleanup (5 s leases), the agent's connection to Eclipse under
 * load and faults (needs the test hook of a test Eclipse started by DevE2E), and the safety of the user's own
 * breakpoints and launches (OPEN-02/03), the build check before a start (OPEN-34), and JUnit results returned to the agent (OPEN-25). Run for changes to cleanup,
 * ownership, starting programs or the bridge.
 *
 * @author Ayan Dutta
 */
@Suite
@SuiteDisplayName("Extended")
@SelectClasses({AbandonedSessionCleanupTests.class, AgentEclipseConnectionTests.class, UserResourceSafetyTests.class, BuildCheckTests.class,
        JUnitResultsTests.class})
public class ExtendedSuite { }
