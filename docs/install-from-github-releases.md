# Install Agentic Debugger for Eclipse from GitHub Releases

This guide is for people installing the prebuilt Eclipse IDE plugin. **You do not need Maven, Git, or the source repository.**

## 1. Download the correct file

1. Open the project's [GitHub Releases page](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases).
2. Choose a release whose notes indicate that its version and Eclipse environment were tested for your setup. A *preview* is a prerelease; do not assume it is the newest stable version.
3. Under that release's **Assets**, download the file named `agentic-debugger-update-site-*.zip` (where `*` is the actual version), and `SHA256SUMS.txt` if provided.
4. **Do not download** GitHub's automatically generated **Source code (zip)** or **Source code (tar.gz)**: those are source archives, not Eclipse update sites.

**Current distribution note:** The existing `v1.5.0-preview.1` prerelease has an older asset named `agentic-debugger-update-site-1.0.0-SNAPSHOT.zip`. That asset belongs to the *older preview* despite its filename. It must not be confused with a newer ZIP that appears only as an unpublished GitHub Actions artifact. Future approved releases should attach the CI-validated versioned ZIP and checksum as release assets.

## 2. Verify the ZIP (when a checksum is supplied)

Open Windows Command Prompt (CMD), switch to the directory containing the downloaded files, and replace `ACTUAL_ZIP_FILENAME.zip` with the file you downloaded:

```cmd
cd /d "%USERPROFILE%\Downloads"
certutil -hashfile "ACTUAL_ZIP_FILENAME.zip" SHA256
```

Compare the 64-character SHA-256 value printed by `certutil` to the value beside that **same filename** in `SHA256SUMS.txt`. If they differ, **do not install the ZIP**. Re-download from the official release. A checksum detects differences but does not by itself establish the publisher's identity.

The early preview might not supply `SHA256SUMS.txt`; in that case, this checksum comparison cannot be performed. Do not invent or reuse a checksum from another release.

## 3. Install in Eclipse

1. Use **Eclipse IDE for Java Developers** or **Eclipse IDE for Enterprise Java and Web Developers**. Java/JDT debugging is required; Maven/m2e and JUnit tooling are needed for relevant workflows. Use a **JDK 21 or later**, subject to any additional Java requirement of your Eclipse distribution.
2. Open Eclipse and choose **Help → Install New Software… → Add… → Archive…**.
3. Select the downloaded **update-site ZIP directly**. Do not extract it first.
4. Choose **Agentic Debugger for Eclipse IDE**, follow Eclipse's installation prompts, review any publisher/trust warnings, and restart Eclipse when prompted.
5. Confirm installation through **Help → About Eclipse IDE → Installation Details → Installed Software**.

**Compatibility:** Check the specific release notes and its CI evidence. The CI branch has verified Eclipse 2025-12 on Windows; the Eclipse 2026-09 compatibility job must be completed successfully before that version is claimed as CI-verified. Older Eclipse releases and Linux/macOS are not certified by these particular CI runs. A JDK 21 *build* does not by itself prove operation of every Eclipse distribution on Java 21.

## 4. Connect your AI assistant

### Claude Code

1. Install and authenticate Claude Code separately.
2. In Eclipse, open **Window → Preferences → Agentic Debugger** and configure the Claude integration as described there.
3. Keep Eclipse running with a Java project imported. In a new Claude session, inspect `/mcp` for `eclipse-debugger` and invoke `eclipse_ping` (expected `PONG`).

### GitHub Copilot for Eclipse (optional)

1. Install and sign in to GitHub Copilot for Eclipse.
2. Open **Window → Preferences → GitHub Copilot → Model Context Protocol (MCP) → Edit MCP Servers from Plug-ins**, select **Agentic Debugger for Eclipse IDE**, and **Approve** the server if offered/denied.
3. Add/select the project's Agentic Debugger custom agent using its Eclipse project context-menu action where supported.

MCP server approval is **separate** from whether the plugin JARs are signed. The presence of tools in one Copilot mode does not demonstrate mode isolation; use the product's latest acceptance notes for confirmed behavior.

## 5. Try your first debugging investigation

1. Import a Java or Spring Boot project into Eclipse and let Eclipse finish its build.
2. Start a fresh Claude Code session after configuring the MCP integration, or select the Agentic Debugger custom agent in GitHub Copilot once the MCP server has been approved.
3. Ask about a **symptom**, rather than supplying breakpoint locations. For example:

   > My Spring Boot order total is incorrect. Debug the calculation using Eclipse, inspect the relevant values, explain where it goes wrong, and do not modify source code or unrelated running applications.

4. Verify that the assistant used the `eclipse-debugger` MCP tools. Look for the debugging activity and HTML report, and confirm any agent-owned breakpoints and processes were cleaned up.

**Important:** If the assistant only reads source code and guesses the cause, it has not demonstrated an actual runtime debugging investigation. An agent's expression evaluation can execute Java code; review any approval prompt before allowing it. For a real debugging demonstration, use a disposable test project first.

## 6. Updating, uninstalling, and troubleshooting

- **Update:** close active debugging/agent sessions, install the approved new update-site ZIP through Eclipse, restart Eclipse, then start a **new** Claude/Copilot session. The plugin stages its MCP server JAR into `%USERPROFILE%\.eclipse-agentdebug\` on a normal Eclipse start.
- **Uninstall:** **Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall**. Remove the plugin's Claude MCP registration through **Window → Preferences → Agentic Debugger** if desired.
- **Eclipse cannot find installable software:** confirm you selected the asset under **Release → Assets** rather than GitHub's source-code ZIP.
- **Eclipse reports an unsigned publisher:** review the trust warning and decide whether you trust the official release; signing is a separate release-engineering decision, not a bypass for Copilot's MCP approval.
- **Claude cannot reach Eclipse:** confirm Eclipse is running, the correct project is imported, `/mcp` lists `eclipse-debugger`, and a normal Eclipse start staged the runtime JAR. Check that another Eclipse instance is not using the bridge port.
- **Copilot says `Denied`:** approve the MCP server in Copilot preferences. A changed server command configuration may require renewed approval.

For more details see the [main README](../README.md#install-and-use). For what release maintainers must verify before publication, see the [release verification checklist](release-verification-checklist.md).
