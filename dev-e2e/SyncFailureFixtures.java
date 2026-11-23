import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/** Safely sync two intentionally failing test fixtures into the disposable Eclipse snapshot.
 * Run only after closing the dedicated Eclipse test instance. Never touches personal projects.
 */
class SyncFailureFixtures {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(".").toRealPath();
        if (!Files.isRegularFile(repo.resolve("dev-e2e/DevE2E.java")))
            throw new IllegalStateException("Run from the agentic-debugger repository root");
        Properties cfg = new Properties();
        try (var reader = Files.newBufferedReader(repo.resolve("dev-e2e/e2e.properties"), StandardCharsets.ISO_8859_1)) {
            cfg.load(reader);
        }
        String configured = cfg.getProperty("workspace");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("Missing workspace setting");
        configured = configured.replace("${LOCALAPPDATA}", System.getenv().getOrDefault("LOCALAPPDATA", ""))
                .replace("%LOCALAPPDATA%", System.getenv().getOrDefault("LOCALAPPDATA", ""));
        Path workspace = Path.of(configured).toRealPath();
        if (!workspace.getFileName().toString().equalsIgnoreCase("java-e2e-workspace")
                || !workspace.getParent().getFileName().toString().equalsIgnoreCase("AgenticDebugger"))
            throw new IllegalStateException("Refusing non-dedicated workspace: " + workspace);
        int port = Integer.parseInt(cfg.getProperty("bridgePort", "21766"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            throw new IllegalStateException("The dedicated Eclipse bridge is active on port " + port
                    + "; close the test Eclipse instance first");
        } catch (java.net.ConnectException ignored) {
            // Expected when the dedicated Eclipse bridge is not running.
        } catch (java.net.SocketTimeoutException ignored) {
            throw new IllegalStateException("Port " + port + " did not refuse connections; close Eclipse and retry");
        }
        Path fixture = repo.resolve("examples/spring-boot-debugging").toRealPath();
        Path target = workspace.resolve("spring-boot-debugging").toRealPath();
        Path marker = target.resolve(".agentdebug-fixture");
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("Missing disposable fixture marker: " + marker);
        if (!Path.of(Files.readString(marker).trim()).toRealPath().equals(fixture))
            throw new IllegalStateException("Fixture marker belongs to another repository; refusing to overwrite");
        String dir = "src/main/java/org/learningfromexperience/bootfixture/failures/";
        for (String name : List.of("StartupFailureApplication.java", "PortConflictApplication.java")) {
            Path from = fixture.resolve(dir + name);
            Path to = target.resolve(dir + name);
            if (!Files.isRegularFile(from) || !Files.isRegularFile(to) || Files.isSymbolicLink(to))
                throw new IllegalStateException("Refusing unexpected fixture path: " + to);
            if (Files.mismatch(from, to) == -1) {
                System.out.println("Already synchronized: " + name);
            } else {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                if (Files.mismatch(from, to) != -1) throw new IOException("Copy verification failed: " + name);
                System.out.println("Updated disposable fixture: " + name);
            }
        }
        System.out.println("PASS: both failure fixtures match the repository; restart the Java E2E runner");
    }
}
