package org.learningfromexperience.agentdebug.e2e;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Regression and extended suite together (38 tests). The Claude end-to-end tests are never part of a suite.
 *
 * @author Ayan Dutta
 */
@Suite
@SuiteDisplayName("Full (regression and extended)")
@SelectClasses({ProgramStartTests.class, BreakpointTests.class, ExecutionControlTests.class, StopsAndVariablesTests.class,
        SpringBootHttpTests.class, SpringBootStartupTests.class, InvestigationIsolationTests.class,
        AbandonedSessionCleanupTests.class, AgentEclipseConnectionTests.class, UserResourceSafetyTests.class})
public class FullSuite { }
