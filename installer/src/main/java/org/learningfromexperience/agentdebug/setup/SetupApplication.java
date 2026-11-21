package org.learningfromexperience.agentdebug.setup;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** CLI entry point. No shell scripts, configuration-file edits or manually entered paths.
 *
 * @author Ayan Dutta
 */
public final class SetupApplication {
    private SetupApplication() { }

    public static void main(String[] args) {
        try {
            String command = args.length == 0 ? "install" : args[0];
            if (!Arrays.asList("install", "check", "uninstall").contains(command)) {
                usage();
                System.exit(2);
            }
            Path base = InstallPaths.appDirectory(
                    System.getProperty("os.name"), System.getProperty("user.home"), System.getenv());
            McpServerInstaller installer = new McpServerInstaller(base);
            ClaudeCodeConfigurator claude = new ClaudeCodeConfigurator(System.getenv(), System.getProperty("os.name"));
            switch (command) {
                case "install" -> install(installer, claude, Arrays.asList(args).contains("--replace"));
                case "check" -> check(installer, claude);
                case "uninstall" -> uninstall(installer, claude);
                default -> throw new IllegalArgumentException(command);
            }
        } catch (Exception error) {
            System.err.println("Setup failed: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void install(McpServerInstaller installer, ClaudeCodeConfigurator claude, boolean replace)
            throws IOException, InterruptedException {
        Path java = new JavaRuntimeDetector().currentJava();
        installer.verifyPayloadExists();
        boolean exists = claude.registrationExists();
        if (exists && !replace && !confirmReplacement()) {
            throw new IllegalStateException("Existing 'eclipse-debugger' registration was left unchanged. "
                    + "Rerun with 'install --replace' to replace it without an interactive prompt.");
        }
        installer.stage();
        if (exists) claude.remove();
        claude.register(java, installer.installedJar());
        installer.markManaged();
        System.out.println("Installed MCP server and registered 'eclipse-debugger' for Claude Code.");
        System.out.println("Restart Claude Code and use /mcp to verify connectivity to Eclipse IDE.");
    }

    private static boolean confirmReplacement() {
        Console console = System.console();
        if (console == null) return false;
        String answer = console.readLine("Existing 'eclipse-debugger' registration found. Replace it? [y/N] ");
        return "y".equalsIgnoreCase(answer == null ? "" : answer.trim());
    }

    private static void check(McpServerInstaller installer, ClaudeCodeConfigurator claude)
            throws IOException, InterruptedException {
        boolean file = Files.isRegularFile(installer.installedJar());
        boolean managed = installer.isManaged();
        boolean registered = claude.registrationExists();
        System.out.println("MCP server installed: " + file);
        System.out.println("Managed by this installer: " + managed);
        System.out.println("Claude Code MCP registration present: " + registered);
        if (!file || !managed || !registered) {
            throw new IllegalStateException("Installation is incomplete. Rerun the installer.");
        }
        System.out.println("Registration present; run /mcp in Claude Code to confirm live connection.");
    }

    private static void uninstall(McpServerInstaller installer, ClaudeCodeConfigurator claude)
            throws IOException, InterruptedException {
        if (!installer.isManaged()) {
            throw new IllegalStateException("No installation managed by this utility; nothing was removed.");
        }
        if (claude.registrationExists()) claude.remove();
        installer.uninstallFiles();
        System.out.println("Removed the MCP server and its user-scoped Claude Code registration.");
        System.out.println("The Eclipse IDE plugin is separate and was not modified.");
    }

    private static void usage() {
        System.out.println("Usage: java -jar agentic-debugger-setup.jar [install [--replace] | check | uninstall]");
    }
}
