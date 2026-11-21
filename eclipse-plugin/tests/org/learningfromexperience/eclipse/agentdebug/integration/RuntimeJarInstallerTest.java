package org.learningfromexperience.eclipse.agentdebug.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

/** Standalone Java sanity test. Run using javac/java; requires no Eclipse dependencies. */
public final class RuntimeJarInstallerTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("agentic-mcp-test-");
        try {
            Path source = root.resolve("bundled.jar");
            Path target = root.resolve("installed").resolve("mcp.jar");
            byte[] first = new byte[1500];
            Arrays.fill(first, (byte) 7);
            Files.write(source, first);
            check(RuntimeJarInstaller.installIfChanged(source.toUri().toURL(), target), "first install");
            check(Arrays.equals(Files.readAllBytes(target), first), "installed contents");
            FileTime original = FileTime.fromMillis(1_700_000_000_000L);
            Files.setLastModifiedTime(target, original);
            check(!RuntimeJarInstaller.installIfChanged(source.toUri().toURL(), target), "unchanged skip");
            check(Files.getLastModifiedTime(target).equals(original), "unchanged timestamp");
            byte[] second = new byte[1500];
            Arrays.fill(second, (byte) 9);
            Files.write(source, second);
            check(RuntimeJarInstaller.installIfChanged(source.toUri().toURL(), target), "changed replace");
            check(Arrays.equals(Files.readAllBytes(target), second), "replacement contents");
            Files.delete(target);
            check(RuntimeJarInstaller.installIfChanged(source.toUri().toURL(), target), "missing restore");
            System.out.println("PASS: install, skip unchanged, replace changed, restore missing");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted((a,b) -> b.compareTo(a)).toList()) Files.deleteIfExists(p);
            }
        }
    }
    private static void check(boolean expected, String message) {
        if (!expected) throw new AssertionError(message);
    }
}
