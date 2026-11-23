# Extract over your existing repository — do not delete it

Target on your Windows machine:

`C:\All_GitHub_Desktop\agentic-debugger-for-eclipse`

The archive paths begin with `dev-e2e/` and `e2e-runner/`. Extract using the normal **overwrite matching files** option. It will not overwrite `dev-e2e/e2e.properties` or production/plugin sources. Existing PowerShell scripts remain untouched.

From that repository root:

```cmd
java dev-e2e/DevE2E.java list
java dev-e2e/DevE2E.java test --groups acceptance,boot-gaps
java dev-e2e/DevE2E.java test --approve-fixture-post
```

Eclipse opens automatically only if a safe dedicated instance is absent. The `test` command never rebuilds/redeploys the plugin. If you want just one test, e.g.:

```cmd
java dev-e2e/DevE2E.java test --tests boot-gaps:two-services
```

See `dev-e2e/ACCEPTANCE-EXPANSION.md` for scenario definitions, safety limits, and report locations.
