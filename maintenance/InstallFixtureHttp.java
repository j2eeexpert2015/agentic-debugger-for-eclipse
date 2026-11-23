import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-time, fail-closed source integration for the fixture-only MCP HTTP tool.
 * The package provides full new Java classes; this integrator adds a single
 * registration call and one Eclipse ownership verification command, retaining
 * the user's remaining MCP/JDT source and preserving originals in ignored reports.
 * No remote access, no automatic deletion, and no workspace/user configuration changes.
 */
public final class InstallFixtureHttp {
    private static final String BRIDGE_PATH = "eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java";
    private static final String MCP_PATH = "mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java";
    private static final String CASE_ANCHOR = "                case \"GET_PROCESS_OUTPUT\" -> getProcessOutput(p);";
    private static final String CASE_INSERT = "                case \"VERIFY_OWNED_FIXTURE_LAUNCH\" -> verifyOwnedFixtureLaunch(p);\n" + CASE_ANCHOR;
    private static final String MCP_ANCHOR = "        // Initialize MCP server.";
    private static final String MCP_INSERT = "        // Restricted, opt-in test fixture HTTP reproduction; disabled in normal MCP sessions.\n"
            + "        FixtureHttpTools.register(allTools, command -> sendToEclipse(command, true));\n\n"
            + MCP_ANCHOR;
    private static final String METHOD_ANCHOR = "    private String getProcessOutput(String[] p) {";
    private static final String METHOD = """
            /** Test-only check: the exact selected launch must belong to this NORMAL investigation. */
            private String verifyOwnedFixtureLaunch(String[] p) throws CoreException {
                String investigationId = require(p, 1);
                String launchName = require(p, 2);
                var investigation = investigations.require(investigationId);
                if (investigation.mode() != DebugInvestigationPolicy.Mode.NORMAL)
                    throw new IllegalStateException("HTTP fixture POST requires a NORMAL investigation");
                ILaunch selected = launch(launchName);
                investigation.verifySession(selected);
                if (investigation.ownedLaunch() != selected || selected.isTerminated())
                    throw new IllegalStateException("Selected fixture launch is not active and investigation-owned");
                ILaunchConfiguration configuration = selected.getLaunchConfiguration();
                String project = configuration.getAttribute(
                        IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
                String mainType = configuration.getAttribute(
                        IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                if (!"spring-boot-debugging".equals(project)
                        || !"org.learningfromexperience.bootfixture.orders.OrdersApplication".equals(mainType))
                    throw new IllegalStateException("Wrong fixture project or main type");
                return ok(",\\"owned\\":true,\\"launchName\\":" + q(launchName));
            }

            """;
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || args.length == 1 && !"--apply".equals(args[0]))
            throw new IllegalArgumentException("Usage: java maintenance/InstallFixtureHttp.java [--apply]");
        boolean apply = args.length == 1;
        Path root = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")))
            throw new IllegalStateException("Run from the project repository root");
        if (!Files.isRegularFile(root.resolve("mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/FixtureHttpTools.java")))
            throw new IllegalStateException("Extract the entire ZIP before installing");
        Map<Path,String> replacements = new LinkedHashMap<>();
        Path plugin = root.resolve(BRIDGE_PATH), mcp = root.resolve(MCP_PATH);
        String originalPlugin = Files.readString(plugin,StandardCharsets.UTF_8);
        String originalMcp = Files.readString(mcp,StandardCharsets.UTF_8);
        String updatedPlugin = updatePlugin(originalPlugin);
        String updatedMcp = replaceOnce(originalMcp,MCP_ANCHOR,MCP_INSERT,"MCP tool registration");
        replacements.put(plugin,updatedPlugin);
        replacements.put(mcp,updatedMcp);
        for(var entry: replacements.entrySet()) {
            System.out.println((entry.getValue().equals(Files.readString(entry.getKey(),StandardCharsets.UTF_8))
                    ? "UNCHANGED: " : apply ? "UPDATE: " : "WOULD UPDATE: ") + root.relativize(entry.getKey()));
        }
        if (!apply) {
            System.out.println("Dry run complete. Run java maintenance/InstallFixtureHttp.java --apply");
            return;
        }
        Path backups = root.resolve("evaluation/reports/fixture-http-source-backups");
        Files.createDirectories(backups);
        for(var entry: replacements.entrySet()) {
            Path path=entry.getKey();
            String original=Files.readString(path,StandardCharsets.UTF_8);
            if(original.equals(entry.getValue()))continue;
            Path backup = backups.resolve(root.relativize(path).toString().replace('\\','_').replace('/','_') + ".before-http.txt");
            if (!Files.exists(backup)) Files.writeString(backup,original,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Path temporary = Files.createTempFile(path.getParent(),".fixture-http-", ".tmp");
            try {
                Files.writeString(temporary,entry.getValue(),StandardCharsets.UTF_8);
                Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        System.out.println("PASS: Fixture HTTP MCP/bridge source integrated. Review git diff before build.");
    }
    private static String updatePlugin(String source) {
        if (!source.contains(CASE_INSERT))
            source = replaceOnce(source, CASE_ANCHOR, CASE_INSERT,"Eclipse command dispatcher");
        if (!source.contains("private String verifyOwnedFixtureLaunch("))
            source = replaceOnce(source,METHOD_ANCHOR,METHOD+METHOD_ANCHOR,"Eclipse launch ownership verification");
        return source;
    }
    private static String replaceOnce(String source, String anchor, String replacement, String purpose) {
        if(source.contains(replacement)) return source;
        int first=source.indexOf(anchor);
        if(first<0 || source.indexOf(anchor,first+anchor.length())>=0)
            throw new IllegalStateException("Cannot safely locate unique source anchor for: " + purpose);
        return source.substring(0,first)+replacement+source.substring(first+anchor.length());
    }
}
