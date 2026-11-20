# Agentic Debugger for Eclipse IDE

An independent MCP integration for AI-assisted Java debugging in Eclipse IDE.

## Prerequisites

- JDK 25 for this initial Eclipse 2026-09/Tycho build (the MCP server itself targets Java 21)
- Maven
- Claude Code CLI installed and available in your terminal

## Build and verify locally

After downloading and extracting the repository, open a terminal in the **repository root** (`agentic-debugger-for-eclipse`).

**1. Build the Eclipse plugin, update site, MCP server and installer**

```bash
mvn package
```

Expected: `BUILD SUCCESS`. The installer is created at `installer/target/agentic-debugger-setup.jar`. The Eclipse p2 repository ZIP is created at `eclipse-update-site/target/agentic-debugger-update-site-1.0.0-SNAPSHOT.zip`.

**2. Install the plugin into Eclipse IDE**

1. Open **Eclipse IDE 2026-09**.
2. Select **Help → Install New Software → Add → Archive**.
3. Choose `eclipse-update-site/target/agentic-debugger-update-site-1.0.0-SNAPSHOT.zip` from the extracted repository.
4. Select **Agentic Debugger for Eclipse IDE**, complete installation, and restart Eclipse.
5. Verify under **Help → About Eclipse IDE → Installation Details → Plug-ins**.

The initial build targets Eclipse **2026-09**. Eclipse 2026-06 has **not** been verified because the existing plugin manifest specifies newer Eclipse bundle dependencies.

**3. Install the MCP server and configure Claude Code**

From the **repository root**, run:

```bash
java -jar installer/target/agentic-debugger-setup.jar
```

The Java installer finds the appropriate user-specific installation directory and registers `eclipse-debugger` automatically. If an existing registration is found, confirm whether to replace it. No PowerShell scripts, hardcoded user paths or manual Claude configuration edits are required.

**4. Verify the MCP installation**

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

> The Java setup utility configures the MCP server, but does not install the Eclipse plugin. The Eclipse p2 installation in Step 2 is required for live debugging. `check` verifies local MCP registration, not the live Eclipse debugger connection.

## Project structure

- `eclipse-plugin/` — Eclipse IDE plugin source
- `mcp-server/` — Java MCP server source
- `installer/` — Cross-platform Java setup utility
- `eclipse-feature/` — Installable Eclipse feature
- `eclipse-update-site/` — p2 repository and installable ZIP
- `pom.xml` — Coordinates Maven/Tycho modules

## Trademark notice

This is an independent project, not affiliated with or endorsed by the Eclipse Foundation. Eclipse is a trademark of Eclipse Foundation AISBL.
