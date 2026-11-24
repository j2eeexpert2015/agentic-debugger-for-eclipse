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
 * <p><b>Look.</b> Light theme. The header names what was debugged ("Debugging OrdersApplication") and, below it,
 * "Debugging session report", the product and the start time; the browser tab title says the same. ChainTest's own CSS and scripts are used unchanged, except one CSS rule ({@link #CSS}) that
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
    /** Marker in the name of a section heading card ("From the agent", "What the agent did in Eclipse"). */
    static final String SECTION_MARK = "<span class=\"ad-section\"></span>";
    /** Name of an investigation until its first launch names it ("Debugging OrdersApplication"). */
    static final String UNNAMED = "Debugging session";
    /**
     * Our additions to ChainTest's page, in one place (light theme):
     * <ul>
     *   <li>hidden: the dashboard and tag table ({@code #summary}), the search and filter bar, the build badges in the
     *       header (duration and times of writing the file), the "ChainTest" label at the top left, and the time
     *       badges and tag pills of the investigation; hidden rather than removed, because ChainTest's script looks
     *       these elements up when the page loads;</li>
     *   <li>header: a dark blue band with a bug icon and the report title in white;</li>
     *   <li>page: light grey background, white cards with rounded corners and a soft shadow;</li>
     *   <li>left column: stays in view while scrolling; the investigation name is larger and followed by its outcome
     *       (Finished, Cleanup problem, Not finished) as a coloured label;</li>
     *   <li>the request card (blue) and the conclusion card (green) highlighted, shown in full without a scroll box
     *       and without their duration badge; the agent's Markdown analysis rendered ({@link #markdown(String)});</li>
     *   <li>steps numbered 1, 2, 3 ...; each step kind has its own colour ({@link #kindMark}): left border, number
     *       and kind label (launch blue, breakpoint purple, stop orange, step cyan, inspect teal, HTTP pink, exception
     *       red, cleanup green);</li>
     *   <li>two parts with a heading each: "From the agent" (request, analysis) and "What the agent did in Eclipse"
     *       (steps, cleanup);</li>
     *   <li>technical details of a step (tool, arguments, raw result) collapsed under "Technical details";</li>
     *   <li>a refused tool call is amber, not red, and says that the agent retried it: it is not an application
     *       error;</li>
     *   <li>long lines wrap instead of scrolling sideways; step details have a taller box.</li>
     * </ul>
     * Colours and the icon use only ASCII in this text (CSS escapes), because ChainTest writes the page in the
     * platform's default encoding.
     */
    static final String CSS = "#summary,div.py-3.mb-5:has(#q),.navbar .container>div:last-child,.navbar-brand,"
            + ".test-container .col-4 .small,.test-container .col-4 .my-2{display:none}"
            // page and header
            + "body{background:#f3f5f9}"
            + ".navbar{background:linear-gradient(90deg,#0b2545 0%,#13315c 55%,#1d5fa8 100%);border:0!important;"
            + "padding:1rem 0;margin-bottom:1.5rem;box-shadow:0 2px 8px rgba(11,37,69,.25)}"
            + ".navbar-brand+span{margin-left:0!important;font-weight:600;color:#fff;font-size:1.15rem!important;letter-spacing:.01em}"
            + ".navbar-brand+span::before{content:\"\\1F41E\";margin-right:.6rem}"
            + ".ad-sub{display:block;margin-top:.15rem;font-size:.82rem;font-weight:400;color:#c9d8ee;letter-spacing:0}"
            + ".test-container{padding-top:0!important}.test-container .border-bottom{border:0!important}"
            // left column: the investigation
            + ".test-container .col-4{position:sticky;top:1rem;align-self:flex-start}"
            + ".testname{font-size:1.2rem!important;font-weight:600;line-height:1.35}"
            + ".testname::after{display:block;width:max-content;margin-top:.5rem;padding:.2rem .7rem;border-radius:1rem;"
            + "font-size:.8rem;font-weight:600;color:#fff}"
            + ".testname.passed{color:#0b2545!important}.testname.passed::after{content:\"Finished\";background:#198754}"
            + ".testname.failed{color:#0b2545!important}.testname.failed::after{content:\"Cleanup problem\";background:#dc3545}"
            + ".testname.skipped{color:#0b2545!important}.testname.skipped::after{content:\"Not finished\";background:#fd7e14}"
            + ".test-container .col-4 code{display:block;color:#495057;white-space:normal;font-family:inherit;font-size:.9rem;"
            + "background:#fff;border-radius:.5rem;padding:.75rem 1rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
            // cards
            + ".col-8 .card{border:0;border-radius:.6rem;box-shadow:0 1px 3px rgba(0,0,0,.08),0 4px 12px rgba(0,0,0,.04)}"
            + ".col-8 .card .fs-6{font-weight:500}"
            + ".col-8 .card .badge.bg-light{background:#eef1f5!important;color:#6c757d!important;font-weight:500}"
            + "pre{white-space:pre-wrap;word-break:break-word;background:#f6f8fb!important;border-radius:.4rem;"
            + "font-size:.82rem;max-height:24rem!important}"
            // request and conclusion
            + ".card:has(.ad-request){border-left:6px solid #0d6efd!important;background:#eef4ff}"
            + ".card:has(.ad-conclusion){border-left:6px solid #198754!important;background:#ecf7f0}"
            + ".card:has(.ad-request) .badge,.card:has(.ad-conclusion) .badge,"
            + ".card:has(.ad-request) .mx-1,.card:has(.ad-conclusion) .mx-1{display:none}"
            + ".card:has(.ad-request) pre,.card:has(.ad-conclusion) pre{font-size:.95rem;max-height:none!important;resize:none;"
            + "background:#fff!important}"
            + ".card:has(.ad-request) .fs-6,.card:has(.ad-conclusion) .fs-6{font-weight:600;font-size:1.05rem!important}"
            + ".card:has(.ad-conclusion) pre{white-space:normal;font-family:var(--bs-body-font-family);line-height:1.55}"
            + ".ad-md code{color:#a3005b;background:rgba(0,0,0,.06);padding:0 .25rem;border-radius:.25rem}"
            + ".ad-md code.ad-block{display:block;white-space:pre-wrap;color:inherit;padding:.5rem;margin:.25rem 0 .5rem}"
            + ".ad-md p,.ad-md ul,.ad-md ol,.ad-md table{margin:0 0 .6rem}.ad-md .ad-h{margin:.6rem 0 .3rem;font-weight:600;color:#146c43}"
            + ".ad-md table{border-collapse:collapse}.ad-md td,.ad-md th{border:1px solid #c8d1cc;padding:.15rem .6rem}"
            + ".ad-md th{background:rgba(25,135,84,.12)}"
            // steps: number and colour by kind
            + ".col-8{counter-reset:ad-step}"
            + ".card:has(.ad-k){--k:#adb5bd;border-left:5px solid var(--k)!important;counter-increment:ad-step}"
            + ".card:has(.ad-k) .fs-6::before{content:counter(ad-step);display:inline-block;min-width:1.6rem;height:1.6rem;"
            + "line-height:1.6rem;margin-right:.55rem;border-radius:50%;background:var(--k);color:#fff;text-align:center;"
            + "font-size:.8rem;font-weight:600}"
            + ".card:has(.ad-k) .tag-list .badge{background:var(--k)!important;color:#fff!important;border:0}"
            + ".card:has(.ad-k-launch){--k:#0d6efd}.card:has(.ad-k-breakpoint){--k:#6f42c1}.card:has(.ad-k-stop){--k:#fd7e14}"
            + ".card:has(.ad-k-step){--k:#0aa2c0}.card:has(.ad-k-inspect){--k:#20a07a}.card:has(.ad-k-http){--k:#d63384}"
            + ".card:has(.ad-k-exception){--k:#dc3545}.card:has(.ad-k-cleanup){--k:#198754}.card:has(.ad-k-other){--k:#6c757d}"
            // a refused tool call: amber, not red, because it is the agent's call, not the application, that failed
            + ".card.result.failed{background:#fff8e8}"
            + ".card.result.failed .bi-exclamation-octagon-fill{color:#e08a00!important}"
            + ".card.result.failed pre.mt-2{background:#fff0cc!important;color:#6b4a00;border-left:3px solid #e0a000}"
            + ".card.result.failed pre.mt-2::before{content:\"Tool call refused, the agent retried: \";font-weight:600}"
            // technical details, collapsed
            + ".ad-tech{margin-top:.4rem;color:#6c757d}.ad-tech summary{cursor:pointer;font-family:var(--bs-body-font-family);"
            + "font-size:.8rem;color:#6c757d;width:max-content}"
            // section headings between the two parts of the report
            + ".card:has(.ad-section){background:transparent!important;box-shadow:none!important;border:0!important;"
            + "margin:1.4rem 0 .4rem!important}"
            + ".card:has(.ad-section) .card-body{padding:0 0 .4rem;border-bottom:2px solid #c9d3e0}"
            + ".card:has(.ad-section) i.bi,.card:has(.ad-section) .badge,.card:has(.ad-section) .mx-1{display:none}"
            + ".card:has(.ad-section) .fs-6{margin-left:0!important;font-size:1.25rem!important;font-weight:700!important;"
            + "color:#0b2545;text-transform:none}"
            + ".card:has(.ad-section) small{display:block;font-size:.85rem;font-weight:400;color:#6c757d;margin-top:.1rem}"
            + ".col-8>.card:first-child:has(.ad-section){margin-top:0!important}";

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
                + ", " + nullToDash(event.mode()) + " mode  |  Investigation " + shortId(event.investigationId())));
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
        Test step = new Test(kindMark(event.kind()) + html(event.title()));
        investigation.addChild(step);
        // Directly into the set: Test.addTag would copy the tag to the investigation as well.
        step.getTags().add(new Tag(event.kind().name()));
        if (event.kind() == ReportEvent.StepKind.LAUNCH && !targets.containsKey(event.investigationId())) {
            // "Started application in debug mode: OrdersApplication" -> "Debugging OrdersApplication";
            // "Started JUnit test in debug mode: OrderServiceTest.quote" -> "Debugging OrderServiceTest.quote"
            int colon = event.title().lastIndexOf(": ");
            int space = event.title().indexOf(' ');
            String target = colon > 0 ? event.title().substring(colon + 2) : space > 0 ? event.title().substring(space + 1) : "";
            if (!target.isBlank()) {
                targets.put(event.investigationId(), target.strip());
                investigation.setName(html("Debugging " + target.strip()));
            }
        }
        boolean failed = event.status() == ReportEvent.StepStatus.FAILED;
        List<String> errors = new java.util.ArrayList<>();
        List<String> details = new java.util.ArrayList<>(event.details());
        int technical = details.indexOf(ReportEvent.TECHNICAL_DETAILS);
        if (technical >= 0 && technical == details.size() - 1) details.remove(technical);          // nothing technical after it
        boolean collapsed = false;
        for (String line : details) {
            // A failed step shows its error once, in ChainTest's error box, not again among the log lines.
            if (failed && line.startsWith("Error: ")) errors.add(line.substring("Error: ".length()));
            else if (line.equals(ReportEvent.TECHNICAL_DETAILS)) {
                // Tool name, arguments and raw result: one click away, not in the reader's way.
                step.addLog("<details class=\"ad-tech\"><summary>Technical details</summary>");
                collapsed = true;
            } else step.addLog(html(line));
        }
        if (collapsed) step.addLog("</details>");
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
            conclusion.addLog(CONCLUSION_MARK + markdown(event.conclusion()));
            conclusion.complete();
        }
        if (!event.cleanup().isEmpty()) {
            Test cleanup = new Test(kindMark(ReportEvent.StepKind.CLEANUP) + (event.outcome() == ReportEvent.Outcome.FINISHED
                    ? "Finished investigation and cleaned up" : "Cleaned up automatically"));
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
        return open.computeIfAbsent(id, key -> new Test(html(UNNAMED)));
    }

    /**
     * Final order of the cards, in two parts with a heading each: "From the agent" (the request and the agent's
     * analysis), then "What the agent did in Eclipse" (the steps, numbered, and the cleanup). The headings are cards
     * of their own, styled as headings ({@link #SECTION_MARK}); they are added to the queue directly, not with
     * addChild/complete, so they never change the investigation's result.
     */
    private static void arrange(Test investigation, long at) {
        java.util.Queue<Test> children = investigation.getChildren();
        List<Test> rest = new java.util.ArrayList<>(children);
        List<Test> ordered = new java.util.ArrayList<>();
        ordered.add(section("From the agent", "What was asked, and the agent's analysis", at));
        for (String name : List.of("Request from the user", "Conclusion by the agent")) {
            for (Test child : List.copyOf(rest)) {
                if (name.equals(child.getName())) { ordered.add(child); rest.remove(child); }
            }
        }
        ordered.add(section("What the agent did in Eclipse",
                "Every debugger action in order: breakpoints, launches, stops with their values, cleanup", at));
        ordered.addAll(rest);
        children.clear();
        children.addAll(ordered);
    }

    private static Test section(String title, String subtitle, long at) {
        Test section = new Test(SECTION_MARK + html(title) + "<small>" + html(subtitle) + "</small>");
        section.setResult(Result.PASSED.getResult());
        section.setStartedAt(at);
        section.setEndedAt(at);
        return section;
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
        if (failures > 0) notes.add(failures + (failures == 1 ? " tool call of the agent was" : " tool calls of the agent were")
                + " refused and retried (marked in the steps); this is not an error in the application.");
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
        arrange(investigation, start.toEpochMilli());
        Build build = new Build(projectName, RUNNER);
        // Header: what was debugged, in large type ("Debugging OrdersApplication"), and below it in small type what
        // this page is, the product and when the investigation started. The browser tab says the same in one line.
        String subject = investigation.getName();          // already escaped
        String header = subject + "<small class=\"ad-sub\">" + html("Debugging session report  \u00b7  " + projectName
                + "  \u00b7  " + TIME.format(start)) + "</small>";
        String tabTitle = subject + html(" - debugging session report - " + TIME.format(start));
        // "js" and "css" must be present, even empty: ChainTestSimpleGenerator.flush() puts them into Map.of(...),
        // which rejects null values (NullPointerException in flush when they are missing).
        Map<String, String> config = Map.of(
                PREFIX + "enabled", "true",
                PREFIX + "output-file", file.toString(),
                PREFIX + "offline", "true",
                PREFIX + "dark-theme", "false",
                PREFIX + "document-title", tabTitle,
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
                String style = Files.readString(css, StandardCharsets.UTF_8);
                // The icon font is referenced relative to the stylesheet; inside the HTML it must be a data URI,
                // otherwise every icon (step result, header) shows as an empty box.
                java.util.regex.Matcher font = FONT.matcher(style);
                StringBuilder inlined = new StringBuilder();
                while (font.find()) {
                    Path fontFile = resources.resolve(font.group(1));
                    // Only woff2 is inlined (every current browser uses it first); the woff fallback stays a link.
                    String url = font.group(2).equals("woff2") && Files.isRegularFile(fontFile)
                            ? "url(\"data:font/" + font.group(2) + ";base64,"
                                    + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(fontFile)) + "\")"
                            : font.group(0);
                    font.appendReplacement(inlined, java.util.regex.Matcher.quoteReplacement(url));
                }
                font.appendTail(inlined);
                style = inlined.toString();
                page = page.replace("<link id=\"style\" href=\"resources/chaintest-pkg.css\" rel=\"stylesheet\">",
                        "<style id=\"style\">" + style.replace("</style", "<\\/style") + "</style>");
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

    /** {@code url("bootstrap-icons.woff2?hash")} in ChainTest's stylesheet; group 1 the file, group 2 woff or woff2. */
    private static final java.util.regex.Pattern FONT =
            java.util.regex.Pattern.compile("url\\(\"?(bootstrap-icons\\.(woff2?))(?:\\?[^\")]*)?\"?\\)");
    private static final java.util.regex.Pattern IMAGE = java.util.regex.Pattern.compile("<img src=\"resources/([^\"/]+)\"");

    /**
     * The agent's analysis as HTML. Agents write Markdown, so the common parts are rendered: headings, paragraphs,
     * bullet and numbered lists, tables, fenced code blocks, {@code **bold**} and {@code `code`}. Everything else is
     * shown as text. All text is escaped first ({@link #html(String)}); only the tags produced here are HTML. The
     * result has no line breaks between blocks, because ChainTest puts it inside a {@code <pre>} element.
     */
    static String markdown(String text) {
        StringBuilder out = new StringBuilder("<div class=\"ad-md\">");
        List<String> paragraph = new java.util.ArrayList<>();
        String list = null;            // "ul" or "ol" while a list is open
        boolean table = false, tableHead = false, code = false;
        StringBuilder codeText = new StringBuilder();
        for (String raw : (text == null ? "" : text).replace("\r", "").split("\n", -1)) {
            String line = raw.strip();
            if (code) {
                if (line.startsWith("```")) {
                    out.append("<code class=\"ad-block\">").append(html(codeText.toString().stripTrailing())).append("</code>");
                    codeText.setLength(0);
                    code = false;
                } else {
                    codeText.append(raw).append('\n');
                }
                continue;
            }
            boolean isTable = line.startsWith("|") && line.endsWith("|") && line.length() > 1;
            boolean isBullet = line.matches("[-*] .*");
            boolean isNumbered = line.matches("\\d+[.)] .*");
            boolean isHeading = line.matches("#{1,6} .*");
            boolean isFence = line.startsWith("```");
            if (!paragraph.isEmpty() && (line.isEmpty() || isTable || isBullet || isNumbered || isHeading || isFence)) {
                out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
                paragraph.clear();
            }
            if (list != null && !(list.equals("ul") ? isBullet : isNumbered)) {
                out.append("</").append(list).append(">");
                list = null;
            }
            if (table && !isTable) {
                out.append("</table>");
                table = false;
            }
            if (line.isEmpty()) continue;
            if (isFence) {
                code = true;
            } else if (isHeading) {
                out.append("<div class=\"ad-h\">").append(inline(line.replaceFirst("#+ ", ""))).append("</div>");
            } else if (isBullet || isNumbered) {
                String kind = isBullet ? "ul" : "ol";
                if (list == null) {
                    out.append("<").append(kind).append(">");
                    list = kind;
                }
                out.append("<li>").append(inline(line.replaceFirst(isBullet ? "[-*] " : "\\d+[.)] ", ""))).append("</li>");
            } else if (isTable) {
                if (line.matches("\\|[\\s:|-]+\\|")) continue;      // the |---|---| line under the header
                if (!table) {
                    out.append("<table>");
                    table = true;
                    tableHead = true;
                }
                String cell = tableHead ? "th" : "td";
                out.append("<tr>");
                for (String value : line.substring(1, line.length() - 1).split("\\|", -1))
                    out.append("<").append(cell).append(">").append(inline(value.strip())).append("</").append(cell).append(">");
                out.append("</tr>");
                tableHead = false;
            } else {
                paragraph.add(line);
            }
        }
        if (code) out.append("<code class=\"ad-block\">").append(html(codeText.toString().stripTrailing())).append("</code>");
        if (!paragraph.isEmpty()) out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
        if (list != null) out.append("</").append(list).append(">");
        if (table) out.append("</table>");
        return out.append("</div>").toString();
    }

    /** Escaped text with {@code `code`} and {@code **bold**}. */
    private static String inline(String text) {
        return codeSpans(html(text)).replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
    }

    /**
     * Invisible marker at the start of a step's name, {@code <span class="ad-k ad-k-breakpoint"></span>}: the CSS
     * numbers the steps and colours them by kind with it.
     */
    static String kindMark(ReportEvent.StepKind kind) {
        return "<span class=\"ad-k ad-k-" + kind.name().toLowerCase(Locale.ROOT) + "\"></span>";
    }

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
