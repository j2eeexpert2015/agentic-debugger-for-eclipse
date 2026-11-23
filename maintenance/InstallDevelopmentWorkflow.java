import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Arrays;

/**
 * Adds one short Development Workflow section and doc links to README.md.
 * Dry-run by default. Idempotent, narrow, and independent of prior README refreshes.
 * This is a one-time docs-only integration utility; it does not invoke Maven/Claude/Eclipse.
 */
public final class InstallDevelopmentWorkflow {
    private static final String HEADING = "## Development workflow";
    private static final String SECTION = """
            ## Development workflow

            Changes follow an **evidence-first, reviewable process**: reproduce and isolate the issue; prepare a minimal, reversible change (with dry-run/backups for source migrations); compile and test the affected scenario; run broader regression; independently review Claude MCP evidence when applicable; then update verified status, documentation and repository hygiene. **Do not treat `REVIEW_REQUIRED` as a PASS or run paid Claude trials without approval.**

            See the [complete development workflow](docs/development-workflow.md) and [coding-agent instructions](AGENTS.md). All agents and contributors should read these before modifying the project.

            """;
    private static final String LINK = "[Development workflow](docs/development-workflow.md)";
    private static final String AGENT_LINK = "[Coding-agent instructions](AGENTS.md)";
    private static final String DOC_HEADER = "## Documentation";
    private InstallDevelopmentWorkflow() { }

    public static void main(String[] args) throws Exception {
        boolean apply = false;
        if (args.length == 1 && "--apply".equals(args[0])) apply = true;
        else if (args.length != 0) throw new IllegalArgumentException("Usage: java maintenance/InstallDevelopmentWorkflow.java [--apply]");
        Path root = Path.of(System.getProperty("user.dir")).toRealPath();
        Path readme = root.resolve("README.md");
        Path workflow = root.resolve("docs/development-workflow.md");
        Path agents = root.resolve("AGENTS.md");
        require(Files.isRegularFile(root.resolve("pom.xml")) && Files.isRegularFile(root.resolve("dev-e2e/DevE2E.java")), "Not an Agentic Debugger repository root");
        require(Files.isRegularFile(workflow) && Files.isRegularFile(agents), "Extract docs/development-workflow.md and AGENTS.md before applying");
        String source = Files.readString(readme, StandardCharsets.UTF_8);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        String normalized = source.replace("\r\n", "\n");
        require(normalized.startsWith("# Agentic Debugger for Eclipse IDE"), "Unexpected README title; refusing change");
        require(count(normalized, DOC_HEADER) == 1, "Expected exactly one '## Documentation' heading");
        boolean sectionPresent = normalized.contains(HEADING);
        require(!sectionPresent || count(normalized, HEADING) == 1, "Ambiguous Development workflow heading");
        if (!sectionPresent) {
            // Append a complete section immediately before the documented link index, without touching other sections.
            normalized = normalized.replace(DOC_HEADER, SECTION + DOC_HEADER);
        }
        // The Documentation link index is assumed to be its own trailing section.
        int index = normalized.indexOf(DOC_HEADER);
        String before = normalized.substring(0, index);
        String tail = normalized.substring(index);
        String[] lines = tail.split("\n", -1);
        int linkLine = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].contains("[Development roadmap](") || lines[i].contains("[Feature tracker](")) { linkLine = i; break; }
        }
        require(linkLine >= 0, "README documentation link index was not found; refusing change");
        if (!tail.contains(LINK)) lines[linkLine] = LINK + " · " + lines[linkLine];
        if (!tail.contains(AGENT_LINK)) lines[linkLine] = lines[linkLine] + " · " + AGENT_LINK;
        String updated = before + String.join("\n", lines);
        updated = updated.replace("\n", eol);
        if (updated.equals(source)) {
            System.out.println("UNCHANGED: README already contains workflow section and both documentation links");
            return;
        }
        if (!apply) {
            System.out.println("WOULD UPDATE: README.md (one workflow section and two documentation links)");
            System.out.println("Dry run complete. Run java maintenance/InstallDevelopmentWorkflow.java --apply");
            return;
        }
        Path backupDir = root.resolve("evaluation/reports/development-workflow-backups");
        Files.createDirectories(backupDir);
        Path backup = backupDir.resolve("README-" + Instant.now().toEpochMilli() + ".md");
        Files.copy(readme, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Path tmp = Files.createTempFile(root, "README-workflow-", ".tmp");
        try {
            Files.writeString(tmp, updated, StandardCharsets.UTF_8);
            try { Files.move(tmp, readme, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, readme, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
        System.out.println("UPDATED: README.md");
        System.out.println("BACKUP: " + backup);
        System.out.println("Review: git diff --check && git diff -- README.md && git status --short");
    }
    private static void require(boolean condition, String error) { if (!condition) throw new IllegalStateException(error); }
    private static int count(String source, String target) {
        int n = 0, pos = 0;
        while ((pos = source.indexOf(target, pos)) >= 0) { n++; pos += target.length(); }
        return n;
    }
}
