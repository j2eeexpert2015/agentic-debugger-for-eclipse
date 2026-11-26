package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for what GitHub Copilot is told about our MCP server ({@link CopilotRegistration}, OPEN-23).
 * No Eclipse and no Copilot needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CopilotRegistrationTest {

    @TempDir Path folder;

    @Test
    @DisplayName("CP-U1 the JSON names one stdio server, eclipse-debugger, run by the launcher script without arguments; Windows paths escaped")
    void cpU1() {
        String json = CopilotRegistration.json(Path.of("C:\\Users\\a b\\.eclipse-agentdebug\\start-mcp-server.cmd"), null);
        assertEquals("{\"servers\":{\"eclipse-debugger\":{\"type\":\"stdio\","
                + "\"command\":\"C:\\\\Users\\\\a b\\\\.eclipse-agentdebug\\\\start-mcp-server.cmd\","
                + "\"args\":[]}}}", json);
    }

    @Test
    @DisplayName("CP-U2 the bridge port goes into env only when it is not the default 8765")
    void cpU2() {
        Path launcher = Path.of("/home/u/.eclipse-agentdebug/start-mcp-server.sh");
        assertFalse(CopilotRegistration.json(launcher, "8765").contains("env"), "default port: no env");
        assertFalse(CopilotRegistration.json(launcher, " ").contains("env"), "blank: no env");
        assertTrue(CopilotRegistration.json(launcher, "21766").endsWith(
                ",\"env\":{\"AGENTDEBUG_PORT\":\"21766\"}}}}"), "another port: env with the port");
    }

    @Test
    @DisplayName("CP-U3 the jar folder is ~/.eclipse-agentdebug for the default port and a folder of its own otherwise")
    void cpU3() {
        Path home = Path.of("/home/u");
        assertEquals(home.resolve(".eclipse-agentdebug"), CopilotRegistration.runtimeFolder(home, null));
        assertEquals(home.resolve(".eclipse-agentdebug"), CopilotRegistration.runtimeFolder(home, "8765"));
        assertEquals(home.resolve(".eclipse-agentdebug").resolve("port-21766"),
                CopilotRegistration.runtimeFolder(home, "21766"));
        assertEquals(home.resolve(".eclipse-agentdebug").resolve("port-21766"),
                CopilotRegistration.runtimeFolder(home, "21766/../x"), "only digits are kept");
    }

    @Test
    @DisplayName("CP-U4 Windows launcher: echo off, java -jar with quoted paths and %* passed on, % kept, CRLF line ends")
    void cpU4() {
        String text = CopilotRegistration.launcherText(
                Path.of("C:\\eclipse\\plugins\\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.9\\jre\\bin\\java.exe"),
                Path.of("C:\\Users\\a%b c\\.eclipse-agentdebug\\agentic-debugger-mcp.jar"), true);
        assertTrue(text.startsWith("@echo off\r\n"), "nothing but the protocol on stdout");
        assertTrue(text.endsWith("\"C:\\eclipse\\plugins\\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.9"
                + "\\jre\\bin\\java.exe\" -jar \"C:\\Users\\a%%b c\\.eclipse-agentdebug\\agentic-debugger-mcp.jar\" %*\r\n"), text);
        assertFalse(text.replace("\r\n", "").contains("\n"), "CRLF only");
        assertEquals("start-mcp-server.cmd", CopilotRegistration.launcherName(true));
    }

    @Test
    @DisplayName("CP-U5 Linux/macOS launcher: sh, exec java -jar, paths in single quotes (a quote in a path kept), arguments passed on")
    void cpU5() {
        // Expected text built from the same Path objects: on a Windows build Path.of("/opt/...") prints with
        // backslashes (failed that way 26 Nov 23:50); the quoting is what this test checks.
        Path java = Path.of("/opt/eclipse/jre/bin/java");
        Path jar = Path.of("/home/o'neil/.eclipse-agentdebug/agentic-debugger-mcp.jar");
        String text = CopilotRegistration.launcherText(java, jar, false);
        assertTrue(text.startsWith("#!/bin/sh\n"), text);
        assertTrue(text.endsWith("exec '" + java + "' -jar '" + jar.toString().replace("'", "'\\''") + "' \"$@\"\n"), text);
        assertTrue(text.contains("o'\\''neil"), "a quote in a path is closed, escaped and reopened: " + text);
        assertEquals("start-mcp-server.sh", CopilotRegistration.launcherName(false));
    }

    @Test
    @DisplayName("CP-U6 a new Java changes only the launcher, not the JSON (Copilot keeps its approval); an unchanged launcher is not rewritten")
    void cpU6() throws Exception {
        Path jar = folder.resolve("agentic-debugger-mcp.jar");
        Path oldJava = Path.of("C:\\eclipse\\plugins\\justj_21.0.8\\jre\\bin\\java.exe");
        Path newJava = Path.of("C:\\eclipse\\plugins\\justj_21.0.9\\jre\\bin\\java.exe");

        Path launcher = CopilotRegistration.writeLauncher(folder, oldJava, jar, true);
        String before = CopilotRegistration.json(launcher, "21766");
        assertTrue(Files.readString(launcher, StandardCharsets.UTF_8).contains("justj_21.0.8"));

        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(launcher, old);
        CopilotRegistration.writeLauncher(folder, oldJava, jar, true);
        assertEquals(old, Files.getLastModifiedTime(launcher), "same text: the file is not touched");

        Path again = CopilotRegistration.writeLauncher(folder, newJava, jar, true);
        assertEquals(launcher, again);
        assertTrue(Files.readString(launcher, StandardCharsets.UTF_8).contains("justj_21.0.9"), "the launcher follows the new Java");
        assertEquals(before, CopilotRegistration.json(again, "21766"), "the JSON Copilot approved stays the same");
        assertFalse(before.contains("justj"), "no Java path in the JSON");
        assertFalse(Files.exists(folder.resolve("start-mcp-server.cmd.tmp")), "no temporary file left");
    }

    @Test
    @DisplayName("CP-U2b Linux/macOS (for now): the direct form java -jar <jar>, as before the launcher")
    void cpU2b() {
        Path java = Path.of("/opt/java/bin/java");                       // printed with \\ on a Windows build
        Path jar = Path.of("/home/u/.eclipse-agentdebug/agentic-debugger-mcp.jar");
        assertEquals("{\"servers\":{\"eclipse-debugger\":{\"type\":\"stdio\",\"command\":"
                + CopilotRegistration.quote(java.toString()) + ",\"args\":[\"-jar\","
                + CopilotRegistration.quote(jar.toString()) + "]}}}",
                CopilotRegistration.jsonDirect(java, jar, "8765"));
    }

    /**
     * CP-U7: the launcher of the system the build runs on really starts Java with the jar and passes stdin and stdout
     * through, with nothing else on stdout (it carries the MCP protocol). The jar is a tiny echo program compiled here,
     * in a folder whose name has a space and a character the script must protect ({@code %} on Windows, {@code '}
     * elsewhere). On Windows the launcher is run as cross-spawn (Copilot) runs a {@code .cmd}: through
     * {@code cmd.exe /d /s /c}. Skipped when the build runs on a Java without a compiler.
     */
    @Test
    @DisplayName("CP-U7 the launcher of this system really runs: java -jar starts, stdin and stdout pass through, nothing else on stdout")
    void cpU7() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no Java compiler in this build's runtime");
        boolean windows = CopilotRegistration.windows();
        Path dir = Files.createDirectories(folder.resolve(windows ? "a b%c" : "a b'c"));
        Path source = dir.resolve("Echo.java");
        Files.writeString(source, "public class Echo { public static void main(String[] a) throws Exception {"
                + " String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();"
                + " System.out.println(\"echo:\" + line); } }", StandardCharsets.UTF_8);
        assertEquals(0, compiler.run(null, null, null, "-d", dir.toString(), source.toString()), "compiling the echo program");
        Path jar = dir.resolve("echo.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Echo");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("Echo.class"));
            out.write(Files.readAllBytes(dir.resolve("Echo.class")));
            out.closeEntry();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path launcher = CopilotRegistration.writeLauncher(dir, java, jar, windows);
        List<String> command = windows
                ? List.of("cmd.exe", "/d", "/s", "/c", "\"\"" + launcher + "\"\"")
                : List.of(launcher.toString());
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write("{\"jsonrpc\":\"2.0\"}\n".getBytes(StandardCharsets.UTF_8));
        }
        List<String> lines;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = out.lines().toList();
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the launcher did not end");
        assertEquals(0, process.exitValue(), "exit code; stdout: " + lines);
        assertEquals(List.of("echo:{\"jsonrpc\":\"2.0\"}"), lines, "only the program's own output on stdout");
    }
}
