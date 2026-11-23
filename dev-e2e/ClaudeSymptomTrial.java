import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in Claude CLI transcript capture, NOT an automatic correctness grader.
 * Run only in the disposable Eclipse workspace with a trusted local MCP registration.
 * Never bypasses Claude permission prompts and never runs production requests. */
public final class ClaudeSymptomTrial {
    private static final Map<String,String> PROMPTS = new LinkedHashMap<>();
    static {
        PROMPTS.put("boot-quote", "The local disposable Orders Spring Boot quote endpoint returns 120.00 when I expect 108.00 for quantity=2, unitPrice=60, discountPercent=10. Use the registered Eclipse debugger MCP to reproduce this localhost fixture issue and identify the first incorrect runtime value. You are explicitly authorized to POST ONLY to the identified disposable localhost Orders fixture. Do not modify code, files, launch configurations, or unrelated sessions. Finish your own investigation and include exact observed breakpoint frames, inputs, HTTP response and cleanup evidence. If an Eclipse MCP tool is unavailable, say so instead of guessing.");
        PROMPTS.put("junit", "A discount calculation test is failing in my imported disposable Spring Boot fixture. Investigate the relevant single JUnit method using Eclipse debugger MCP, without running the entire suite or modifying source. Show observed application frames and values, explain the mismatch, and finish only your own investigation. Do not guess or claim test evidence you did not observe.");
        PROMPTS.put("validation", "The disposable local Orders API returns HTTP 400 when quantity is invalid. Find the originating application-code path using Eclipse debugger MCP and show the actual status, body, and exception/handler evidence. You are explicitly authorized to POST ONLY to the disposable localhost Orders fixture. Do not change source or other sessions. Finish your own investigation. Report exactly what is observed.");
    }
    private static void require(boolean ok,String message) {if(!ok) throw new IllegalArgumentException(message);}
    private static String json(String s) {return "\"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r") + "\"";}
    public static void main(String[] args) throws Exception {
        require(args.length>=3,"Usage: ClaudeSymptomTrial REPO SCENARIO OUTPUT_FOLDER --approve-claude [--approve-fixture-post]");
        Path root=Path.of(args[0]).toRealPath();
        String scenario=args[1];
        Path reports=Path.of(args[2]).toAbsolutePath();
        require(PROMPTS.containsKey(scenario),"Unknown Claude scenario: " + scenario);
        boolean consent=Arrays.asList(args).contains("--approve-claude");
        boolean post=Arrays.asList(args).contains("--approve-fixture-post");
        require(consent,"Claude CLI execution requires --approve-claude (may consume tokens)");
        require(post || scenario.equals("junit"),"Fixture HTTP trial requires --approve-fixture-post");
        require(Files.isRegularFile(root.resolve("pom.xml")),"Not a repository checkout");
        // Fail closed rather than replace a previous trial or silently overwrite evidence.
        Files.createDirectories(reports);
        String stem=scenario + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path transcript=reports.resolve(stem+".claude.json");
        Path metadata=reports.resolve(stem+".manifest.json");
        Path prompt=reports.resolve(stem+".prompt.txt");
        Path stderr=reports.resolve(stem+".stderr.log");
        require(!Files.exists(transcript) && !Files.exists(metadata),"Existing evidence collision");
        Files.writeString(prompt,PROMPTS.get(scenario)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        // The CLI executes in the checkout so the agent can inspect fixture source.
        // No skip-permissions or blanket allowed-tools flags are supplied.
        List<String> command=List.of("claude","-p",PROMPTS.get(scenario),"--output-format","json","--max-turns","25");
        Process p=null;
        int exit=-1; boolean timedOut=false; String failure="";
        try {
            p=new ProcessBuilder(command).directory(root.toFile()).redirectError(stderr.toFile())
                    .redirectOutput(transcript.toFile()).start();
            if(!p.waitFor(480,TimeUnit.SECONDS)) {
                timedOut=true;p.destroy();
                if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
            }
            exit=p.exitValue();
        } catch(Exception e) {failure=e.toString();}
        String outcome=timedOut?"TIMEOUT":exit==0?"REVIEW_REQUIRED":"CLI_FAILED";
        // EXIT 0 is NOT a debugger PASS. Human/code-based grading must review tool evidence.
        String manifest="{\n"+
                "  \"scenario\": "+json(scenario)+",\n"+
                "  \"generatedAt\": "+json(Instant.now().toString())+",\n"+
                "  \"status\": "+json(outcome)+",\n"+
                "  \"exitCode\": "+exit+",\n"+
                "  \"failure\": "+json(failure)+",\n"+
                "  \"transcript\": "+json(transcript.toString())+",\n"+
                "  \"stderr\": "+json(stderr.toString())+",\n"+
                "  \"permissionMode\": \"default (no bypass)\",\n"+
                "  \"humanEvidenceReviewNeeded\": true,\n"+
                "  \"toolInvocationVerified\": false,\n"+
                "  \"runtimeDiagnosisVerified\": false,\n"+
                "  \"cleanupVerified\": false\n}";
        Files.writeString(metadata,manifest,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(scenario+" => "+outcome+"; transcript="+transcript+"; manifest="+metadata);
        if(!outcome.equals("REVIEW_REQUIRED")) throw new IllegalStateException("Claude trial not captured successfully: "+outcome+" "+failure);
    }
}
