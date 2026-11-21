package org.learningfromexperience.eclipse.agentdebug.integration;
import java.nio.file.Path;
/**
 * Provides ClaudeRegistrationTest support for the Agentic Debugger integration.
 *
 * @author Ayan Dutta
 */
class ClaudeRegistrationTest {
  static void check(boolean condition, String label) { if(!condition) throw new AssertionError(label); }
  public static void main(String[] args) {
    Path home=Path.of("C:\\Users\\vmadmin");
    String old="eclipse-debugger:\n  Scope: User config (available in all your projects)\n  Status: ✓ Connected\n  Type: stdio\n  Command: C:\\Java\\bin\\java.exe\n  Args: -jar C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar\n";
    ClaudeRegistration r=ClaudeRegistration.parse(old);
    check(r.legacyJar(home,true), "previous installer recognized");
    check(!r.exactJar(home.resolve(".eclipse-agentdebug/agentic-debugger-mcp.jar")), "not same jar");
    check(!ClaudeRegistration.parse(old.replace("agentic-debugger-for-eclipse", "foreign-product")).legacyJar(home,true),"foreign unchanged");
    check(!ClaudeRegistration.parse(old.replace("Scope: User", "Scope: Local")).legacyJar(home,true),"project/local unchanged");
    check(!ClaudeRegistration.parse(old.replace("Type: stdio", "Type: http")).legacyJar(home,true),"http unchanged");
    String newer=old.replace("C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar", "C:\\Users\\vmadmin\\.eclipse-agentdebug\\agentic-debugger-mcp.jar");
    check(ClaudeRegistration.samePath(ClaudeRegistration.parse(newer).registeredJar(), "C:\\Users\\vmadmin\\.eclipse-agentdebug\\agentic-debugger-mcp.jar"), "current managed path recognized");
    check(!ClaudeRegistration.parse(old.replace("mcp-server.jar", "mcp-server.jar -Xfoo")).legacyJar(home,true), "extra args cannot migrate");
    System.out.println("PASS: six registration recognition/migration safeguards");
  }
}
