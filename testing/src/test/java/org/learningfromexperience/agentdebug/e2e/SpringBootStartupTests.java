package org.learningfromexperience.agentdebug.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code spring-boot-startup}: a Spring Boot application that fails to start is seen failing, and its JVM is
 * confirmed gone by its operating-system process ID (not only by Eclipse's debug state, which can lag).
 *
 * @author Ayan Dutta
 */
@DisplayName("Spring Boot startup: an application that fails to start")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class SpringBootStartupTests extends EclipseIntegrationTest {
    private static final Pattern FIXTURE_PID = Pattern.compile("AGENTDEBUG_FIXTURE_PID=(\\d+)");

    @Override String group() { return "spring-boot-startup"; }

    @Test
    @DisplayName("A failure during startup is detected and the JVM is gone")
    void startupFailureDetected() throws Exception {
        failsToStart("StartupFailureApplication", "AGENTDEBUG_EXPECTED_STARTUP_FAILURE");
    }

    @Test
    @DisplayName("A port that is already in use is detected and the JVM is gone")
    void portConflictDetected() throws Exception {
        failsToStart("PortConflictApplication", "AGENTDEBUG_EXPECTED_PORT_CONFLICT");
    }

    /**
     * Starts a fixture that must fail, waits up to 45 s for its failure marker and for its JVM process to exit. If
     * Eclipse suspends a thread after the failure (an exception breakpoint of the IDE), the test resumes only this
     * investigation's JVM, at most 3 times. An unexpected successful start fails the scenario.
     */
    private void failsToStart(String main, String marker) throws Exception {
        String investigation = investigation();
        String launch = startProgram(BOOT_PROJECT, FAILURES_PKG + main, investigation);
        put("launchName", launch);
        boolean markerSeen = false, unexpectedSuccess = false, exited = false, seenAlive = false;
        long pid = -1;
        int resumes = 0;
        String lastState = "not-queried", sample = "", lastSuspension = "none";
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String text = processOutput(launch);
            markerSeen |= text.contains(marker);
            unexpectedSuccess |= text.contains("AGENTDEBUG_UNEXPECTED_") || text.contains("Tomcat started on port");
            if (!text.isBlank()) sample = text.substring(Math.max(0, text.length() - 1200));
            if (pid <= 0) {
                Matcher match = FIXTURE_PID.matcher(text);
                if (match.find()) pid = Long.parseLong(match.group(1));
            }
            Map<String, Object> state = bridge.call("GET_DEBUG_STATE", true, launch);
            lastState = str(state.get("status")) + " terminated=" + str(state.get("terminated"))
                    + " suspended=" + str(state.get("suspended")) + " disconnected=" + str(state.get("disconnected"));
            boolean debugTerminated = Boolean.TRUE.equals(state.get("terminated"));
            if (pid > 0) {
                var handle = ProcessHandle.of(pid);
                seenAlive |= handle.isPresent() && handle.get().isAlive();
                exited = handle.isEmpty() || !handle.get().isAlive();
            }
            if (markerSeen && !exited && Boolean.TRUE.equals(state.get("suspended")) && resumes < 3) {
                for (Map<String, Object> thread : rows(bridge.call("GET_THREADS", true, launch).get("threads"))) {
                    if (!Boolean.TRUE.equals(thread.get("suspended"))) continue;
                    int index = num(thread.get("index"));
                    List<Map<String, Object>> frames = rows(bridge.call("GET_STACK", true, launch, index).get("frames"));
                    lastSuspension = "thread=" + str(thread.get("name")) + ", top=" + (frames.isEmpty() ? "unavailable"
                            : str(frames.get(0).get("className")) + "." + str(frames.get(0).get("methodName"))
                            + ":" + str(frames.get(0).get("lineNumber")));
                    Map<String, Object> answer = bridge.call("CONTROL_DEBUG", true, launch, index, "RESUME", investigation);
                    if ("success".equals(answer.get("status"))) resumes++;
                    else put("resumeError", answer);
                    break; // one thread per round, bounded by the resume count
                }
            }
            if (unexpectedSuccess || (markerSeen && exited && pid > 0)) break;
            // A JDI event alone is no proof that the OS process ended; keep watching while the PID is unknown.
            if (debugTerminated && pid <= 0 && markerSeen) break;
            Thread.sleep(200);
        }
        put("markerSeen", markerSeen);
        put("fixturePid", pid > 0 ? pid : null);
        put("osProcessExited", exited && pid > 0);
        put("osProcessSeenAlive", seenAlive);
        put("resumes", resumes);
        put("lastDebugState", lastState);
        put("lastSuspension", lastSuspension);
        require(!unexpectedSuccess, main + " started successfully although it must fail");
        require(markerSeen, "Failure marker " + marker + " never seen (state=" + lastState + "; output=" + sample + ")");
        require(pid > 0, "The fixture's process ID is unknown; termination is not assumed (state=" + lastState + "; output=" + sample + ")");
        require(exited, "The failure was seen but the JVM (PID " + pid + ") is still running (state=" + lastState
                + "; suspension=" + lastSuspension + "; output=" + sample + ")");
    }
}
