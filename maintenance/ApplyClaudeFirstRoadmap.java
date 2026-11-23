import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-time, guarded update of roadmap priorities; no source-code or test changes. */
public final class ApplyClaudeFirstRoadmap {
    private static final String OPEN = "<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->";
    private static final String CLOSE = "<!-- CLAUDE-FIRST-PRIORITY:END -->";
    private ApplyClaudeFirstRoadmap() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Run from repository root without arguments");
        Path root = Path.of("").toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve("pom.xml")) && Files.isRegularFile(root.resolve("dev-e2e/DevE2E.java")),
                "Must run from agentic-debugger-for-eclipse repository root");
        Path development = root.resolve("docs/development-roadmap.md");
        Path features = root.resolve("docs/feature-roadmap.md");
        Path evaluation = root.resolve("docs/evaluation-plan.md");
        Map<Path,String> result = new LinkedHashMap<>();
        for (Path p : new Path[]{development,features,evaluation}) {
            require(Files.isRegularFile(p), "Missing required document: " + p);
            String original = Files.readString(p,StandardCharsets.UTF_8);
            require(original.startsWith("# ") && original.length() > 4000, "Unexpected documentation structure: " + p);
            result.put(p,original);
        }
        String dev = result.get(development);
        require(dev.contains("## Immediate implementation order"),"Missing existing roadmap implementation order");
        String priority = "## Current implementation order — 23 November 2026\n\n"
                + "**First: P0 — Claude Code symptom-only acceptance.** Do not start new debugger features until an actual no-skill agent baseline has been evaluated. The 23/23 installed Java E2E PASS result proves direct debugger scenarios, **not Claude's autonomous choices**.\n\n"
                + "| Order | Priority | Target / gate |\n|---|---|---|\n"
                + "| **1** | **P0** | **Claude symptom-only no-skill baseline:** JUnit, wrong Boot quote, handled HTTP 400; independently review tool calls, actual frames/values, diagnosis, consent and cleanup; add Java calculation and concurrent-service prompts as *planned* cases |\n"
                + "| 2 | P0 | Fixture synchronization: marker-verified, safe sync of dedicated Eclipse workspace; preserve unrelated files |\n"
                + "| 3 | P0 | Event-correlated pause waiting: correct JVM, thread, breakpoint reason, no stale/wrong stop |\n"
                + "| 4 | P0 | User-resource isolation: user-owned breakpoint/configuration preservation and concurrent launches |\n"
                + "| 5 | P1 | Run to Line: scoped debug operation, correct thread and cleanup |\n"
                + "| 6 | P1 | Change Variable Value: explicit permission, owned session and safe failure |\n"
                + "| 7 | P1 | Repeated Claude trials, skill activation and no-skill/with-skill comparisons |\n"
                + "| 8 | P1 | Other MCP clients including Codex: verify real interoperability |\n"
                + "| 9 | P2 | Thread dumps, local attach, method breakpoints and watchpoints: targeted feasibility |\n"
                + "| 10 | P2 | Spring Tools launch modes and expanded Spring-context JUnit coverage |\n"
                + "| 11 | P2 | Linux/macOS and remaining PowerShell harness parity |\n"
                + "| 12 | P3 | Remote/Docker attach and Hot Code Replace, subject to user demand and safety |\n\n"
                + "**Evidence policy:** Existing `claude` Java launcher captures transcript artifacts as `REVIEW_REQUIRED`, never automatic PASS. Start with an individual JUnit smoke trial, then the Boot quote and validation trials. Repeat on fresh sessions before estimating reliability. Keep direct 23/23 as the regression gate; capture exact Git revision, bundle, tools and reports.\n\n"
                + "**Selection of new features should follow observed agent failure modes**, rather than treating IDE GUI features as proven agent MCP support. See [evaluation plan](evaluation-plan.md) and [README](../README.md#next-development-priority-claude-symptom-only-acceptance).";
        dev = upsertAfterHeading(dev,"# Development Roadmap — Phases 0–5",priority);
        // Explicitly retire the stale current-action list while retaining the dated milestone history.
        dev = replaceSection(dev,"## Immediate implementation order", "## Immediate implementation order\n\n**Superseded by [Current implementation order](#current-implementation-order--23-november-2026) above.** The former list began with already completed Spring Boot feasibility; it is not an active work queue.\n\n[Detailed historical Codex tasks](codex-implementation-backlog.md) · [Feature matrix](feature-roadmap.md).", "[Detailed ordered Codex tasks](codex-implementation-backlog.md)");
        result.put(development,dev);

        String feature = result.get(features);
        feature = upsertAfterHeading(feature,"# Feature Roadmap and Decision Tracker",
                "**Current delivery priority:** [Claude symptom-only no-skill acceptance is P0 / Task 1](development-roadmap.md#current-implementation-order--23-november-2026). The direct installed-Eclipse Java baseline is **23/23 PASS** on 23 November 2026. This does not establish repeated autonomous Claude success. Next: safe fixture sync, event-correlated pause waiting, user-resource isolation; then Run to Line, variable mutation and skill comparison. **Feature IDs below remain stable and historical evidence is preserved.**");
        result.put(features,feature);

        String eval = result.get(evaluation);
        eval = upsertAfterHeading(eval,"# Regression and Unified Debugging Skill Evaluation Plan",
                "## Immediate P0 experiment — symptom-only Claude without skill\n\n"
                + "**Status: NOT YET VERIFIED as a repeated current baseline.** Run the existing opt-in `claude` trial group against the installed Eclipse test fixture **without installing/enabling the optional debugging skill**. The three implemented transcript trials are `junit`, `boot-quote` and `validation`. A plain-Java calculation trial and a concurrent-service safety trial are proposed additions, not currently implemented.\n\n"
                + "**Procedure:** one independent session per prompt, same fixture/source visibility, model, MCP configuration, approval policy and timeout. Preserve raw transcripts and inspect real `eclipse-debugger` calls, selected project/launch, breakpoint choice, thread/frame, runtime values, explanation and NORMAL cleanup. Score each `PASS`, `PARTIAL`, `FAIL`, `UNSAFE` or `NOT RUN`. A CLI success status or correct answer without runtime evidence is insufficient for an actual debugger PASS. Record unexpected state changes separately.\n\n"
                + "**Sequence:** smoke-test JUnit alone; then Boot quote and validation; review the actual traces; repeat fresh sessions before reporting rates. Only then conduct with-skill trials and compare like for like. The 23/23 direct tests remain a distinct prerequisite and do not count as Claude trials.\n\n"
                + "**CLI from repository root:** `java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude`, then `java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post`. These can consume Claude usage.\n");
        result.put(evaluation,eval);

        // Validate all outputs before writing any file.
        for (var entry : result.entrySet()) {
            require(entry.getValue().contains(OPEN) && entry.getValue().contains(CLOSE),"Missing priority markers: " + entry.getKey());
            require(entry.getValue().length() > 4000,"Unexpectedly shortened document: " + entry.getKey());
        }
        for (var entry : result.entrySet()) {
            Path file=entry.getKey();
            String current=Files.readString(file,StandardCharsets.UTF_8);
            if (current.equals(entry.getValue())) { System.out.println("UNCHANGED: " + root.relativize(file)); continue; }
            Path tmp=Files.createTempFile(file.getParent(),".claude-first-",".tmp");
            try {
                Files.writeString(tmp,entry.getValue(),StandardCharsets.UTF_8);
                Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
            System.out.println("UPDATED: " + root.relativize(file));
        }
        System.out.println("DONE: Claude symptom-only baseline is P0 / #1 in roadmap, feature tracker and evaluation plan.");
    }

    private static String upsertAfterHeading(String s,String title,String content) {
        String block = OPEN + "\n" + content + "\n" + CLOSE;
        int a=s.indexOf(OPEN), b=s.indexOf(CLOSE);
        if(a>=0 || b>=0) {
            require(a>=0 && b>a && s.indexOf(OPEN,a+OPEN.length())<0,"Corrupt priority markers");
            return s.substring(0,a)+block+s.substring(b+CLOSE.length());
        }
        int i=s.indexOf(title+"\n");
        require(i>=0,"Missing title: "+title);
        i+=title.length()+1;
        return s.substring(0,i)+"\n"+block+"\n"+s.substring(i);
    }
    private static String replaceSection(String s,String heading,String replacement,String endMarker) {
        int a=s.indexOf(heading+"\n");
        require(a>=0,"Missing section: "+heading);
        int b=s.indexOf(endMarker,a);
        if (b<0) {
            // A second execution recognizes the rewritten section and makes no change.
            require(s.substring(a).contains("**Superseded by [Current implementation order]"),"Cannot locate expected section end");
            return s;
        }
        int e=s.indexOf('\n',b);
        e=e<0?s.length():e+1;
        return s.substring(0,a)+replacement+"\n"+s.substring(e);
    }
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
