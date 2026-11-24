package org.learningfromexperience.agentdebug.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * Writes the debugging session report (OPEN-30) as one self-contained HTML file per investigation, the same page as
 * the Agent Activity view in Eclipse (OPEN-38 step 4: replaces the ChainTest writer and its module).
 *
 * <p><b>Built like the view.</b> Every event goes, in the {@link ActivityEvents} format, into an {@link ActivityStore}
 * of this writer's own, exactly as the view's store in Eclipse receives it; at the end of an investigation
 * {@link ActivityPage#report} renders that investigation. So report and view show the same steps, colours, current
 * stop and analysis, in time order.
 *
 * <p><b>Files.</b> At {@code investigationFinished} the report goes to the file the MCP server chose
 * ({@link ReportEvent.InvestigationFinished#reportFile()}, so it can tell the agent the path). An investigation still
 * open when the session ends is written at {@link #close()} as ended without finish, to a file named by
 * {@link ReportFiles}.
 *
 * <p>Runs only on the report thread of {@link AsyncReportRecorder} (ADR-009): may be slow or fail without affecting
 * debugging, not thread-safe, never writes to {@code System.out} (stdout carries the MCP protocol).
 *
 * @author Ayan Dutta
 */
public final class HtmlReportWriter implements DebugReportListener {

    private final Path folder;
    private final String projectName;
    private final ZoneId zone;
    private final ActivityStore store = new ActivityStore();
    private final Set<Path> taken = new HashSet<>();
    private final List<Path> written = new ArrayList<>();

    public HtmlReportWriter(ReportSettings settings) throws IOException {
        this(settings, ZoneId.systemDefault());
    }

    HtmlReportWriter(ReportSettings settings, ZoneId zone) throws IOException {
        this.folder = Files.createDirectories(settings.outputDirectory()).toAbsolutePath().normalize();
        this.projectName = settings.projectName();
        this.zone = zone;
    }

    /** Report files written so far in this session, in order (for tests and diagnostics). */
    public List<Path> writtenReports() {
        return List.copyOf(written);
    }

    @Override
    public void investigationStarted(ReportEvent.InvestigationStarted event) {
        feed(ActivityEvents.json(event));
    }

    @Override
    public void stepStarted(ReportEvent.StepStarted event) {
        feed(ActivityEvents.json(event));
    }

    @Override
    public void stepRecorded(ReportEvent.StepRecorded event) {
        feed(ActivityEvents.json(event));
    }

    @Override
    public void investigationFinished(ReportEvent.InvestigationFinished event) throws IOException {
        feed(ActivityEvents.json(event));
        write(event.investigationId(), event.reportFile());
    }

    @Override
    public void eventsDropped(long count) {
        feed(ActivityEvents.dropped(count));
    }

    /** Investigations still open when the session ends: written as ended without finish. */
    @Override
    public void close() throws IOException {
        for (ActivityStore.Investigation inv : store.investigations()) {
            if (inv.finished()) continue;
            feed(ActivityEvents.finished(inv.id(), Instant.now().toString(), ReportEvent.Outcome.ENDED_WITHOUT_FINISH.name(),
                    null, List.of(), null));
            write(inv.id(), null);
        }
    }

    private void feed(String json) {
        String reply = store.accept(ActivityEvents.encode(json));
        if (!reply.contains("success")) throw new IllegalStateException("Event not taken: " + reply);
    }

    private void write(String id, Path chosen) throws IOException {
        ActivityStore.Investigation inv = null;
        for (ActivityStore.Investigation candidate : store.investigations())
            if (candidate.id().equals(id)) inv = candidate;
        if (inv == null) return;
        Path file = chosen != null ? chosen.toAbsolutePath().normalize() : ReportFiles.reserve(folder, target(inv),
                start(inv), zone, taken);
        taken.add(file);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, ActivityPage.report(inv, store.droppedEvents(), zone, projectName), StandardCharsets.UTF_8);
        written.add(file);
    }

    /** What was debugged, for the file name: "OrdersApplication" from "Debugging OrdersApplication"; null before a launch. */
    static String target(ActivityStore.Investigation inv) {
        String title = ActivityPage.title(inv);
        String prefix = "Debugging ";
        return title.startsWith(prefix) && !title.equals("Debugging session") ? title.substring(prefix.length()) : null;
    }

    private static Instant start(ActivityStore.Investigation inv) {
        try {
            return inv.startedAt() == null ? Instant.now() : Instant.parse(inv.startedAt());
        } catch (RuntimeException unparsable) {
            return Instant.now();
        }
    }
}
