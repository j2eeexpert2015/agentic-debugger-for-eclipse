package org.learningfromexperience.agentdebug.report.chaintest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.learningfromexperience.agentdebug.report.DebugReportListener;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportSettings;

import com.aventstack.chaintest.domain.Build;
import com.aventstack.chaintest.domain.Result;
import com.aventstack.chaintest.domain.Tag;
import com.aventstack.chaintest.domain.Test;
import com.aventstack.chaintest.generator.ChainTestSimpleGenerator;

/**
 * Writes the debugging session report with ChainTest's static HTML generator (OPEN-30, ADR-009).
 *
 * <p><b>Mapping.</b> ChainTest's model is a test run; ours is a debugging session:
 * <ul>
 *   <li>one MCP server process (one agent session) = one ChainTest build = one report file;</li>
 *   <li>one investigation = one top-level test; its result is PASSED when it was finished and cleaned up, FAILED when
 *       cleanup reported a problem (JVM still running), SKIPPED when it ended without
 *       {@code finish_debug_investigation} (idle lease, disconnect). A failed tool call the agent recovered from does
 *       not fail the investigation: the step is red, the investigation says how many calls failed;</li>
 *   <li>one step (tool call) = one child test named {@code KIND - title}, tagged with its kind, with its details as
 *       log lines and its attachments (screenshots) as embedded images;</li>
 *   <li>the agent's conclusion = a {@code CONCLUSION} child, the cleanup result = a final {@code CLEANUP} child.</li>
 * </ul>
 * ChainTest's page shows logs and images only for child tests; for an investigation it shows name, tags, times
 * and description, so investigation-level notes (how it ended) go into the description. Tags give badges on each
 * card and the tag chart at the top: every step is tagged with its kind, the investigation only with its mode, client
 * and outcome (step tags are added directly, because ChainTest would otherwise copy them to the investigation).
 * The investigation is renamed after its first launch target; long IDs are shortened to 8 characters in the name.
 *
 * <p><b>Look.</b> Dark theme, title "Agentic Debugger - Debugging Session Report", header with the project name and
 * the session start time. ChainTest's own CSS and scripts are used unchanged.
 *
 * <p>Uses only ChainTest's core engine ({@link Build}, {@link Test}) and {@link ChainTestSimpleGenerator}, configured
 * in code (no {@code chaintest.properties}), offline (CSS and scripts copied next to the report). Not
 * {@code ChainTestListener}: that is a TestNG adapter whose {@code log}/{@code embed} only work during a TestNG test.
 * The report is rewritten after every finished investigation, so it exists even if the session is killed later.
 *
 * <p>Runs only on the report thread of {@code AsyncReportRecorder}; not thread-safe by design. ChainTest's templates
 * do not escape text, so everything is HTML-escaped here ({@link #html}).
 *
 * @author Ayan Dutta
 */
public final class ChainTestReportWriter implements DebugReportListener {

    static final String RUNNER = "agentic-debugger";
    static final String REPORT_FILE = "index.html";
    private static final String PREFIX = "chaintest.generator.simple.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    /** Format of the start and end times ChainTest shows on each investigation (java.text.SimpleDateFormat). */
    private static final String CARD_TIME_FORMAT = "dd MMM yyyy, HH:mm:ss";

    private final Path reportFile;
    private final Build build;
    private final ChainTestSimpleGenerator generator;
    private final Map<String, Test> open = new LinkedHashMap<>();
    private final Queue<Test> finished = new ConcurrentLinkedQueue<>();
    /** Investigations already named after their launch target. */
    private final java.util.Set<String> named = new java.util.HashSet<>();
    /** Failed tool calls per investigation. */
    private final Map<String, Integer> failedCalls = new java.util.HashMap<>();
    private long droppedEvents;

    public ChainTestReportWriter(ReportSettings settings) throws Exception {
        Files.createDirectories(settings.outputDirectory());
        reportFile = settings.outputDirectory().resolve(REPORT_FILE);
        build = new Build(settings.projectName(), RUNNER);
        generator = new ChainTestSimpleGenerator();
        // "js" and "css" must be present, even empty: ChainTestSimpleGenerator.flush() puts them into Map.of(...),
        // which rejects null values (NullPointerException in flush when they are missing).
        // Header next to ChainTest's logo: project, report kind and when the session started.
        String header = html(settings.projectName() + "  |  Debugging Session Report  |  "
                + TIME.format(java.time.Instant.now()));
        Map<String, String> config = Map.of(
                PREFIX + "enabled", "true",
                PREFIX + "output-file", reportFile.toString(),
                PREFIX + "offline", "true",
                PREFIX + "dark-theme", "true",
                PREFIX + "document-title", "Agentic Debugger - Debugging Session Report",
                PREFIX + "datetime-format", CARD_TIME_FORMAT,
                PREFIX + "js", "",
                PREFIX + "css", "",
                "chaintest.project.name", header);
        generator.start(Optional.of(config), RUNNER, build);
        if (!generator.started()) throw new IllegalStateException("ChainTest report generator did not start");
    }

    /** The report this writer produces. */
    public Path reportFile() {
        return reportFile;
    }

    @Override
    public void investigationStarted(ReportEvent.InvestigationStarted event) {
        Test investigation = investigation(event.investigationId());
        investigation.setStartedAt(event.at().toEpochMilli());
        investigation.setDescription(html("Mode: " + nullToDash(event.mode()) + " | Client: " + nullToDash(event.client())
                + " | Started: " + TIME.format(event.at())));
        if (event.mode() != null) investigation.addTag(html(event.mode()));
        if (event.client() != null && !event.client().isBlank()) investigation.addTag(html(event.client()));
        investigation.setDescription(investigation.getDescription() + html(" | ID: " + event.investigationId()));
    }

    @Override
    public void stepRecorded(ReportEvent.StepRecorded event) {
        Test investigation = investigation(event.investigationId());
        Test step = new Test(html(event.kind() + " - " + event.title()));
        investigation.addChild(step);
        // Directly into the set: Test.addTag would copy the tag to the investigation as well.
        step.getTags().add(new Tag(event.kind().name()));
        if (event.kind() == ReportEvent.StepKind.LAUNCH && named.add(event.investigationId())) {
            // "start_java_application OrdersApplication" -> "Investigation inv-7f3a - OrdersApplication"
            int space = event.title().indexOf(' ');
            if (space > 0) investigation.setName(html("Investigation " + shortId(event.investigationId()) + " - "
                    + event.title().substring(space + 1).strip()));
        }
        boolean failed = event.status() == ReportEvent.StepStatus.FAILED;
        List<String> errors = new java.util.ArrayList<>();
        for (String line : event.details()) {
            // A failed step shows its error once, in ChainTest's error box, not again among the log lines.
            if (failed && line.startsWith("Error: ")) errors.add(line.substring("Error: ".length()));
            else step.addLog(html(line));
        }
        for (Path attachment : event.attachments()) {
            String type = mediaType(attachment);
            if (type != null && Files.isRegularFile(attachment)) step.addEmbed(attachment.toFile(), type);
        }
        if (failed) {
            step.setResult(Result.FAILED.getResult());
            step.setError(html(errors.isEmpty() ? "The tool call failed." : String.join("\n", errors)));
            failedCalls.merge(event.investigationId(), 1, Integer::sum);
        }
        step.complete();   // also raises the investigation's result to FAILED when the step failed
        long end = event.at().toEpochMilli();
        step.setStartedAt(end - Math.max(0, event.durationMs()));
        step.setEndedAt(end);
    }

    @Override
    public void investigationFinished(ReportEvent.InvestigationFinished event) {
        Test investigation = investigation(event.investigationId());
        open.remove(event.investigationId());
        if (event.conclusion() != null && !event.conclusion().isBlank()) {
            Test conclusion = new Test("CONCLUSION - diagnosis by the agent");
            investigation.addChild(conclusion);
            conclusion.getTags().add(new Tag("CONCLUSION"));
            conclusion.addLog(html(event.conclusion()));
            conclusion.complete();
        }
        if (!event.cleanup().isEmpty()) {
            Test cleanup = new Test(html(ReportEvent.StepKind.CLEANUP + " - " + (event.outcome() == ReportEvent.Outcome.FINISHED
                    ? "finish_debug_investigation" : "automatic cleanup")));
            investigation.addChild(cleanup);
            cleanup.getTags().add(new Tag(ReportEvent.StepKind.CLEANUP.name()));
            for (String line : event.cleanup()) cleanup.addLog(html(line));
            cleanup.complete();
        }
        boolean cleanupProblem = event.cleanup().stream()
                .anyMatch(line -> line.startsWith("Warning:") || line.equals("JVM ended: false"));
        end(investigation, event.investigationId(), event.outcome(), cleanupProblem, event.at().toEpochMilli(), null);
    }

    @Override
    public void eventsDropped(long count) {
        droppedEvents = count;
    }

    @Override
    public void close() {
        // Investigations still open when the session ends are reported as ended without finish.
        for (var entry : open.entrySet())
            end(entry.getValue(), entry.getKey(), ReportEvent.Outcome.ENDED_WITHOUT_FINISH, false,
                    System.currentTimeMillis(), "The agent session ended before this investigation was finished.");
        open.clear();
        if (droppedEvents > 0) {
            Test notice = new Test("Report incomplete");
            notice.setDescription(html(droppedEvents + " events were not recorded because the report could not keep up."));
            notice.setResult(Result.SKIPPED.getResult());
            notice.complete();
            build.updateStats(notice);
            finished.add(notice);
        }
        build.complete();
        writeReport();
    }

    private Test investigation(String id) {
        return open.computeIfAbsent(id, key -> new Test(html("Investigation " + shortId(key))));
    }

    /** UUIDs are long; the first 8 characters identify an investigation in the name (the full ID is in the description). */
    static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 8) : id;
    }

    private void end(Test investigation, String id, ReportEvent.Outcome outcome, boolean cleanupProblem,
                     long endedAt, String note) {
        investigation.addTag(outcome == ReportEvent.Outcome.FINISHED ? "finished" : "ended-without-finish");
        List<String> notes = new java.util.ArrayList<>();
        // The investigation's result is decided by how it ended, not by tool calls the agent recovered from.
        if (outcome == ReportEvent.Outcome.ENDED_WITHOUT_FINISH) {
            notes.add(note != null ? note
                    : "Ended without finish_debug_investigation: cleaned up automatically (idle lease, MCP disconnect or restart).");
            investigation.setResult(Result.SKIPPED.getResult());
        } else if (cleanupProblem) {
            notes.add("Cleanup reported a problem (see the CLEANUP step).");
            investigation.setResult(Result.FAILED.getResult());
        } else {
            investigation.setResult(Result.PASSED.getResult());
        }
        int failures = failedCalls.getOrDefault(id, 0);
        if (failures > 0) notes.add(failures + (failures == 1 ? " tool call" : " tool calls") + " failed; the agent continued.");
        if (!notes.isEmpty()) {
            String before = investigation.getDescription();
            investigation.setDescription((before == null || before.isBlank() ? "" : before + " | ") + html(String.join(" ", notes)));
        }
        investigation.setEndedAt(endedAt);
        build.updateStats(investigation);
        finished.add(investigation);
        writeReport();
    }

    private void writeReport() {
        if (!finished.isEmpty()) generator.flush(finished);
    }

    private static String mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return null;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * ChainTest's templates insert text without escaping: escape HTML characters, and write every non-ASCII
     * character as a numeric entity because the generator writes the file in the platform's default encoding.
     */
    static String html(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c > 126) out.append("&#").append(c).append(';');
                    else out.appendCodePoint(c);
                }
            }
        });
        return out.toString();
    }
}
