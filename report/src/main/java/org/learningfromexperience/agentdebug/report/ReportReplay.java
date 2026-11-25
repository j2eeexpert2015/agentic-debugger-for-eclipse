package org.learningfromexperience.agentdebug.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * Builds a debugging session report again from the events a Claude trial recorded ({@code <report>.events.jsonl},
 * written with {@code AGENTDEBUG_REPORT_EVENTS=1}), without Claude, and runs the trial's REPORT CHECK on it. For
 * changes that only touch the report page (layout, colours, the flow): they need no new trial, which costs Claude
 * usage. Run by {@code java dev-e2e/DevE2E.java report-replay}.
 *
 * <pre>java -cp report/target/agentic-debugger-report-1.0.0-SNAPSHOT.jar
 *     org.learningfromexperience.agentdebug.report.ReportReplay EVENTS.jsonl OUT.html</pre>
 *
 * @author Ayan Dutta
 */
public final class ReportReplay {

    private ReportReplay() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: ReportReplay EVENTS.jsonl OUT.html");
            System.exit(2);
        }
        Path out = Path.of(args[1]).toAbsolutePath();
        String html = replay(Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8), ZoneId.systemDefault());
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.println("Replayed report: " + out);
        String check = check(html);
        System.out.println(check);
        if (!check.endsWith("PASS")) System.exit(1);
    }

    /** The report of the last investigation in the events, as the HTML writer would write it now. */
    static String replay(List<String> lines, ZoneId zone) {
        ActivityStore store = new ActivityStore();
        for (String line : lines) {
            if (line.isBlank()) continue;
            String reply = store.accept(ActivityEvents.encode(line));
            if (!reply.contains("success")) throw new IllegalArgumentException("Event not taken: " + reply);
        }
        List<ActivityStore.Investigation> all = store.investigations();
        if (all.isEmpty()) throw new IllegalArgumentException("No investigation in the events");
        return ActivityPage.report(all.get(all.size() - 1), store.droppedEvents(), zone, "Agentic Debugger for Eclipse");
    }

    /**
     * The REPORT CHECK of the Claude trials (ClaudeSymptomTrial.checkReport), without the part that needs the
     * agent's answer: the request, the agent's conclusion, the cleanup step, nothing loaded from outside the file.
     */
    static String check(String html) {
        List<String> missing = new ArrayList<>();
        if (!html.contains("<span class=\"lbl\">Request: </span>")) missing.add("request");
        if (!html.contains("<h2>From the agent</h2>")) missing.add("conclusion (no summary at finish)");
        if (!html.contains("Finished investigation and cleaned up") && !html.contains("Cleaned up automatically"))
            missing.add("cleanup step");
        if (html.contains("<link") || html.contains(" src=\"")) missing.add("self-contained file (something is loaded from outside)");
        return missing.isEmpty() ? "REPORT CHECK PASS" : "REPORT CHECK FAIL  missing: " + String.join(", ", missing);
    }
}
