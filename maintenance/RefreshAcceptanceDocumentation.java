import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One-time, documentation-only updater. Dry-run by default; preserves historical sections.
 * Run from the repository root: java maintenance/RefreshAcceptanceDocumentation.java [--apply]
 */
public final class RefreshAcceptanceDocumentation {
    private record Change(Path path, String before, String after) { }
    private static final Map<String,Change> changes = new LinkedHashMap<>();
    private static Path root;
    private static String read(String path) throws Exception {
        Path p = root.resolve(path);
        if (!Files.isRegularFile(p)) throw new IllegalStateException("Missing repository file: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
    private static void change(String path, java.util.function.UnaryOperator<String> update) throws Exception {
        Change staged = changes.get(path);
        String before = staged == null ? read(path) : staged.after();
        String after = update.apply(before);
        if (before.equals(after)) System.out.println("UNCHANGED " + path);
        else {
            changes.put(path, new Change(root.resolve(path),staged == null ? before : staged.before(),after));
            System.out.println("WOULD UPDATE " + path);
        }
    }
    private static String section(String src, String begin, String end, String newContent) {
        int first = src.indexOf(begin);
        if (first < 0 || src.indexOf(begin, first+begin.length()) >= 0) throw new IllegalStateException("Missing/duplicate marker: " + begin);
        int last = src.indexOf(end, first+begin.length());
        if (last < 0) throw new IllegalStateException("Missing end marker: " + end);
        return src.substring(0, first+begin.length())+"\n"+newContent.strip()+"\n"+src.substring(last);
    }
    private static String uniqueReplace(String src, String old, String current) {
        if (src.contains(current)) return src;
        int i=src.indexOf(old);
        if (i<0 || src.indexOf(old,i+old.length())>=0) throw new IllegalStateException("Missing/duplicate exact target: "+old.substring(0,Math.min(90,old.length())));
        return src.substring(0,i)+current+src.substring(i+old.length());
    }
    private static String insertBefore(String src, String anchor, String content, String marker) {
        if (src.contains(marker)) return src;
        int i=src.indexOf(anchor);
        if (i<0 || src.indexOf(anchor,i+anchor.length())>=0) throw new IllegalStateException("Missing/duplicate insert anchor: "+anchor);
        return src.substring(0,i)+content+"\n\n"+src.substring(i);
    }
    public static void main(String[] args) throws Exception {
        if (args.length>1 || (args.length==1 && !args[0].equals("--apply")))
            throw new IllegalArgumentException("Usage: java maintenance/RefreshAcceptanceDocumentation.java [--apply]");
        boolean apply=args.length==1;
        root=Path.of(System.getProperty("user.dir")).toRealPath();
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isRegularFile(root.resolve("dev-e2e/DevE2E.java")))
            throw new IllegalStateException("Run this Java source from the Agentic Debugger repository root");
        for (String p:List.of("docs/claude-acceptance-2026-11-23.md","docs/repository-hygiene.md"))
            if (!Files.isRegularFile(root.resolve(p))) throw new IllegalStateException("Extract the complete documentation ZIP first: "+p);

        change("README.md",s->{
            s=uniqueReplace(s,
                "**Verified baseline — 23 November 2026:** **23/23 direct Java E2E scenarios PASS** in one Windows Eclipse execution, all with `cleanup=true`. These are real installed-debugger checks, **not** evidence that Claude independently chooses the right debugging strategy. See [Implementation and test status](#implementation-and-test-status).",
                "**Evidence snapshot — 23 November 2026:** A historical single-run **23/23 direct Java E2E PASS** baseline exists. Following the fixture HTTP MCP change, the first 16 direct cases passed in a combined run, the Boot-gap group later passed **7/7** separately, but a fresh uninterrupted 23/23 pass on that exact updated build remains pending. Three single-session Claude investigations were reviewed (JUnit diagnosis; Boot quote and HTTP 400 event-level runtime PASS). See [acceptance evidence](docs/claude-acceptance-2026-11-23.md); do not infer repeated reliability or skill A/B success.");
            if (!s.contains("## Current milestone and next development priority")) s=section(s,"## Next development priority: Claude symptom-only acceptance","## Install and use", """
                ## Current milestone and next development priority

                **P0 single-trial symptom-driven acceptance is demonstrated** for the three implemented scenarios: one JUnit investigation (reported diagnostic evidence) and two Spring Boot HTTP investigations (event-level MCP evidence). See [Claude acceptance results, evidence boundaries and exact commands](docs/claude-acceptance-2026-11-23.md). The optional skill's activation, repeated no-skill reliability and with-skill A/B benefit have **not** been established.

                **Next P0 gate:** automate evidence-backed verdicts and independent post-cleanup checks; rerun the updated 23-case suite together. Then prioritize safe fixture synchronization, event-correlated pause waiting and user-owned resource isolation. Do not add debugger features merely for checklist parity.

                """);
            s=uniqueReplace(s,
                "**Agent behavior:** Selected earlier explicit and symptom-only Claude trials are documented, but **a repeated baseline covering the current Boot/JUnit scenarios, skill activation reliability, and a controlled no-skill/with-skill comparison remain open**. The opt-in `claude` trial runner captures transcripts as `REVIEW_REQUIRED`; it does **not** certify agent success.",
                "**Agent behavior (23 November 2026):** The JUnit single-method diagnosis was supported by Claude's final-result record; the Spring Boot wrong-quote and invalid-quantity runs have reviewed MCP event-level proof of HTTP reproduction, runtime debugging and cleanup responses. **Three single trials are not a reliability study.** Skill activation, repeated no-skill trials, controlled no-skill/with-skill comparison, and independent post-cleanup inventories remain open. The harness intentionally labels captures `REVIEW_REQUIRED`; see [evidence ledger](docs/claude-acceptance-2026-11-23.md).");
            s=uniqueReplace(s,
                "| **1** | **P0** | **Claude symptom-only acceptance:** repeatable no-skill trials, actual MCP evidence, safe cleanup, individually reviewed results |",
                "| **1** | **P0** | **Automated Claude acceptance verdicts and independent cleanup evidence:** preserve event-level MCP observations; revalidate full combined regression; repeat controlled no-skill trials (initial JUnit/Boot smoke completed) |");
            s=uniqueReplace(s,"[Development roadmap](docs/development-roadmap.md) ·", "[Claude acceptance evidence](docs/claude-acceptance-2026-11-23.md) · [Repository hygiene](docs/repository-hygiene.md) · [Development roadmap](docs/development-roadmap.md) ·");
            return s;
        });

        change("dev-e2e/README.md",s->{
            String content="""
                ## Reviewed Claude acceptance — 23 November 2026

                **Three individual runs were reviewed:** `junit` (successful diagnosis in Claude final-result JSON), `boot-quote` (event-level PASS for request/thread/`rate=0`/HTTP 200/cleanup response), and `validation` (event-level PASS for invalid quantity, application exception, HTTP 400 and cleanup response). These are **single trials, not a reliability percentage**. The optional skill was not independently confirmed disabled or enabled; a controlled A/B evaluation is still pending. The CLI still emits `REVIEW_REQUIRED` for completed captures, not an automatic verdict.

                For HTTP Claude trials, the runner uses the fresh repository MCP shaded JAR, an isolated test MCP configuration, test-only `start_fixture_http_request`/`get_fixture_http_request`/`cancel_fixture_http_request` tools and explicit `--approve-fixture-post`. The installed user-scoped MCP JAR can be a different revision; E2E does not update global registration. HTTP is asynchronous because request threads may suspend at breakpoints. Arbitrary URLs/production systems are not supported by these fixture tools. Keep original JSONL/manifest outside Git (ignored report directory).

                The updated installation passed all 23 direct scenarios **across separate runs**, but the most recent uninterrupted combined suite encountered six transient Boot-gap timeouts before later 7/7 isolated group success. Reconfirm a single full regression before release. [Detailed acceptance ledger and caveats](../docs/claude-acceptance-2026-11-23.md).
                """;
            return insertBefore(s,"## Retained test infrastructure",content,"## Reviewed Claude acceptance — 23 November 2026");
        });

        String status="""
            **Current evidence (23 November 2026):** The initial Claude symptom-driven trial set has been reviewed: single JUnit diagnosis (final-result record) and two Spring Boot HTTP investigations (event-level runtime PASS). A historical uninterrupted 23/23 direct JDT regression passed; after the fixture HTTP change, first 16 direct scenarios passed together and all seven Boot-gap scenarios later passed separately, but the newest build has **not** completed a fresh uninterrupted 23/23 run. The opt-in Claude harness still reports `REVIEW_REQUIRED`, not automatic acceptance. Repeated no-skill reliability, optional skill A/B, independent cleanup inventories, STS-specific launch modes, and cross-platform coverage remain open. [Current acceptance evidence and boundaries](claude-acceptance-2026-11-23.md). The dated sections below are historical snapshots; their old `NOT RUN` cells refer to the time they were written, not current overall status. See [README](../README.md).
            """;
        for (String path:List.of("docs/development-roadmap.md","docs/feature-roadmap.md","docs/capabilities.md","docs/phase2-verification-consolidation.md","docs/spring-boot-debugging-scenarios.md")) {
            change(path,s->section(s,"<!-- JAVA-E2E-19-STATUS:BEGIN -->","<!-- JAVA-E2E-19-STATUS:END -->",status));
        }
        change("docs/development-roadmap.md",s->section(s,"<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->","<!-- CLAUDE-FIRST-PRIORITY:END -->","""
            ## Current implementation order — updated 23 November 2026

            **P0 single-run Claude acceptance is completed for JUnit, wrong Boot quote and HTTP 400**, with two event-level HTTP traces. It is **not** a repeated baseline or a verified optional skill comparison. See [reviewed acceptance evidence](claude-acceptance-2026-11-23.md).

            | Order | Priority | Target / evidence gate |
            |---|---|---|
            | **1** | **P0** | **Automated transcript grading and independent post-cleanup evidence**, plus an uninterrupted updated 23/23 regression |
            | 2 | P0 | Safe fixture synchronization into marker-verified disposable workspace |
            | 3 | P0 | Event-correlated debugger stop: exact launch/thread/reason, no stale matches |
            | 4 | P0 | Full user-resource isolation under concurrent sessions, markers and configurations |
            | 5 | P1 | Repeat controlled no-skill Claude trials, then optional skill activation and A/B comparison |
            | 6 | P1 | Run to Line and consent-gated Change Variable Value, each with negative cases |
            | 7 | P1 | Interoperability testing for Codex and other MCP clients |
            | 8 | P2 | Spring Tools, expanded Spring/JUnit and cross-platform testing |
            | 9 | P2 | Thread dumps, local attach, watchpoints and related feasibility |
            | 10 | P3 | Remote/Docker attach and Hot Code Replace after demand/safety review |

            **Status policy:** a CLI exit code and `REVIEW_REQUIRED` do not imply acceptance. Preserve raw MCP responses, exact JVM/thread/frame/locals, HTTP response, and ownership-safe cleanup. One successful trial is not a success rate. Historical phase plans below remain dated records.
            """));
        change("docs/feature-roadmap.md",s->section(s,"<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->","<!-- CLAUDE-FIRST-PRIORITY:END -->","""
            **Current delivery priority (23 November 2026):** Three initial symptom-driven Claude investigations were reviewed; Boot wrong-quote and HTTP validation have event-level MCP evidence, while JUnit has final-result evidence. **Next P0**: automated verdicts, independent cleanup verification and repeatable reliability tests; then fixture synchronization, event-correlated pause and full isolation. No controlled skill A/B exists yet. [Evidence ledger](claude-acceptance-2026-11-23.md) · [Development roadmap](development-roadmap.md).
            """));
        change("docs/evaluation-plan.md",s->section(s,"<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->","<!-- CLAUDE-FIRST-PRIORITY:END -->","""
            ## Current P0 evaluation — first three Claude smoke trials reviewed

            **23 November 2026 outcome:** `junit` succeeded by the captured Claude final-result account; `boot-quote` and `validation` passed event-level MCP review of owned JVM launch, asynchronous fixture request, suspended HTTP thread, application values, actual HTTP result and cleanup response. These are **one run each**, not a statistical or skills comparison baseline. The optional skill was not independently verified as absent/present in a controlled experiment. [Exact evidence ledger](claude-acceptance-2026-11-23.md).

            **Next experiment:** Implement a Java event-transcript evaluator; check actual MCP responses and post-cleanup session/marker inventories. Repeat each trial on fresh isolated sessions with controlled model, permissions, fixture revision and skill state; only then report rates and compare optional skill A/B. Add plain Java and concurrent-service agent trials separately. Record both direct regression and agent evidence independently; the updated combined 23-case regression needs rerun because a six-case transient timeout occurred before later isolated Boot-gap PASS.

            **CLI (from repository root):** `java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude` or `java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post`. Use only disposable fixture resources; Claude usage may be charged. `REVIEW_REQUIRED` is a capture status, not an automatic PASS.
            """));
        change("README-INSTALL-AND-TEST.md",s->uniqueReplace(s,
            "A working local request may need an independent authorized client (our MCP bridge has no general HTTP request tool).",
            "For dedicated, explicitly approved E2E fixture trials, the Java MCP server now offers constrained asynchronous `start_fixture_http_request`, `get_fixture_http_request`, and `cancel_fixture_http_request`. These tools are NOT enabled for normal use and do not provide arbitrary HTTP requests; a non-E2E debugging session still needs an independently approved client."));
        change(".gitignore",s->s.contains("# Captured single-run Eclipse diagnostics")?s:s.stripTrailing()+"\n\n# Captured single-run Eclipse diagnostics should stay outside version control\n/eclipse-bridge-threads.txt\n/eclipse-errors.txt\n");

        if(!apply) {System.out.println("Dry run complete: "+changes.size()+" files would change. Apply: java maintenance/RefreshAcceptanceDocumentation.java --apply");return;}
        if(changes.isEmpty()) {System.out.println("PASS: already up to date");return;}
        Path backupRoot=root.resolve("evaluation/reports/acceptance-doc-backups/"+Instant.now().toString().replace(':','-'));
        for (Change c:changes.values()) {Path rel=root.relativize(c.path());Path dst=backupRoot.resolve(rel);Files.createDirectories(dst.getParent());Files.writeString(dst,c.before(),StandardCharsets.UTF_8);}
        for(Change c:changes.values()){Path tmp=Files.createTempFile(c.path().getParent(),"doc-refresh-",".tmp");try{Files.writeString(tmp,c.after(),StandardCharsets.UTF_8);Files.move(tmp,c.path(),StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}System.out.println("UPDATED "+root.relativize(c.path()));}
        System.out.println("PASS: updated "+changes.size()+" documentation files. Backups: "+backupRoot);
        System.out.println("Review: git diff --check && git diff --stat; optional deletion instructions: docs/repository-hygiene.md");
    }
}
