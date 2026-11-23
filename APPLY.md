# Apply Claude-first roadmap priorities

**Source baseline:** `j2eeexpert2015/agentic-debugger-for-eclipse` current README, development roadmap, feature tracker, and evaluation plan reviewed on 23 November 2026. The package changes documentation only; it does not change Eclipse plugin, MCP, tests, .gitignore or configuration.

1. Extract this ZIP into your existing repository, preserving its other files. `README.md` is the complete concise replacement.
2. From repository root, run `java maintenance/ApplyClaudeFirstRoadmap.java`. It updates `docs/development-roadmap.md`, `docs/feature-roadmap.md` and `docs/evaluation-plan.md` without replacing historical phase evidence. Run twice if desired: the second execution should report `UNCHANGED`.
3. Check `git diff --check` and `git diff --stat`, then review `git diff -- README.md docs/development-roadmap.md docs/feature-roadmap.md docs/evaluation-plan.md`.
4. The `maintenance/ApplyClaudeFirstRoadmap.java` file is a one-time documentation utility. Delete it after the update and review, or leave it locally untracked; there is no need to commit it.
5. Commit the four resulting documentation changes after review.

**Important:** Do not rerun `dev-e2e/UpdateRoadmapDocs.java` as a supposed general status updater; it was written for a previous milestone and is not needed here. Do not claim the three symptom-only CLI trials passed until their actual transcripts have been assessed.
