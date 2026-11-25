package org.learningfromexperience.agentdebug.e2e;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * The extended suite (9 tests): abandoned-session cleanup (5 s leases) and the agent's connection to Eclipse under
 * load and faults (needs the test hook of a test Eclipse started by DevE2E). Run for changes to cleanup or the bridge.
 *
 * @author Ayan Dutta
 */
@Suite
@SuiteDisplayName("Extended")
@SelectClasses({AbandonedSessionCleanupTests.class, AgentEclipseConnectionTests.class})
public class ExtendedSuite { }
