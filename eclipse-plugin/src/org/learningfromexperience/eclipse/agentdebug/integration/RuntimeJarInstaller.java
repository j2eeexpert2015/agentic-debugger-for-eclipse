package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stages the bundled MCP JAR only when its bytes differ from the installed runtime. */
final class RuntimeJarInstaller {
    private RuntimeJarInstaller() { }

    /** @return true if a new JAR was installed; false if the existing JAR was identical. */
    static boolean installIfChanged(URL bundled, Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("MCP destination has no parent: " + destination);

        // Compare content rather than modification timestamps: p2 updates may change timestamps.
        // Do not create any directories or temporary files if the JAR is already up to date.
        byte[] bundledDigest;
        try (InputStream in = bundled.openStream()) {
            bundledDigest = digest(in);
        }
        if (Files.isRegularFile(destination)) {
            try (InputStream in = Files.newInputStream(destination)) {
                if (MessageDigest.isEqual(bundledDigest, digest(in))) return false;
            }
        }

        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "agentic-mcp-", ".jar");
        try {
            try (InputStream in = bundled.openStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(temp) < 1000) throw new IOException("Bundled MCP runtime is incomplete");
            // Check again in case the packaged bundle changed during the read.
            try (InputStream in = Files.newInputStream(temp)) {
                if (!MessageDigest.isEqual(bundledDigest, digest(in))) {
                    throw new IOException("Bundled MCP runtime changed during installation");
                }
            }
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return HexFormat.of().formatHex(digest(in));
        }
    }

    private static byte[] digest(InputStream in) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int length;
            while ((length = in.read(buf)) != -1) sha.update(buf, 0, length);
            return sha.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
