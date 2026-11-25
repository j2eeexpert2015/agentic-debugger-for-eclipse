package org.learningfromexperience.agentdebug.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.learningfromexperience.agentdebug.testing.grading.ClaudeTranscriptEvaluator;
import org.learningfromexperience.agentdebug.testing.trial.ClaudeSymptomTrial;

/**
 * Real Claude Code debugs a planted bug with this tool and the skill, from the user's prompt to its final answer, and
 * is graded automatically from what the debugger actually returned (right value found, cleanup, report path). A test
 * passes when the verdict is PASS. Uses Claude usage, so the tests run only with {@code -Dclaude.approved=true}; the
 * two HTTP tests also need {@code -Dfixture.post.approved=true}. Never part of a suite.
 *
 * <p>Options: {@code -Dclaude.model=haiku} (default: the Claude CLI's model), {@code -Dclaude.report=true} (also write
 * the debugging session report), {@code -Dclaude.skill=false} (Claude may not use the skill, for comparison).
 * Transcripts, verdicts and reports go to {@code evaluation/reports/claude-trials}; the files keep the trials' short
 * names (boot-quote, junit, validation). The pass-rate and cost summary of this run is printed at the end.
 *
 * @author Ayan Dutta
 */
@DisplayName("Claude agent end to end: real Claude Code debugs a planted bug")
@EnabledIfSystemProperty(named = "claude.approved", matches = "true",
        disabledReason = "Uses Claude usage: add -Dclaude.approved=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ClaudeAgentEndToEndTests {
    private TestEnvironment env;
    private String batch;
    private boolean skill;
    private int runs;

    @BeforeAll
    void prepare() throws Exception {
        env = TestEnvironment.get();
        skill = !"false".equals(TestEnvironment.option("claude.skill"));
        String model = TestEnvironment.option("claude.model");
        batch = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + (skill ? "-skill" : "-noskill") + (model == null ? "" : "-" + model);
        // The trial uses the skill of this repository (the test Eclipse does not install it, OPEN-32).
        if (skill) syncSkill(env.root.resolve("skills/eclipse-debugger"));
    }

    @Test
    @DisplayName("Spring Boot: Claude finds the wrong quote value (120.00 instead of 108.00)")
    void springBootWrongQuote() throws Exception {
        trial("boot-quote", true);
    }

    @Test
    @DisplayName("JUnit: Claude debugs the failing discount test")
    void junitWrongDiscount() throws Exception {
        trial("junit", false);
    }

    @Test
    @DisplayName("Spring Boot: Claude finds where the HTTP 400 for an invalid quantity starts")
    void springBootValidation400() throws Exception {
        trial("validation", true);
    }

    @AfterAll
    void summary() throws Exception {
        if (runs > 0) ClaudeTranscriptEvaluator.main(new String[] {"--summary", "--batch", batch,
                env.root.resolve("evaluation/reports/claude-trials").toString()});
    }

    private void trial(String scenario, boolean http) throws Exception {
        boolean post = TestEnvironment.approved("fixture.post.approved");
        assumeTrue(!http || post, "Sends an HTTP request to the local test application: add -Dfixture.post.approved=true");
        runs++;
        Map<String, Object> verdict = ClaudeSymptomTrial.run(env.root, scenario,
                env.root.resolve("evaluation/reports/claude-trials"), post, !skill,
                "true".equals(TestEnvironment.option("claude.report")), TestEnvironment.option("claude.model"), batch);
        assertNotNull(verdict, "Claude produced no transcript");
        assertEquals("PASS", verdict.get("verdict"), ClaudeTranscriptEvaluator.summary(verdict));
    }

    /**
     * Makes %USERPROFILE%\.claude\skills\eclipse-debugger the same as skills/eclipse-debugger of this repository
     * (a normal Eclipse start does the same from the installed plugin). A file that differs from what was installed
     * last (recorded in .eclipse-agentdebug\skill-installed.properties, the plugin's record) is kept as
     * name.bak-time first. Other files in the skill folder are not touched.
     */
    private static void syncSkill(Path source) throws IOException {
        if (!Files.isDirectory(source)) return;
        Path home = Path.of(System.getProperty("user.home"));
        Path target = home.resolve(".claude/skills/eclipse-debugger");
        Path stateFile = home.resolve(".eclipse-agentdebug/skill-installed.properties");
        Properties state = new Properties();
        if (Files.isRegularFile(stateFile)) try (InputStream in = Files.newInputStream(stateFile)) { state.load(in); }
        List<Path> files;
        try (var walk = Files.walk(source)) { files = walk.filter(Files::isRegularFile).sorted().toList(); }
        boolean changed = false;
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        for (Path file : files) {
            String name = source.relativize(file).toString().replace('\\', '/');
            byte[] content = Files.readAllBytes(file);
            String sha = sha256(content);
            Path installed = target.resolve(name);
            if (Files.isRegularFile(installed)) {
                String current = sha256(Files.readAllBytes(installed));
                if (current.equals(sha)) {
                    if (!sha.equals(state.getProperty(name))) { state.setProperty(name, sha); changed = true; }
                    continue;
                }
                if (!current.equals(state.getProperty(name))) {
                    Path backup = installed.resolveSibling(installed.getFileName() + ".bak-" + stamp);
                    Files.copy(installed, backup, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Skill: kept the previous " + name + " as " + backup.getFileName());
                }
            }
            Files.createDirectories(installed.getParent());
            Files.write(installed, content);
            state.setProperty(name, sha);
            changed = true;
            System.out.println("Skill updated from the repository: " + installed);
        }
        if (changed) {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream out = Files.newOutputStream(stateFile)) {
                state.store(out, "Agentic Debugger: skill files as last written (SHA-256)");
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
