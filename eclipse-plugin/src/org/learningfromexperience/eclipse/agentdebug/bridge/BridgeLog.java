package org.learningfromexperience.eclipse.agentdebug.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Small rolling log of bridge command timings and slow-command stack traces.
 *
 * <p>Only command <i>names</i> are ever written, never arguments or tokens: arguments can
 * contain expressions, class names or values from the user's program.
 * Logging failures are swallowed; the log must never break a debugging command.
 *
 * <p>Plain Java on purpose (no Eclipse imports) so it can be unit-tested.
 *
 * @author Ayan Dutta
 */
public class BridgeLog {

    static final long MAX_BYTES = 1_000_000;

    private final Path file;

    /** @param file log file, or {@code null} to disable writing (used by tests) */
    public BridgeLog(Path file) {
        this.file = file;
    }

    /** Default location: ~/.eclipse-agentdebug/bridge-&lt;port&gt;.log */
    public static BridgeLog forPort(int port) {
        return new BridgeLog(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug",
                "bridge-" + port + ".log"));
    }

    public Path file() {
        return file;
    }

    /** One line per finished (or rejected) command. */
    public void command(long operationId, String name, BridgeCommands.Lane lane,
                        long queueMillis, long runMillis, String outcome) {
        write("op=" + operationId + " cmd=" + name + " lane=" + lane
                + " queueMs=" + queueMillis + " runMs=" + runMillis + " outcome=" + outcome);
    }

    /** Free-form event, e.g. bridge start, mode, slow command with stack trace. */
    public void event(String text) {
        write(text);
    }

    synchronized void write(String line) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, Instant.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics only; never fail a debugger command because logging failed.
        }
    }

    /** Formats the top of a thread's stack for slow-command diagnostics. */
    static String stack(Thread thread, int maxFrames) {
        if (thread == null) return "(no worker thread)";
        StringBuilder text = new StringBuilder(thread.getName()).append(" state=").append(thread.getState());
        StackTraceElement[] frames = thread.getStackTrace();
        for (int i = 0; i < Math.min(maxFrames, frames.length); i++) {
            text.append(System.lineSeparator()).append("    at ").append(frames[i]);
        }
        if (frames.length > maxFrames) text.append(System.lineSeparator()).append("    ...");
        return text.toString();
    }
}
