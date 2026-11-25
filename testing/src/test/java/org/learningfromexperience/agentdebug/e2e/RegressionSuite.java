package org.learningfromexperience.agentdebug.e2e;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * The regression suite (21 tests): run after every product change. Needs the running test Eclipse and, for the HTTP
 * tests, {@code -Dfixture.post.approved=true} (without it they are reported as skipped).
 * In the IDE: right-click, Run As, JUnit Test. From the command line it is the default of the integration-tests profile.
 *
 * @author Ayan Dutta
 */
@Suite
@SuiteDisplayName("Regression")
@SelectClasses({ProgramStartTests.class, BreakpointTests.class, ExecutionControlTests.class, StopsAndVariablesTests.class,
        SpringBootHttpTests.class, SpringBootStartupTests.class, InvestigationIsolationTests.class})
public class RegressionSuite { }
