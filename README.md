# Agentic Debugger for Eclipse IDE — Source Repository (Initial Layout)

**Product name:** Agentic Debugger  
**Descriptive title:** Agentic Debugger for Eclipse IDE  
**Repository:** `agentic-debugger-for-eclipse`

This is an early source layout for an independent Java debugging extension for Eclipse IDE. It includes the Eclipse plugin, MCP server and a portable Java installer. It does not yet contain an Eclipse feature, p2 update site, full integration tests, examples, release workflows, or a separate documentation tree.

## Layout

```text
agentic-debugger-for-eclipse/
├── README.md
├── .gitignore
├── pom.xml
├── eclipse-plugin/
│   ├── .project
│   ├── .classpath
│   ├── META-INF/MANIFEST.MF
│   ├── plugin.xml
│   ├── build.properties
│   └── src/org/learningfromexperience/eclipse/agentdebug/
│       ├── Activator.java
│       ├── AgentDebugStartup.java
│       ├── bridge/EclipseBridgeServer.java
│       └── debug/
│           ├── EclipseDebugService.java
│           ├── EclipseBreakpointService.java
│           └── EclipseAgentOperations.java
├── mcp-server/
    ├── pom.xml
    └── src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/
        └── DebugMcpServer.java
└── installer/
    ├── pom.xml
    └── src/main/java/org/learningfromexperience/agentdebug/setup/
        ├── SetupApplication.java
        ├── InstallPaths.java
        ├── JavaRuntimeDetector.java
        ├── McpServerInstaller.java
        └── ClaudeCodeConfigurator.java
```

## What this source is

- Java sources are copied from the **previously prepared 16-tool integration candidate**, preserving their packages and implementation.
- `mcp-server/pom.xml` retains Java `--release 21`, MCP SDK BOM `2.0.1` and Maven Shade packaging from that candidate. Its Maven artifact ID is renamed to `agentic-debugger-mcp`, so the JAR name and any existing Claude Code configuration pointing to the older JAR name must be updated.
- `eclipse-plugin/META-INF/MANIFEST.MF` retains `JavaSE-25` and its current Eclipse dependency minima. **The plugin has not yet been converted to Java 21 or verified in Eclipse 2026-06.**
- Eclipse PDE metadata (`.project`, `.classpath`, `plugin.xml`, `build.properties`) is supplied for this repository layout because those files were not part of the previously prepared candidate. Check that startup extension and bundle contents match your latest known-good original project before replacing any installed plugin.
- This archive was **not compiled or integration-tested in this environment**. The user's previous end-to-end test of the 16-tool implementation does not constitute verification of this newly assembled project ZIP.

## Import the Eclipse plugin

1. In Eclipse, use **File → Import → General → Existing Projects into Workspace**.
2. Choose the `agentic-debugger-for-eclipse` directory as the root and import `org.learningfromexperience.eclipse.agentdebug`.
3. Make sure the Eclipse target platform contains JDT Debug, Eclipse Debug Core and UI bundles and the IDE is running on a compatible JDK.
4. Inspect **Problems** and verify the plugin compiles before installation. The provided PDE metadata targets **JavaSE-25**, as the prior manifest does.
5. For development, use **Run As → Eclipse Application**; remember that a launched runtime Eclipse is a **separate IDE process** and cannot see debug launches in the host IDE.
6. For debugging applications in your normal Eclipse IDE, install the plugin **in that host IDE**, restart it and confirm the plugin appears in Installation Details.

## Build the MCP server and setup utility

Requirements: Maven and a compatible Java JDK (the configured output bytecode level is Java 21).

From the repository root, run:

```text
mvn package
```

The root Maven reactor builds the shaded MCP JAR first, then embeds it into `installer/target/agentic-debugger-setup.jar`. **Stop running Java/MCP processes that lock the old JAR before using `mvn clean`.** The Eclipse PDE plugin is deliberately **not** part of this Maven reactor yet; it is imported into Eclipse separately until the Tycho build is introduced.

## Install and register Claude Code — no paths to configure

With Java 21 or newer and Claude Code CLI available, run the packaged utility from the `installer/target` directory:

```text
java -jar agentic-debugger-setup.jar
```

This installs the MCP server into an OS-appropriate *per-user application data directory*, detects the Java executable used for setup, and registers the user-scoped `eclipse-debugger` MCP server using the official Claude Code CLI. No PowerShell scripts, user-entered absolute paths or manual `.claude.json` edits are needed. If `eclipse-debugger` already exists, setup asks before replacing it; for non-interactive replacement use `install --replace`.

To verify registration or remove this installer-managed integration:

```text
java -jar agentic-debugger-setup.jar check
java -jar agentic-debugger-setup.jar uninstall
```

The setup utility **does not install the Eclipse PDE plugin**. It also does not edit or remove other MCP server registrations. Restart Claude Code after installing. The installer has not yet been tested on Windows/macOS in this environment.

## Run and verify

1. Start the Eclipse IDE where the plugin is installed. Check its console for bridge startup on `127.0.0.1:8765`.
2. Run the Java setup utility as described above; avoid committing machine-specific configuration or token files.
3. Restart Claude Code and check `/mcp` for a connected `eclipse-debugger` server exposing the expected tools.
4. Ask Claude Code to list Eclipse sessions and launch configurations, then start a saved Java debug configuration. Do not infer that an empty session list means the bridge is broken: no sessions is a valid initial state.
5. Verify suspension, stack and local variables after a breakpoint is actually hit. Preserve existing user-created breakpoints.

## Next stages (not included in this ZIP)

After validating this source layout against your latest working projects: establish the target Eclipse 2026-06/2026-09 compatibility matrix; add the Eclipse feature, p2 update site, full integration tests and automated release packaging as **later changes**.

## Caution

This is a source migration package, **not an installable p2 update site**. Do not treat it as a production release. Review the generated PDE project metadata against your original project before modifying an installed plugin.

## Naming and trademark notice

Agentic Debugger is an independent project for use with Eclipse IDE. It is not affiliated with, endorsed by, or sponsored by the Eclipse Foundation. Eclipse is a trademark of Eclipse Foundation AISBL. Namespaces such as `org.learningfromexperience.eclipse.agentdebug` and Java class names mentioning Eclipse are **technical integration identifiers**, retained to preserve package/bundle compatibility; they do not signify Eclipse Foundation ownership. Do not use the Eclipse logo as the product logo without reviewing the official trademark policy.
