# Java Debugging Sample

`average-calculator-debug-sample` is an optional two-class Java project for manual demonstrations. Import it into Eclipse with **Existing Projects into Workspace**.

The automated `mvn verify -Pe2e` / `-Pinstalled-e2e` tests create their own isolated sample project; do not import this one for those tests.

To check the new automatic launch tools manually, create a Java project with a `main` class **without** using Run As first. Ask Claude to call `list_java_applications`, then `start_java_application` with the explicit project name and fully qualified main class. Set the breakpoint before launching to catch short-running programs.
