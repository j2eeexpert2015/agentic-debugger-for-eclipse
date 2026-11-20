# Agentic Debugger for Eclipse IDE

An independent MCP integration for AI-assisted Java debugging in Eclipse IDE.

## Prerequisites

- Java 21 or newer
- Maven
- Claude Code CLI installed and available in your terminal

## Build and verify locally

After downloading and extracting the repository, open a terminal in the **repository root** (`agentic-debugger-for-eclipse`).

**1. Build the MCP server and Java installer**

```bash
mvn package
```

Expected: `BUILD SUCCESS`. The installer is created at `installer/target/agentic-debugger-setup.jar`.

**2. Install the MCP server and configure Claude Code**

From the **repository root**, run:

```bash
java -jar installer/target/agentic-debugger-setup.jar
```

The Java installer finds the appropriate user-specific installation directory and registers `eclipse-debugger` automatically. If an existing registration is found, confirm whether to replace it. No PowerShell scripts, hardcoded user paths or manual Claude configuration edits are required.

**3. Verify the MCP installation**

Still from the **repository root**, run:

```bash
java -jar installer/target/agentic-debugger-setup.jar check
```

Expected: MCP server installed, installer-managed configuration present, and Claude Code MCP registration present.

**Alternative — run from the installer folder**

If you prefer changing directories, use:

```bash
cd installer/target
java -jar agentic-debugger-setup.jar
java -jar agentic-debugger-setup.jar check
```

Use **either** the root-relative commands or the alternative above; there is no need to run both.

> **Eclipse is not needed for these three commands.** They verify the build, installation and Claude Code registration, **not** a live MCP connection or Java debugging. To test actual debugging, the plugin must be installed and running inside Eclipse IDE.

## Project structure

- `eclipse-plugin/` — Eclipse IDE plugin source
- `mcp-server/` — Java MCP server source
- `installer/` — Cross-platform Java setup utility
- `pom.xml` — Builds the MCP server and installer in order

## Trademark notice

This is an independent project, not affiliated with or endorsed by the Eclipse Foundation. Eclipse is a trademark of Eclipse Foundation AISBL.
