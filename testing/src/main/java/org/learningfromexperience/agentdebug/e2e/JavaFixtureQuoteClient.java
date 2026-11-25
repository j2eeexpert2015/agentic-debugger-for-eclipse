package org.learningfromexperience.agentdebug.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Java-only replacement for boot-tests/Invoke-FixtureQuote.ps1.
 *  Only sends an approved POST to the dedicated local Spring Boot fixture JVM.
 *  NOT a general-purpose HTTP client.
 */
public final class JavaFixtureQuoteClient {
    private static final Pattern START = Pattern.compile("Starting OrdersApplication[^\\r\\n]*?with PID (\\d+)");
    private static final Pattern READY = Pattern.compile("Tomcat started on port (\\d+)");

    private JavaFixtureQuoteClient() { }

    public static void main(String[] args) throws Exception {
        Map<String,String> options = new LinkedHashMap<>();
        boolean approved = false, invalid = false;
        for (int i = 0; i < args.length; i++) {
            if ("--approve-fixture-post".equals(args[i])) { approved = true; continue; }
            if ("--invalid-quantity".equals(args[i])) { invalid = true; continue; }
            if (!args[i].startsWith("--") || i + 1 == args.length) throw new IllegalArgumentException("Invalid command line");
            options.put(args[i++].substring(2), args[i]);
        }
        // CRITICAL: consent must be checked before any bridge/network interaction.
        require(approved, "Fixture POST requires explicit approval");
        Path ws = Path.of(required(options, "workspace")).toRealPath();
        Path receipt = Path.of(required(options, "receipt")).toAbsolutePath().normalize();
        String launch = required(options, "launch-name");
        int bridgePort = Integer.parseInt(required(options, "bridge-port"));
        require(bridgePort >= 1024 && bridgePort <= 65535 && bridgePort != 8765,
                "Use the dedicated nondefault Eclipse bridge");
        require(!Files.exists(receipt), "Choose a fresh receipt path for each run");
        require(Files.isRegularFile(ws.resolve("spring-boot-debugging/.agentdebug-fixture")) ||
                Files.isRegularFile(ws.resolve("phase-one-debugging/.agentdebug-fixture")),
                "Refusing a workspace without the dedicated test-fixture marker");

        Path tokenFile = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "bridge-"+bridgePort+".token");
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        Map<String,Object> info = bridge(bridgePort, token, "GET_E2E_INFO");
        require("success".equals(info.get("status")), "Eclipse bridge identity query failed");
        require(Path.of(string(info.get("workspace"))).toRealPath().equals(ws), "Wrong Eclipse workspace");
        require(string(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Expected installed Agentic Debugger plugin");

        String encodedLaunch = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(launch.getBytes(StandardCharsets.UTF_8));
        Matcher startup = null, ready = null;
        boolean started = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String,Object> output = bridge(bridgePort, token, "GET_PROCESS_OUTPUT " + encodedLaunch);
            if ("success".equals(output.get("status"))) {
                List<Object> processes = list(output.get("processes"));
                require(processes.size() == 1, "Expected exactly one process in selected fixture launch");
                Map<String,Object> process = map(processes.get(0));
                String lines = string(process.get("stdout")) + "\n" + string(process.get("console"));
                startup = START.matcher(lines);
                ready = READY.matcher(lines);
                if (startup.find() && ready.find()) { started = true; break; }
            } else if (!string(output.get("message")).contains("Active debug launch not found")) {
                throw new IllegalStateException("Fixture launch discovery failed: " + output);
            }
            Thread.sleep(250);
        }
        require(started, "Timed out waiting for selected fixture startup and readiness");
        long pid;
        int appPort;
        try {
            pid = Long.parseLong(startup.group(1));
            appPort = Integer.parseInt(ready.group(1));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Timed out waiting for fixture startup and readiness", e);
        }
        require(appPort > 0 && appPort <= 65535, "Invalid fixture HTTP port");
        ProcessHandle process = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("Fixture JVM has exited"));
        require(process.isAlive(), "Fixture JVM no longer running");
        // The OS may expose only javaw.exe, a classpath launcher or a shortened JVM command line.
        // Do not infer application identity from command-line text. The fixture-only HTTP
        // endpoint below must return this exact live PID and application marker before POST.

        String url = "http://127.0.0.1:" + appPort + "/orders/quote";
        require(!options.containsKey("requested-url") || url.equals(options.get("requested-url")),
                "Requested URL does not match approved fixture endpoint");
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new NoProxy()).build()) {
            // Portable replacement for OS-specific Get-NetTCPConnection: an endpoint served
            // ONLY by the test fixture identifies the JVM behind the selected listening port.
            HttpResponse<String> identity = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + appPort + "/agentdebug-fixture/identity"))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            require(identity.statusCode() == 200, "Test fixture identity endpoint unavailable");
            Map<String,Object> identityBody = map(PhaseOneBridgeVerifier.Json.parse(identity.body()));
            require(number(identityBody.get("pid")) == pid && "OrdersApplication".equals(identityBody.get("application")),
                    "HTTP listener does not belong to selected fixture JVM");
            require(process.isAlive(), "Fixture JVM exited before HTTP reproduction");

            String json = invalid ? "{\"quantity\":0,\"unitPrice\":60,\"discountPercent\":10}"
                    : "{\"quantity\":2,\"unitPrice\":60,\"discountPercent\":10}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String,Object> record = new LinkedHashMap<>();
            record.put("launch", launch);
            record.put("processId", pid);
            record.put("method", "POST");
            record.put("url", url);
            record.put("request", json);
            record.put("status", response.statusCode());
            record.put("body", response.body());
            Path temp = Files.createTempFile(receipt.getParent(), "agentdebug-receipt-", ".tmp");
            try {
                Files.writeString(temp, PhaseOneBridgeVerifier.Json.stringify(record), StandardCharsets.UTF_8);
                Files.move(temp, receipt, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
        }
    }

    private static Map<String,Object> bridge(int port, String token, String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("AUTH " + token + " " + command);
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            require(line != null, "No response from Eclipse bridge");
            return map(PhaseOneBridgeVerifier.Json.parse(line));
        }
    }
    private static String required(Map<String,String> map, String key) {
        String value = map.get(key);
        require(value != null && !value.isBlank(), "Missing --" + key);
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static String string(Object x) { return x == null ? "" : x.toString(); }
    private static long number(Object x) { return x instanceof Number n ? n.longValue() : Long.parseLong(string(x)); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object x) { return (Map<String,Object>) x; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object x) { return x instanceof List<?> a ? (List<Object>) a : List.of(); }
    private static final class NoProxy extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
    }
}
