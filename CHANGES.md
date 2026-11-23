# Exact replacement files

- `dev-e2e/DevE2E.java` — replace with updated selectors; keeps original compile/deploy and scenarios
- `dev-e2e/ClaudeSymptomTrial.java` — new, opt-in, captures actual Claude CLI output
- `e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/BootGapVerifier.java` — replace with additive 4 scenarios
- `examples/spring-boot-debugging/src/main/java/org/learningfromexperience/bootfixture/failures/{StartupFailureApplication,NeverReadyApplication,PortConflictApplication}.java` — 3 new disposable fixture mains
- `EXTRACT-AND-RUN.md` — instructions for migration and honest acceptance status

No other source or project files overwritten; no removal or changes to `.gitignore`, pom.xml, README.md, installed skill, or PowerShell scripts.
