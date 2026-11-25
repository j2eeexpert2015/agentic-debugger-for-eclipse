package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;

/**
 * OPEN-25: the results of JUnit runs, as Eclipse's own JUnit integration reports them (the same results as the JUnit
 * view), kept per run so {@code GET_JUNIT_RESULTS} can return them to the agent.
 *
 * <p>Uses only the public JUnit API ({@code org.eclipse.jdt.junit}: {@link JUnitCore#addTestRunListener},
 * {@link TestRunListener}). A run is keyed by its test run name, which is the name of the launch configuration it was
 * started with, so the launch name the agent got from {@code start_junit_test} finds it. The listener is registered
 * once, before the first JUnit start of the agent; runs started before that are not known. The last {@link #KEEP}
 * runs are kept.
 *
 * <p>Loaded only when used: {@code org.eclipse.jdt.junit.core} is an optional dependency, and without it the callers
 * get a {@link LinkageError} and say so.
 *
 * @author Ayan Dutta
 */
final class JUnitResults {

    /** Runs kept, oldest dropped first. */
    static final int KEEP = 30;

    /** One finished test method. Trace, expected and actual are null for a passed test. */
    record TestCase(String className, String methodName, String result, double seconds, String trace,
            String expected, String actual) { }

    /** One test run (one launch). */
    static final class Run {
        final String name;
        final List<TestCase> tests = new CopyOnWriteArrayList<>();
        volatile boolean finished;
        volatile String result = "RUNNING";

        Run(String name) { this.name = name; }
    }

    private static final Map<String, Run> RUNS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) { return size() > KEEP; }
    });
    private static boolean listening;

    private JUnitResults() { }

    /** Registers the listener once. Throws a {@link LinkageError} when Eclipse's JUnit support is not installed. */
    static synchronized void listen() {
        if (listening) return;
        JUnitCore.addTestRunListener(new Listener());
        listening = true;
    }

    /** The run started from this launch configuration, or null. */
    static Run run(String launchName) {
        return RUNS.get(launchName);
    }

    static String resultName(ITestElement.Result result) {
        if (result == ITestElement.Result.OK) return "PASSED";
        if (result == ITestElement.Result.FAILURE) return "FAILED";
        if (result == ITestElement.Result.ERROR) return "ERROR";
        if (result == ITestElement.Result.IGNORED) return "SKIPPED";
        return "UNDEFINED";
    }

    private static final class Listener extends TestRunListener {
        @Override
        public void sessionStarted(ITestRunSession session) {
            String name = session.getTestRunName();
            if (name != null) RUNS.put(name, new Run(name));
        }

        @Override
        public void testCaseFinished(ITestCaseElement test) {
            try {
                Run run = RUNS.get(test.getTestRunSession().getTestRunName());
                if (run == null) return;
                ITestElement.FailureTrace failure = test.getFailureTrace();
                run.tests.add(new TestCase(test.getTestClassName(), test.getTestMethodName(),
                        resultName(test.getTestResult(false)), test.getElapsedTimeInSeconds(),
                        failure == null ? null : failure.getTrace(),
                        failure == null ? null : failure.getExpected(),
                        failure == null ? null : failure.getActual()));
            } catch (RuntimeException ignored) {
                // Never disturb Eclipse's JUnit view.
            }
        }

        @Override
        public void sessionFinished(ITestRunSession session) {
            try {
                Run run = RUNS.get(session.getTestRunName());
                if (run == null) return;
                run.result = resultName(session.getTestResult(true));
                run.finished = true;
            } catch (RuntimeException ignored) {
                // Never disturb Eclipse's JUnit view.
            }
        }
    }
}
