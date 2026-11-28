# Working agreement: how Ayan and the assistant work on this repository

Read this before the first reply in a new chat. It describes how work is delivered, checked and recorded. It
complements [development-workflow.md](development-workflow.md) (the engineering procedure) and
[handoff.md](handoff.md) (where the work stands today). Where they differ, this page wins for how we talk and deliver.

## 1. Roles

- **Ayan** (owner) decides what is built, runs every build, deploy and test on his Windows machine, uses GitHub
  Desktop for git, and sends back the output (log, screenshot, report).
- **The assistant** reads the repository, proposes, writes the code and docs, and hands them over. It may compile and
  run unit tests in its own copy, and must then say what it could **not** check (Windows, Eclipse UI, Copilot,
  Claude runs). It never pushes, never commits, never asks Ayan to run git commands.

## 2. One thing at a time

- Work on **one item per round**: one change, one delivery, one set of commands, one thing for Ayan to check.
  Do not mix a code delivery with a side question, a second item and a check of something else in one message.
- If something new comes up while working (a bug seen in a log, a model mistake, an idea), write it down as a
  README row or one line under "Noticed, not started" in the handoff, say so in one sentence, and carry on.
- Keep messages short. Lead with the result or the one action Ayan must take. No long status recaps.
- Before UI or look changes, show a **demo first** (a small HTML page or a screenshot of the idea), let Ayan choose,
  then change the code. Never change existing pages more than the agreed item needs.
- Be honest when not sure: say "not checked", "I think", or "I don't know" rather than present a guess as fact.

## 3. A delivery

1. **Complete files only, never git patches.** Several files go in a **zip that keeps the repository structure**,
   extracted over the repository root. Every zip has a **new name** (e.g. `open-23-step4c.zip`, then `...4d.zip`),
   files touched and zipped with the India time zone.
2. **A full change list** in the message, for every file: the file, where in it, before and after (short), and why.
3. **"Trial needed: yes/no"** with the reason (yes when anything the agent reads or does changes; see
   development-workflow §6).
4. **The commands** (section 4): one line each, with a line of note before it.
5. **What the assistant checked and what it could not.**
6. Update `docs/handoff.md` (a "Last delivery" section) and the tracking table in `STATUS.md` with each delivery.

Never in a delivery: `dev-e2e/e2e.properties` (machine-specific, never committed), anything under `examples/` (the
buggy fixtures stay unchanged), generated files, secrets.

## 4. Commands

Every command for Ayan is **one line** to paste into `cmd` (Ayan 28 Nov: multi-line pastes get cut or leave the last
line waiting for Enter), with **one line of note before it** saying what it does. The ready lines for every
scenario are in **[local-commands.md](local-commands.md)**; take them from there and keep that page up to date.

- Start with `cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse`, join the steps with `&&` (the line stops
  at the first failure). A step that may fail without stopping the rest uses `&`.
- Maven as `call mvn ...` (without `call`, the line ends after Maven). Install into the test Eclipse and start it
  with `java dev-e2e/DevE2E.java deploy`; start only with `java dev-e2e/DevE2E.java start`.
- Unpack a zip with `tar -xf "%USERPROFILE%\Downloads\<zip>" -C .`.
- When the test Eclipse must be closed (deploy, file locks), the line starts (after `cd`) with
  `call dev-e2e\close-test-eclipse.cmd`: it closes **only** the test Eclipse, gracefully, and stops when a dialog is
  open in it. Never kill it with `Stop-Process`: a killed Eclipse does not save its workspace (removed breakpoints
  come back) nor run the plugin's shutdown clean-up (28 Nov).
- A `findstr` guard, when one is needed, has no `\"` in its search string.
- Before the integration tests: Run > Remove All Breakpoints in the test Eclipse (a `pause` step in the line), see
  handoff.md §2.
- No git commands. Deletions only with `del /q` / `rmdir /s /q`, and only when the delivery says so.
- For a Copilot or Claude check inside Eclipse, give the exact prompt to paste.

Typical round (code change), local-commands.md 1a:

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd && tar -xf "%USERPROFILE%\Downloads\<zip>" -C . && call mvn clean package -DskipTests && java dev-e2e/DevE2E.java deploy
```

## 5. Recording

- The **"Status and tracking"** table in `STATUS.md` is the single tracking list. New items get the next `OPEN-nn` number;
  a row's last cell holds the status and the evidence ("Done, checked <date time>" plus what was seen).
- `docs/handoff.md` says where the work stands for the next chat; older deliveries live in the git history.
- Findings about outside tools go in their own document (e.g. `docs/copilot-integration-notes.md`,
  `docs/copilot-mode-list-bug.md`), linked from the README row.

## 6. Product rules that affect every change

- **Do not intrude on users who do not use our tools:** nothing is written into a user's projects or home folder
  unless the user asked for it (menu, preferences button); quiet by default (`AGENTDEBUG_VERBOSE` for extra stderr).
- Safety is enforced in code (ownership of breakpoints, launches, JVMs); the skill and agent text are guidance only.
- Windows is the only system Ayan can test. Nothing untested ships for Linux/macOS; CI (GitHub Actions) is how we get
  evidence there (README OPEN-54..56, `docs/ci.md`).

## 7. Starting a new chat

Ayan attaches or links the repository (branch with the latest commits) and these two files, and says what comes next.
The assistant then:

1. reads `docs/working-agreement.md`, `docs/handoff.md`, the tracking table in `STATUS.md` and the documents the next item
   links to;
2. asks for the output of the last check if the handoff says one is pending and it was not given;
3. continues with the single next step from the handoff, following sections 2 to 4.
