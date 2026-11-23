package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Explicitly opt-in, fixture-only HTTP repro tool for disposable Java E2E Claude trials.
 * Never enabled for ordinary Eclipse / Claude debugging sessions. No arbitrary URLs,
 * request bodies, redirects, proxies or access to borrowed launch instances.
 */
public final class FixtureHttpTools {
    @FunctionalInterface public interface Bridge {
        String call(String command) throws IOException;
    }
    private static final String PROJECT = "spring-boot-debugging";
    private static final String APP = "OrdersApplication";
    private static final Pattern PID = Pattern.compile("Starting OrdersApplication[^\\r\\n]*?with PID (\\d+)");
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final Map<String, Pending> REQUESTS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();

    private record Pending(String launchName, String scenario, long pid, int port,
                           long createdMillis, CompletableFuture<HttpResponse<String>> future) { }
    private FixtureHttpTools() { }

    /** A test-only MCP tool, never registered without explicit E2E environment/consent. */
    public static void register(List<SyncToolSpecification> tools, Bridge bridge) {
        if (!"1".equals(System.getenv("AGENTDEBUG_E2E_HTTP"))) return;
        String workspace = System.getenv("AGENTDEBUG_E2E_WORKSPACE");
        String root = System.getenv("AGENTDEBUG_E2E_ROOT");
        if (workspace == null || root == null || workspace.isBlank() || root.isBlank()) return;
        tools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("start_fixture_http_request", schema(Map.of(
                        "launchName", str(), "investigationId", str(),
                        "scenario", Map.of("type", "string", "enum", List.of("discount", "invalid-quantity"))
                ), List.of("launchName", "investigationId", "scenario")))
                        .description("TEST FIXTURE ONLY. Begin an asynchronous POST to the owned, disposable OrdersApplication "
                                + "to reproduce the discount bug or invalid-quantity HTTP 400. Requires explicit "
                                + "test consent, an active NORMAL investigation owning the selected launch, and "
                                + "verified localhost fixture identity. Returns requestId immediately; breakpoint "
                                + "can pause the request thread. Inspect frames, resume thread, then call "
                                + "get_fixture_http_request to obtain the actual response. No Bash/curl needed.")
                        .build())
                .callHandler((exchange, request) -> {
                    try { return success(start(bridge, request.arguments())); }
                    catch (Exception error) { return failure(error.getMessage()); }
                }).build());
        tools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("get_fixture_http_request", schema(Map.of(
                        "requestId", str()), List.of("requestId")))
                        .description("Non-blocking status/result for a previously started owned fixture POST. "
                                + "Poll after resuming the HTTP request thread; reports actual status and body.").build())
                .callHandler((exchange, request) -> {
                    try { return success(status(required(request.arguments(), "requestId"))); }
                    catch (Exception error) { return failure(error.getMessage()); }
                }).build());
        tools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("cancel_fixture_http_request", schema(Map.of(
                        "requestId", str()), List.of("requestId")))
                        .description("Cancel a still pending disposable fixture HTTP request. "
                                + "Only affects requests initiated by this test MCP process.").build())
                .callHandler((exchange, request) -> {
                    try {
                        String id = required(request.arguments(), "requestId");
                        Pending item = REQUESTS.get(id);
                        if (item == null) throw new IllegalArgumentException("Unknown requestId");
                        boolean cancelled = item.future.cancel(true);
                        return success("{\"requestId\":" + q(id) + ",\"cancelled\":" + cancelled + "}");
                    } catch (Exception error) { return failure(error.getMessage()); }
                }).build());
    }

    private static String start(Bridge bridge, Map<String,Object> args) throws Exception {
        require("1".equals(System.getenv("AGENTDEBUG_E2E_POST_APPROVED")),
                "Fixture POST not approved in E2E runner");
        String scenario = required(args, "scenario");
        require(scenario.equals("discount") || scenario.equals("invalid-quantity"), "Unsupported fixture scenario");
        String launch = required(args, "launchName");
        require(launch.equals("Agentic Debugger - spring-boot-debugging - OrdersApplication"),
                "Only the controlled OrdersApplication launch is accepted");
        String investigation = required(args, "investigationId");
        require(investigation.matches("[0-9a-fA-F-]{36}"), "Invalid investigation ID");
        Path workspace = Path.of(System.getenv("AGENTDEBUG_E2E_WORKSPACE")).toRealPath();
        Path root = Path.of(System.getenv("AGENTDEBUG_E2E_ROOT")).toRealPath();
        Path marker = workspace.resolve(PROJECT).resolve(".agentdebug-fixture");
        require(Files.isRegularFile(marker), "Dedicated E2E fixture marker not found");
        require(Path.of(Files.readString(marker).trim()).toRealPath()
                        .equals(root.resolve("examples").resolve(PROJECT).toRealPath()),
                "Fixture is not owned by the configured repository");
        Map<String,Object> e2e = obj(Json.parse(bridge.call("GET_E2E_INFO")));
        successBridge(e2e);
        require(Path.of(value(e2e,"workspace")).toRealPath().equals(workspace),
                "Wrong Eclipse workspace; refusing HTTP POST");
        require(value(e2e,"loadedFrom").contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Expected an installed Agentic Debugger Eclipse plugin");
        // Validation occurs INSIDE Eclipse, using investigation's exact ILaunch identity.
        Map<String,Object> owned = obj(Json.parse(bridge.call("VERIFY_OWNED_FIXTURE_LAUNCH "
                + enc(investigation) + " " + enc(launch))));
        successBridge(owned);
        require(Boolean.TRUE.equals(owned.get("owned")), "Selected fixture launch is not investigation-owned");
        require(REQUESTS.values().stream().noneMatch(p -> !p.future.isDone()),
                "Another fixture HTTP request is already pending; resume or cancel it first");
        require(REQUESTS.size() < 12, "Bounded fixture HTTP request limit reached");

        Map<String,Object> output = obj(Json.parse(bridge.call("GET_PROCESS_OUTPUT " + enc(launch))));
        successBridge(output);
        List<?> processes = array(output.get("processes"));
        require(processes.size() == 1, "Expected exactly one process in the owned fixture launch");
        Map<String,Object> process = obj(processes.get(0));
        String logs = value(process,"stdout") + "\n" + value(process,"stderr")
                + "\n" + value(process,"console");
        Matcher pm = PID.matcher(logs), tm = PORT.matcher(logs);
        require(pm.find() && tm.find(), "Orders fixture not ready; retry when Tomcat startup is visible");
        long pid = Long.parseLong(pm.group(1));
        int port = Integer.parseInt(tm.group(1));
        require(port >= 1024 && port <= 65535, "Invalid fixture HTTP port");
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow(
                () -> new IllegalStateException("Selected fixture JVM does not exist"));
        require(handle.isAlive(), "Selected fixture JVM exited");
        URI origin = URI.create("http://127.0.0.1:" + port);
        HttpResponse<String> identity = HTTP.send(HttpRequest.newBuilder(
                origin.resolve("/agentdebug-fixture/identity"))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(identity.statusCode() == 200, "Fixture HTTP identity check failed");
        Map<String,Object> identityJson = obj(Json.parse(identity.body()));
        require(APP.equals(value(identityJson,"application"))
                        && asLong(identityJson.get("pid")) == pid,
                "HTTP listener PID/application does not match selected owned fixture JVM");
        require(handle.isAlive(), "Fixture JVM exited before request");

        String payload = scenario.equals("invalid-quantity")
                ? "{\"quantity\":0,\"unitPrice\":60,\"discountPercent\":10}"
                : "{\"quantity\":2,\"unitPrice\":60,\"discountPercent\":10}";
        HttpRequest request = HttpRequest.newBuilder(origin.resolve("/orders/quote"))
                .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        String id = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(185, TimeUnit.SECONDS);
        REQUESTS.put(id, new Pending(launch, scenario, pid, port, System.currentTimeMillis(), future));
        return "{\"requestId\":" + q(id) + ",\"status\":\"PENDING\",\"method\":\"POST\","
                + "\"url\":" + q(request.uri().toString()) + ",\"scenario\":" + q(scenario)
                + ",\"pid\":" + pid + ",\"bodySent\":" + q(payload) + "}";
    }

    private static String status(String id) {
        Pending p = REQUESTS.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown requestId");
        if (!p.future.isDone())
            return "{\"requestId\":" + q(id) + ",\"status\":\"PENDING\",\"ageMs\":"
                    + (System.currentTimeMillis() - p.createdMillis) + "}";
        try {
            HttpResponse<String> res = p.future.join();
            String body = res.body();
            if (body != null && body.length() > 6000) body = body.substring(0,6000);
            return "{\"requestId\":" + q(id) + ",\"status\":\"COMPLETE\",\"httpStatus\":"
                    + res.statusCode() + ",\"body\":" + q(body) + ",\"fixturePid\":" + p.pid + "}";
        } catch (Exception e) {
            return "{\"requestId\":" + q(id) + ",\"status\":\"FAILED\",\"error\":"
                    + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}";
        }
    }
    private static Map<String,Object> schema(Map<String,Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required,
                "additionalProperties", false);
    }
    private static Map<String,Object> str() { return Map.of("type", "string"); }
    private static String required(Map<String,Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return s;
    }
    private static String enc(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t"); default -> b.append(c < 32 ? " " : c);
            }
        }
        return b.append('"').toString();
    }
    private static void successBridge(Map<String,Object> result) {
        require("success".equals(value(result,"status")), "Eclipse verification rejected request: " + result);
    }
    private static long asLong(Object n) { return n instanceof Number num ? num.longValue() : Long.parseLong(n.toString()); }
    private static String value(Map<String,Object> source, String key) {
        Object x = source.get(key); return x == null ? "" : x.toString();
    }
    private static Map<String,Object> obj(Object o) {
        if (!(o instanceof Map<?,?> map)) throw new IllegalArgumentException("Unexpected JSON object");
        java.util.LinkedHashMap<String,Object> out = new java.util.LinkedHashMap<>();
        map.forEach((k,v) -> out.put(String.valueOf(k),v));
        return out;
    }
    private static List<?> array(Object o) {
        if (!(o instanceof List<?> list)) throw new IllegalArgumentException("Unexpected JSON array");
        return list;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static CallToolResult success(String text) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).build();
    }
    private static CallToolResult failure(String text) {
        return CallToolResult.builder().isError(true)
                .content(List.of(new McpSchema.TextContent(text == null ? "Unknown fixture error" : text))).build();
    }

    /** Minimal strict JSON decoder for bridge and fixture identity responses, no external dependency. */
    static final class Json {
        private final String text; private int p;
        private Json(String text) { this.text = text; }
        static Object parse(String text) {
            Json j = new Json(text); Object result=j.read(); j.ws();
            if(j.p != text.length()) throw new IllegalArgumentException("Trailing JSON");
            return result;
        }
        private void ws(){while(p<text.length() && Character.isWhitespace(text.charAt(p)))p++;}
        private char peek(){ws();return p<text.length()?text.charAt(p):'\0';}
        private char take(){if(p>=text.length())throw new IllegalArgumentException("Unexpected end of JSON");return text.charAt(p++);}
        private void expect(char c){ws();if(take()!=c)throw new IllegalArgumentException("Unexpected JSON token");}
        private Object read() {
            char c=peek();
            if(c=='"') return string();
            if(c=='{') { take();java.util.Map<String,Object> m=new java.util.LinkedHashMap<>();
                if(peek()=='}'){take();return m;}
                while(true){String k=string();expect(':');m.put(k,read());c=peek();
                    if(c=='}'){take();return m;}expect(',');}
            }
            if(c=='['){take();java.util.List<Object> l=new java.util.ArrayList<>();
                if(peek()==']'){take();return l;}
                while(true){l.add(read());c=peek();if(c==']'){take();return l;}expect(',');}
            }
            if(text.startsWith("true",p)){p+=4;return true;}
            if(text.startsWith("false",p)){p+=5;return false;}
            if(text.startsWith("null",p)){p+=4;return null;}
            int start=p; if(peek()=='-')p++;
            while(p<text.length() && "0123456789.eE+-".indexOf(text.charAt(p))>=0)p++;
            if(start==p)throw new IllegalArgumentException("Bad JSON number");
            return new java.math.BigDecimal(text.substring(start,p));
        }
        private String string(){expect('"');StringBuilder b=new StringBuilder();
            while(true){char c=take();if(c=='"')return b.toString();
                if(c!='\\'){b.append(c);continue;}
                char e=take();switch(e){
                    case '"' -> b.append('"');case '\\' -> b.append('\\');case '/' -> b.append('/');
                    case 'n' -> b.append('\n');case 'r' -> b.append('\r');case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');case 'f' -> b.append('\f');
                    case 'u' -> {String hex=text.substring(p,p+4);p+=4;b.append((char)Integer.parseInt(hex,16));}
                    default -> throw new IllegalArgumentException("Invalid JSON string escape");
                }
            }
        }
    }
}
