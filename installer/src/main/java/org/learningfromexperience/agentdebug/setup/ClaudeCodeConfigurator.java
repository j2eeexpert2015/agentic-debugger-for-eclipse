package org.learningfromexperience.agentdebug.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Uses the supported Claude Code CLI; never rewrites ~/.claude.json directly. */
final class ClaudeCodeConfigurator {
    static final String SERVER_NAME = "eclipse-debugger";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private final Path cli;

    ClaudeCodeConfigurator(Map<String, String> environment, String osName) {
        this.cli = locateClaude(environment, osName);
    }

    private static Path locateClaude(Map<String, String> env, String osName) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        String path = env.getOrDefault("PATH", "");
        String separator = windows ? ";" : ":";
        String[] names = windows ? new String[] {"claude.exe", "claude.cmd", "claude.bat"} : new String[] {"claude"};
        for (String folder : path.split(java.util.regex.Pattern.quote(separator))) {
            if (folder.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(folder).resolve(name);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Claude Code CLI was not found on PATH. Install Claude Code, then rerun setup.");
    }

    boolean registrationExists() throws IOException, InterruptedException {
        return run("mcp", "get", SERVER_NAME).exitCode() == 0;
    }

    void remove() throws IOException, InterruptedException {
        Result result = run("mcp", "remove", SERVER_NAME, "--scope", "user");
        if (result.exitCode() != 0) throw new IOException("Claude Code could not remove the user-scoped registration: " + result.output());
    }

    void register(Path javaBinary, Path serverJar) throws IOException, InterruptedException {
        Result result = run("mcp", "add", "--transport", "stdio", "--scope", "user", SERVER_NAME,
                "--", javaBinary.toString(), "-jar", serverJar.toString());
        if (result.exitCode() != 0) throw new IOException("Claude Code registration failed: " + result.output());
    }

    Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(cli.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Claude Code command timed out.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Do not echo command lines: registered arguments may contain user-specific paths.
        return new Result(process.exitValue(), output.strip());
    }

    record Result(int exitCode, String output) { }
}
