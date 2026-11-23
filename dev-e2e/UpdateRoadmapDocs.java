import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synchronize the verified November 23 23-scenario direct-E2E documentation status without replacing
 * historical roadmap documents, test records or manually maintained prose.
 * Run from the repository root: java dev-e2e/UpdateRoadmapDocs.java
 * Idempotent. No network, Python, PowerShell, source-code changes or deletions.
 */
public final class UpdateRoadmapDocs {
    private static final String OPEN = "<!-- JAVA-E2E-19-STATUS:BEGIN -->";
    private static final String CLOSE = "<!-- JAVA-E2E-19-STATUS:END -->";
    private static final String[] DOCS = {
        "docs/capabilities.md", "docs/development-roadmap.md", "docs/feature-roadmap.md",
        "docs/phase2-verification-consolidation.md", "docs/spring-boot-debugging-scenarios.md",
        
    };
    private UpdateRoadmapDocs() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Run from repository root: java dev-e2e/UpdateRoadmapDocs.java");
        Path root = Path.of("").toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve("pom.xml")) && Files.isRegularFile(root.resolve("README.md")),
            "Not the Eclipse debugger repository root: " + root);
        Map<Path,String> desired = new LinkedHashMap<>();
        for (String name : DOCS) {
            Path file = root.resolve(name);
            require(Files.isRegularFile(file), "Required existing documentation not found: " + name);
            String original = Files.readString(file, StandardCharsets.UTF_8);
            require(original.startsWith("# "), "Unexpected document heading; no files modified: " + name);
            String updated = upsertBanner(original, banner(name));
            if (name.equals("docs/capabilities.md")) updated = correctCapabilities(updated);
            if (name.equals("docs/feature-roadmap.md")) updated = correctFeatureRoadmap(updated);
            if (name.equals("docs/development-roadmap.md")) updated = correctDevelopmentRoadmap(updated);
            desired.put(file,updated);
        }
        // All prerequisites and document structure validated before modifying any file.
        for (var entry : desired.entrySet()) {
            Path file=entry.getKey();
            String previous=Files.readString(file,StandardCharsets.UTF_8);
            if (previous.equals(entry.getValue())) { System.out.println("UNCHANGED: " + root.relativize(file)); continue; }
            Path temporary=Files.createTempFile(file.getParent(), ".java-e2e-docs-", ".tmp");
            try {
                Files.writeString(temporary,entry.getValue(), StandardCharsets.UTF_8);
                Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            System.out.println("UPDATED: " + root.relativize(file));
        }
        System.out.println("23-scenario documentation status synchronized. No Markdown files deleted.");
        System.out.println("Review: git diff -- README.md docs/");
    }

    private static String banner(String name) {
        return "**Latest installed-Eclipse result (23 November 2026): 23/23 direct Java E2E scenarios PASS "
            + "in one combined Windows run, with successful cleanup for each scenario.** "
            + "Groups: Phase 1 Java/JUnit (7), Phase 2 validation/ownership (2), Boot discovery/readiness (2), "
            + "HTTP quote defect (1), core acceptance (4), Boot failure/isolation acceptance (7). "
            + "Dedicated startup-failure, readiness-timeout and port-conflict tests passed; "
            + "startup and port failures were verified using actual JVM PIDs and OS process exit evidence. "
            + "The combined result was reported from a local working tree, not a separately verified "
            + "published release. Autonomous Claude, skill activation/comparison, STS-specific launch "
            + "modes, Linux/macOS and full user-owned marker/configuration preservation remain OPEN. "
            + "**Current implementation, exact runner commands and prioritized next steps:** "
            + "[root README](../README.md#next-priorities). "
            + "Older status tables below describe their dated milestones and must not be interpreted "
            + "as the latest 23-scenario direct-test status.";
    }
    private static String upsertBanner(String content,String message) {
        String block=OPEN+"\n"+message+"\n"+CLOSE;
        int begin=content.indexOf(OPEN), end=content.indexOf(CLOSE);
        if (begin>=0 || end>=0) {
            require(begin>=0 && end>begin && content.indexOf(OPEN,begin+OPEN.length())<0,
                "Corrupt or repeated Java E2E status markers");
            return content.substring(0,begin)+block+content.substring(end+CLOSE.length());
        }
        int nl=content.indexOf('\n');
        require(nl>0,"Missing first heading");
        return content.substring(0,nl+1)+"\n"+block+"\n\n"+content.substring(nl+1);
    }
    private static String correctCapabilities(String s) {
        s=s.replace("**The optional unified debugging skill is not implemented and is not necessary for debugger operations.**",
            "**The optional debugging skill files exist, but full activation/comparative acceptance remains incomplete; core debugger operations do not depend on a skill.**");
        s=s.replace("| CORE-12 | Optional agent debugging skill | **Not implemented**; Phase 3 work, no core dependency |",
            "| CORE-12 | Optional agent debugging skill | Skill source exists; live activation/comparative acceptance incomplete; core debugger independent |" );
        s=s.replace("| Unified Java/JUnit/Spring Boot Claude skill (`SKILL-01`–`SKILL-03`) | Planned; not implemented |",
            "| Unified Java/JUnit/Spring Boot Claude skill (`SKILL-01`–`SKILL-03`) | Source exists; activation and controlled evaluation incomplete |" );
        s=s.replace("| Step Into | **Implemented; separate installed acceptance not recorded** |",
            "| Step Into | **Dedicated Java E2E PASS (23 Nov 2026; `core-step-into-out`)** |" );
        s=s.replace("| Step Out | **Implemented; separate installed acceptance not recorded** |",
            "| Step Out | **Dedicated Java E2E PASS (23 Nov 2026; `core-step-into-out`)** |" );
        s=s.replace("| CORE-05-03 | Step Into | **Implemented; dedicated test pending** |",
            "| CORE-05-03 | Step Into | **Dedicated Java E2E PASS (23 Nov 2026)** |" );
        s=s.replace("| CORE-05-04 | Step Out | **Implemented; dedicated test pending** |",
            "| CORE-05-04 | Step Out | **Dedicated Java E2E PASS (23 Nov 2026)** |" );
        s=s.replace("| CORE-05 | Pause, resume, Step Into, Step Over and Step Out | Resume and Step Over have recorded installed evidence; Step Into and Step Out are implemented but require independently recorded step-result assertions |",
            "| CORE-05 | Pause, resume, Step Into, Step Over and Step Out | Dedicated Java E2E Step Into/Out passed on 23 Nov 2026; other cross-context negative coverage remains open |" );
        return s;
    }
    private static String correctFeatureRoadmap(String s) {
        s=s.replace("| CORE-05-03 | Step Into | Implemented; standalone acceptance pending |",
            "| CORE-05-03 | Step Into | Dedicated Java E2E PASS (23 Nov 2026) |" );
        s=s.replace("| CORE-05-04 | Step Out | Implemented; standalone acceptance pending |",
            "| CORE-05-04 | Step Out | Dedicated Java E2E PASS (23 Nov 2026) |" );
        s=s.replace("| SKILL-01 | Appropriate selection of runtime investigation versus static-only explanation; choose Java/JUnit/Boot context | Candidate; skill absent |",
            "| SKILL-01 | Appropriate selection of runtime investigation versus static-only explanation; choose Java/JUnit/Boot context | Skill source exists; activation/agent acceptance incomplete |" );
        s=s.replace("| SKILL-02 | Hypothesis, targeted stops, first-bad-value tracing, efficient tools, bounded observations | Candidate; skill absent |",
            "| SKILL-02 | Hypothesis, targeted stops, first-bad-value tracing, efficient tools, bounded observations | Skill source exists; controlled comparison incomplete |" );
        s=s.replace("| SKILL-03 | Error handling, correct target/session, explicit consent and investigation finish | Candidate; skill absent |",
            "| SKILL-03 | Error handling, correct target/session, explicit consent and investigation finish | Skill source exists; full behavior acceptance incomplete |" );
        s=s.replace("Keep Step Into and Step Out as **supported**, not as separately tested operations until a recorded installed run proves the outcomes.",
            "Step Into and Step Out now have a dedicated installed Java E2E assertion (caller `main()` to callee `sum()` and return); cross-context tests remain open.");
        s=s.replace("Spring Boot startup-failure and readiness negatives remain pending", 
            "Spring Boot startup-failure and readiness negatives passed in the 23-scenario direct test; agent-level cases remain pending");
        return s;
    }
    private static String correctDevelopmentRoadmap(String s) {
        s=s.replace("**skill not implemented**","skill files exist; activation/comparison incomplete");
        s=s.replace("**Not implemented yet.**","**Skill source exists; activation, cross-context and comparative acceptance incomplete.**");
        return s;
    }
    private static void require(boolean pass,String message) {
        if (!pass) throw new IllegalStateException(message);
    }
}
