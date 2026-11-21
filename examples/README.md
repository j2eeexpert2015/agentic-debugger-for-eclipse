# Claude Code + Eclipse Debugger Smoke Test

Import `agentic-debugger-smoke-test` into the **same Eclipse workspace in which the debugger bridge is running** using **File → Import → General → Existing Projects into Workspace**. Select `examples/agentic-debugger-smoke-test` as the root directory and click Finish.

Confirm **Run → Debug Configurations → Java Application → TestDebug** exists, has Project `AgenticDebuggerSmokeTest`, and Main class `test.TestDebug`. The supplied `TestDebug.launch` file should provide these values. If it does not appear, select the project in Package Explorer and choose **Run As → Java Application** once, then verify its Main class is `test.TestDebug`; do not leave a launch targeting unqualified `TestDebug`.

Open Claude Code in a terminal and paste `../CLAUDE_E2E_PROMPT.txt` (or copy the text from that file). This is a **manual Claude-driven end-to-end test**; it cannot be verified by Maven compilation alone. See the repository root README for the PDE runtime workbench setup.

Source marker: `src/test/TestDebug.java:8` is the intended breakpoint line. The program is deliberately incorrect (integer division); it prints `Average: 20.0` when allowed to finish. **Never interpret that intentional defect as a plugin failure.**
