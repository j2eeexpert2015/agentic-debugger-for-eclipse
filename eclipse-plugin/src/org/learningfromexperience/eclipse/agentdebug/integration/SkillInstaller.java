package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Keeps the Claude Code skill {@code eclipse-debugger} in {@code ~/.claude/skills/eclipse-debugger/} the same as the
 * one packed in the plugin (OPEN-32), so a plugin update also updates the skill.
 *
 * <p><b>Never loses the user's own edits.</b> A state file records what the plugin last wrote for each file. For each
 * bundled file:
 * <ul>
 *   <li>missing: written;</li>
 *   <li>already the same: left alone (nothing written, the file time unchanged);</li>
 *   <li>still exactly what the plugin wrote last time: replaced with the new version;</li>
 *   <li>never written by the plugin (copied by hand before OPEN-32): kept as {@code <name>.bak-<time>}, then
 *       replaced;</li>
 *   <li>changed by the user after the plugin wrote it: left as it is; the new version is written next to it as
 *       {@code <name>.new} and the result says so.</li>
 * </ul>
 * Files of the skill folder that the plugin does not bundle are never touched. Each file is written to a temporary
 * file first and then moved into place.
 *
 * @author Ayan Dutta
 */
final class SkillInstaller {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SkillInstaller() {
    }

    /**
     * @param bundled the plugin's skill files, by path relative to the skill folder ("SKILL.md",
     *                "references/java-debugging.md")
     * @param target  the installed skill folder
     * @param state   the state file (what the plugin wrote last time)
     * @return one line per file that was written, backed up or left because of the user's edits; empty when the skill
     *         was already current
     */
    static List<String> install(Map<String, byte[]> bundled, Path target, Path state) throws IOException {
        Properties written = new Properties();
        if (Files.isRegularFile(state)) try (var in = Files.newInputStream(state)) {
            written.load(in);
        }
        List<String> report = new ArrayList<>();
        boolean changed = false;
        for (var entry : bundled.entrySet()) {
            String name = entry.getKey().replace('\\', '/');
            if (name.isBlank() || name.startsWith("/") || name.contains("..")) continue;
            Path file = target.resolve(name).normalize();
            if (!file.startsWith(target.normalize())) continue;
            String newSha = sha(entry.getValue());
            if (Files.isRegularFile(file)) {
                String current = sha(Files.readAllBytes(file));
                if (current.equals(newSha)) {
                    if (!newSha.equals(written.getProperty(name))) {
                        written.setProperty(name, newSha);
                        changed = true;
                    }
                    continue;
                }
                String last = written.getProperty(name);
                if (last != null && !last.equals(current)) {
                    write(file.resolveSibling(file.getFileName() + ".new"), entry.getValue());
                    report.add(name + ": kept your edited copy; the new version is next to it as "
                            + file.getFileName() + ".new");
                    continue;
                }
                if (last == null) {
                    Path backup = file.resolveSibling(file.getFileName() + ".bak-" + LocalDateTime.now().format(STAMP));
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    report.add(name + ": updated (the previous copy is kept as " + backup.getFileName() + ")");
                } else {
                    report.add(name + ": updated");
                }
            } else {
                report.add(name + ": installed");
            }
            write(file, entry.getValue());
            written.setProperty(name, newSha);
            changed = true;
        }
        if (changed) {
            Files.createDirectories(state.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(state.toAbsolutePath().getParent(), "skill-installed-", ".tmp");
            try {
                try (var out = Files.newOutputStream(tmp)) {
                    written.store(out, "Agentic Debugger: skill files as the plugin last wrote them (SHA-256)");
                }
                move(tmp, state);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return report;
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "skill-", ".tmp");
        try {
            Files.write(tmp, content);
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
