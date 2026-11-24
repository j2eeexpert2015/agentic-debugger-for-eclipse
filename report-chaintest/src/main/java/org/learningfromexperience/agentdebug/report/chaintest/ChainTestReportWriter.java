package org.learningfromexperience.agentdebug.report.chaintest;

import java.nio.charset.StandardCharsets;
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
import org.learningfromexperience.agentdebug.report.ReportFiles;
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
 *   <li>one investigation = one ChainTest build = one report file, named by {@link ReportFiles}
 *       ({@code OrdersApplication_2026-11-24_10-39-31.html}); the file is the one the recorder chose and passed in
 *       {@link ReportEvent.InvestigationFinished#reportFile()}. ChainTest writes its CSS, script and embedded
 *       images into a {@code resources} folder next to the report; {@link #selfContained(Path)} then copies them into
 *       the HTML, so the report file can be copied or mailed on its own;</li>
 *   <li>what the user asked = the first child, "Request from the user", when known;</li>
 *   <li>one investigation = one top-level test; its result is PASSED when it was finished and cleaned up, FAILED when
 *       cleanup reported a problem (JVM still running), SKIPPED when it ended without
 *       {@code finish_debug_investigation} (idle lease, disconnect). A failed tool call the agent recovered from does
 *       not fail the investigation: the step is red, the investigation says how many calls failed;</li>
 *   <li>one step (tool call) = one child test named by its plain-language title ("Set breakpoint: OrderService,
 *       line 24"), tagged with its kind (badge), with its details as log lines and its attachments (screenshots) as
 *       embedded images;</li>
 *   <li>the agent's conclusion = a "Conclusion by the agent" child, the cleanup result = a final cleanup child.</li>
 * </ul>
 * ChainTest's page shows logs and images only for child tests; for an investigation it shows name, tags, times
 * and description, so investigation-level notes (how it ended) go into the description. Tags give badges on each
 * card and the tag chart at the top: every step is tagged with its kind, the investigation only with its mode, client
 * and outcome (step tags are added directly, because ChainTest would otherwise copy them to the investigation).
 * The investigation is renamed after its first launch target; long IDs are shortened to 8 characters in the name.
 *
 * <p><b>Look.</b> Dark theme, title "Agentic Debugger - Debugging Session Report", header with the project name and
 * the session start time. ChainTest's own CSS and scripts are used unchanged, except one CSS rule ({@link #CSS}) that
 * hides what a debugging session report does not need: the dashboard (Tests / Methods / Summary charts and the Tags
 * table, element {@code #summary}) and the search and filter bar. They are hidden, not removed from the page, because
 * ChainTest's script looks them up when the page loads and would stop (theme switch, image viewer) without them.
 *
 * <p>Uses only ChainTest's core engine ({@link Build}, {@link Test}) and {@link ChainTestSimpleGenerator}, configured
 * in code (no {@code chaintest.properties}), offline (CSS and scripts copied next to the report). Not
 * {@code ChainTestListener}: that is a TestNG adapter whose {@code log}/{@code embed} only work during a TestNG test.
 * A report is written as soon as its investigation finishes, so it exists even if the session is killed later.
 *
 * <p>Runs only on the report thread of {@code AsyncReportRecorder}; not thread-safe by design. ChainTest's templates
 * do not escape text, so everything is HTML-escaped here ({@link #html}).
 *
 * @author Ayan Dutta
 */
public final class ChainTestReportWriter implements DebugReportListener {

    static final String RUNNER = "agentic-debugger";
    private static final String PREFIX = "chaintest.generator.simple.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    /** Format of the start and end times ChainTest shows on each investigation (java.text.SimpleDateFormat). */
    private static final String CARD_TIME_FORMAT = "dd MMM yyyy, HH:mm:ss";
    /** Invisible markers in the first log line of the request and conclusion cards, so the CSS can highlight them. */
    static final String REQUEST_MARK = "<span class=\"ad-request\"></span>";
    static final String CONCLUSION_MARK = "<span class=\"ad-conclusion\"></span>";
    /**
     * Our additions to ChainTest's page, in one place:
     * <ul>
     *   <li>hidden: the dashboard and tag table ({@code #summary}), the search and filter bar, the build badges in the
     *       header (duration and times of writing the file), the "ChainTest" label at the top left, and the time badges and tag pills of the investigation;
     *       hidden rather than removed, because ChainTest's script looks these elements up when the page loads;</li>
     *   <li>the request card (blue) and the conclusion card (green) highlighted, without their duration badge;</li>
     *   <li>long lines wrap instead of scrolling sideways; the investigation's summary line is plain text.</li>
     * </ul>
     */
    static final String CSS = "#summary,div.py-3.mb-5:has(#q),.navbar .container>div:last-child,.navbar-brand,"
            + ".test-container .col-4 .small,.test-container .col-4 .my-2{display:none}"
            + ".navbar-brand+span{margin-left:0!important;font-weight:600}"
            + ".card:has(.ad-request){border-left:6px solid #0d6efd;background:rgba(13,110,253,.07)}"
            + ".card:has(.ad-conclusion){border-left:6px solid #198754;background:rgba(25,135,84,.09)}"
            + ".card:has(.ad-request) .badge,.card:has(.ad-conclusion) .badge,"
            + ".card:has(.ad-request) .mx-1,.card:has(.ad-conclusion) .mx-1{display:none}"
            + ".card:has(.ad-request) pre,.card:has(.ad-conclusion) pre{font-size:.95rem}"
            + "pre{white-space:pre-wrap;word-break:break-word}"
            + ".card:has(.ad-conclusion) pre code{color:#a3005b;background:rgba(0,0,0,.06);padding:0 .25rem;border-radius:.25rem}"
            + ".test-container .col-4 code{color:inherit;white-space:normal;font-family:inherit}";

    private final Path folder;
    private final String projectName;
    private final Map<String, Test> open = new LinkedHashMap<>();
    /** Investigations already named after their launch target, and that target (for the file name). */
    private final Map<String, String> targets = new java.util.HashMap<>();
    private final Map<String, java.time.Instant> starts = new java.util.HashMap<>();
    /** Report files chosen in this session, so two reports never get the same name. */
    private final java.util.Set<Path> taken = new java.util.HashSet<>();
    /** Failed tool calls per investigation. */
    private final Map<String, Integer> failedCalls = new java.util.HashMap<>();
    private final List<Path> written = new java.util.ArrayList<>();
    private long droppedEvents;

    public ChainTestReportWriter(ReportSettings settings) throws Exception {
        folder = Files.createDirectories(settings.outputDirectory()).toAbsolutePath().normalize();
        projectName = settings.projectName();
    }

    /** Report files written so far in this session, in order (for tests and diagnostics). */
    public List<Path> writtenReports() {
        return List.copyOf(written);
    }

    @Override
    public void investigationStarted(ReportEvent.InvestigationStarted event) {
        Test investigation = investigation(event.investigationId());
        investigation.setStartedAt(event.at().toEpochMilli());
        investigation.setDescription(html(TIME.format(event.at()) + "  |  " + nullToDash(event.client())
                + ", " + nullToDash(event.mode()) + " mode"));
        if (event.mode() != null) investigation.addTag(html(event.mode()));
        if (event.client() != null && !event.client().isBlank()) investigation.addTag(html(event.client()));
        starts.put(event.investigationId(), event.at());
        if (event.request() != null && !event.request().isBlank()) {
            // First card: what the user asked, so the report reads request -> steps -> conclusion.
            Test request = new Test("Request from the user");
            investigation.addChild(request);
            request.getTags().add(new Tag("REQUEST"));
            request.addLog(REQUEST_MARK + html(event.requestExact() ? "Exact prompt sent to the agent:" : "As passed on by the agent:"));
            request.addLog("");
            for (String line : event.request().split("\\R")) request.addLog(html(line));
            request.complete();
            long at = event.at().toEpochMilli();
            request.setStartedAt(at);
            request.setEndedAt(at);
        }
    }

    @Override
    public void stepRecorded(ReportEvent.StepRecorded event) {
        Test investigation = investigation(event.investigationId());
        Test step = new Test(html(event.title()));
        investigation.addChild(step);
        // Directly into the set: Test.addTag would copy the tag to the investigation as well.
        step.getTags().add(new Tag(event.kind().name()));
        if (event.kind() == ReportEvent.StepKind.LAUNCH && !targets.containsKey(event.investigationId())) {
            // "Started application in debug mode: OrdersApplication" -> "Investigation 36160c77 - OrdersApplication"
            int colon = event.title().lastIndexOf(": ");
            int space = event.title().indexOf(' ');
            String target = colon > 0 ? event.title().substring(colon + 2) : space > 0 ? event.title().substring(space + 1) : "";
            if (!target.isBlank()) {
                targets.put(event.investigationId(), target.strip());
                investigation.setName(html("Investigation " + shortId(event.investigationId()) + " - " + target.strip()));
            }
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
            Test conclusion = new Test("Conclusion by the agent");
            investigation.addChild(conclusion);
            conclusion.getTags().add(new Tag("CONCLUSION"));
            conclusion.addLog(CONCLUSION_MARK + codeSpans(html(event.conclusion())));
            conclusion.complete();
            moveUp(investigation, conclusion);
        }
        if (!event.cleanup().isEmpty()) {
            Test cleanup = new Test(event.outcome() == ReportEvent.Outcome.FINISHED
                    ? "Finished investigation and cleaned up" : "Cleaned up automatically");
            investigation.addChild(cleanup);
            cleanup.getTags().add(new Tag(ReportEvent.StepKind.CLEANUP.name()));
            for (String line : event.cleanup()) cleanup.addLog(html(line));
            cleanup.complete();
        }
        boolean cleanupProblem = event.cleanup().stream()
                .anyMatch(line -> line.startsWith("Warning:") || line.equals("JVM ended: false"));
        end(investigation, event.investigationId(), event.outcome(), cleanupProblem, event.at().toEpochMilli(), null,
                event.reportFile());
    }

    @Override
    public void eventsDropped(long count) {
        droppedEvents = count;
    }

    @Override
    public void close() {
        // Investigations still open when the session ends are reported as ended without finish.
        for (var entry : new java.util.ArrayList<>(open.entrySet()))
            end(entry.getValue(), entry.getKey(), ReportEvent.Outcome.ENDED_WITHOUT_FINISH, false,
                    System.currentTimeMillis(), "The agent session ended before this investigation was finished.", null);
        open.clear();
    }

    private Test investigation(String id) {
        return open.computeIfAbsent(id, key -> new Test(html("Investigation " + shortId(key))));
    }

    /**
     * Puts the conclusion right after the request (or first, without a request), so a reader sees what was asked and
     * what was found before the steps. ChainTest keeps children in insertion order in a queue, so the queue is rebuilt.
     */
    private static void moveUp(Test investigation, Test conclusion) {
        java.util.Queue<Test> children = investigation.getChildren();
        List<Test> ordered = new java.util.ArrayList<>(children);
        ordered.remove(conclusion);
        int at = !ordered.isEmpty() && "Request from the user".equals(ordered.get(0).getName()) ? 1 : 0;
        ordered.add(at, conclusion);
        children.clear();
        children.addAll(ordered);
    }

    /** UUIDs are long; the first 8 characters identify an investigation in the name (the full ID is in the description). */
    static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 8) : id;
    }

    private void end(Test investigation, String id, ReportEvent.Outcome outcome, boolean cleanupProblem,
                     long endedAt, String note, Path chosenFile) {
        open.remove(id);
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
        if (droppedEvents > 0) notes.add("Report incomplete: " + droppedEvents + " events of this session were not recorded.");
        java.time.Instant began = starts.get(id);
        if (began != null) {
            long seconds = Math.max(0, (endedAt - began.toEpochMilli()) / 1000);
            notes.add(0, "Took " + (seconds >= 60 ? seconds / 60 + " min " + seconds % 60 + " s" : seconds + " s") + ".");
        }
        if (!notes.isEmpty()) {
            String before = investigation.getDescription();
            investigation.setDescription((before == null || before.isBlank() ? "" : before + " | ") + html(String.join(" ", notes)));
        }
        investigation.setEndedAt(endedAt);
        java.time.Instant start = starts.getOrDefault(id, java.time.Instant.ofEpochMilli(endedAt));
        Path file = chosenFile != null ? chosenFile
                : ReportFiles.reserve(folder, targets.get(id), start, ZoneId.systemDefault(), taken);
        taken.add(file);
        writeReport(investigation, file, start);
    }

    /** Writes one investigation as its own report file with a fresh ChainTest build and generator. */
    private void writeReport(Test investigation, Path file, java.time.Instant start) {
        try {
            Files.createDirectories(file.getParent());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Build build = new Build(projectName, RUNNER);
        // Header next to ChainTest's logo: project, report kind and when the investigation started.
        String header = html(projectName + "  |  Debugging Session Report  |  " + TIME.format(start));
        // "js" and "css" must be present, even empty: ChainTestSimpleGenerator.flush() puts them into Map.of(...),
        // which rejects null values (NullPointerException in flush when they are missing).
        Map<String, String> config = Map.of(
                PREFIX + "enabled", "true",
                PREFIX + "output-file", file.toString(),
                PREFIX + "offline", "true",
                PREFIX + "dark-theme", "false",
                PREFIX + "document-title", "Agentic Debugger - Debugging Session Report",
                PREFIX + "datetime-format", CARD_TIME_FORMAT,
                PREFIX + "js", "",
                PREFIX + "css", CSS,
                "chaintest.project.name", header);
        ChainTestSimpleGenerator generator = new ChainTestSimpleGenerator();
        generator.start(Optional.of(config), RUNNER, build);
        if (!generator.started()) throw new IllegalStateException("ChainTest report generator did not start");
        build.updateStats(investigation);
        build.complete();
        Queue<Test> tests = new ConcurrentLinkedQueue<>();
        tests.add(investigation);
        generator.flush(tests);
        selfContained(file);
        written.add(file);
    }

    /**
     * Makes the report one self-contained file: ChainTest links its stylesheet, its script and embedded images from
     * the {@code resources} folder next to the report, so a report copied or mailed on its own lost all styling (and
     * our hidden sections showed again). Their content is put into the HTML instead: stylesheet and script inline,
     * images as data URIs. If anything goes wrong the report stays as ChainTest wrote it, with its links.
     */
    static void selfContained(Path file) {
        Path resources = file.getParent().resolve("resources");
        try {
            String page = Files.readString(file, StandardCharsets.UTF_8);
            Path css = resources.resolve("chaintest-pkg.css");
            Path js = resources.resolve("chaintest-pkg.js");
            if (Files.isRegularFile(css)) {
                page = page.replace("<link id=\"style\" href=\"resources/chaintest-pkg.css\" rel=\"stylesheet\">",
                        "<style id=\"style\">" + Files.readString(css, StandardCharsets.UTF_8).replace("</style", "<\\/style") + "</style>");
            }
            if (Files.isRegularFile(js)) {
                page = page.replace("<script src=\"resources/chaintest-pkg.js\"></script>",
                        "<script>" + Files.readString(js, StandardCharsets.UTF_8).replace("</script", "<\\/script") + "</script>");
            }
            java.util.regex.Matcher m = IMAGE.matcher(page);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                Path image = resources.resolve(m.group(1));
                String type = mediaType(image);
                String src = type != null && Files.isRegularFile(image)
                        ? "data:" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(image))
                        : "resources/" + m.group(1);
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<img src=\"" + src + "\""));
            }
            m.appendTail(out);
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("[Eclipse Agentic Debugger] Report kept with links to its resources folder: " + e);
        }
    }

    private static final java.util.regex.Pattern IMAGE = java.util.regex.Pattern.compile("<img src=\"resources/([^\"/]+)\"");

    /** Text the agent marked as code with backticks ({@code `int rate = percent / 100`}) is shown as code. */
    static String codeSpans(String escaped) {
        return escaped.replaceAll("`([^`\n]+)`", "<code>$1</code>");
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
