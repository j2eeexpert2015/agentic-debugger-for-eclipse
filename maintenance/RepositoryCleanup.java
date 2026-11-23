import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-time, fail-closed repository housekeeping, based on reviewed GitHub blobs.
 * Run at repository root: java maintenance/RepositoryCleanup.java [--apply]
 * Without --apply it only previews. Never deletes modified files or directories.
 */
public final class RepositoryCleanup {
    private static final Map<String, String> OBSOLETE = new LinkedHashMap<>();
    static {
        OBSOLETE.put("APPLY-AND-VERIFY.md", "d80253d334a280b930fd64a77fd60355e683d537");
        OBSOLETE.put("APPLY-LIFECYCLE-DIAGNOSTICS.md", "3764689104478fc58a1ec2b4229a5db5931dad43");
        OBSOLETE.put("APPLY-INSTRUCTIONS.txt", "718875f07bb4187aa4b1d0dc89b5d93ee6dff25f");
        OBSOLETE.put("CHANGES.md", "5d1a537fd8c4ccadffbe28348c70a9e4b4d8e5c6");
        OBSOLETE.put("DOCUMENTATION-AUDIT.txt", "5077aea0fb217fce84181bf8a85c05c95f13b46e");
        OBSOLETE.put("EXTRACT-AND-RUN.md", "fd211a50b988707324b0c4d35ed6121d13356545");
        OBSOLETE.put("EXTRACT-AND-TEST.md", "6cce235a9b7ba92d4a6f354c9ecf989c637efb40");
        OBSOLETE.put("INTEGRATION-AUDIT.md", "4a51009e774b3361104c5b5e1f8cc2b9532dfd2f");
        OBSOLETE.put("SHA256SUMS.txt", "69a708e736b0520d6f169c56afd32d810a4627b0");
        OBSOLETE.put("dev-e2e/ACCEPTANCE-EXPANSION.md", "d1b83bb7768c5e04bdd35ab89155b7b4a7b56ff3");
        OBSOLETE.put("docs/JAVA-PENDING-E2E.md", "2af68317cd8cbc7c670603a714f327d77ac2f5eb");
        OBSOLETE.put("docs/phase2-bridge-verification.md", "65a957f75319b1ce1496cc6d664e0b1afe60d14b");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || args.length == 1 && !"--apply".equals(args[0]))
            throw new IllegalArgumentException("Usage: java maintenance/RepositoryCleanup.java [--apply]");
        boolean apply = args.length == 1;
        Path root = Path.of("").toAbsolutePath().normalize().toRealPath();
        for (String essential : new String[]{"pom.xml", ".git", "eclipse-plugin/META-INF/MANIFEST.MF",
                "dev-e2e/DevE2E.java", "README.md"})
            check(Files.exists(root.resolve(essential)), "Expected Eclipse debugger repository root, missing " + essential);
        int existing = 0, mismatches = 0;
        // Two-phase: check EVERY candidate before deleting ANY candidate.
        for (var e : OBSOLETE.entrySet()) {
            Path p = root.resolve(e.getKey()).normalize();
            check(p.startsWith(root) && !Files.isSymbolicLink(p), "Refusing unsafe path " + p);
            if (!Files.exists(p)) { System.out.println("ABSENT:   " + e.getKey()); continue; }
            check(Files.isRegularFile(p), "Refusing non-file " + p);
            existing++;
            byte[] bytes = Files.readAllBytes(p);
            String actual = gitBlobHash(bytes);
            // On Windows, Git may checkout text files using CRLF even when its blob is LF.
            // Accept only that newline conversion, never arbitrary content changes.
            String normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            boolean verified = actual.equals(e.getValue()) ||
                    gitBlobHash(normalized.getBytes(StandardCharsets.UTF_8)).equals(e.getValue());
            if (!verified) {
                mismatches++;
                System.out.println("CHANGED:  " + e.getKey() + " (will NOT delete; preserve local edits)");
            } else System.out.println((apply ? "ELIGIBLE: " : "WOULD DELETE: ") + e.getKey());
        }
        if (mismatches > 0) {
            System.out.println("REFUSED: " + mismatches + " changed files; no deletions performed.");
            System.exit(2);
        }
        if (apply) {
            for (var e : OBSOLETE.entrySet()) {
                Path p = root.resolve(e.getKey());
                if (Files.exists(p)) { Files.delete(p); System.out.println("DELETED: " + e.getKey()); }
            }
            System.out.println("Done. These tracked deletions are reversible with git restore; inspect git status.");
        } else System.out.println("Preview complete: " + existing + " unchanged candidate files. Run --apply to delete.");
        System.out.println("Preserved: all production Java, verifiers, fixtures, skill files, specialized scripts and dated evidence.");
    }
    private static String gitBlobHash(byte[] bytes) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        sha1.update(bytes);
        return HexFormat.of().formatHex(sha1.digest());
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
