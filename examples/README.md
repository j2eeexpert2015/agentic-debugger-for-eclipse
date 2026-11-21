# Java debugging fixture

The E2E runner creates this project automatically in the dedicated PDE Runtime Eclipse workspace. **Manual import is not needed** for `mvn verify -Pe2e`.

Project: `AgenticDebuggerCalculatorE2E`  
Launch configuration: `AverageCalculatorApplication`  
Entry point: `com.learningfromexperience.debugsample.AverageCalculatorApplication`  
Debugger target: `com.learningfromexperience.debugsample.service.AverageCalculator`, line **10** (`sum += number`).

At the first breakpoint hit: `sum = 0`, `number = 10`. After one step-over: `sum = 10`. Total is 61 and buggy average is `20.0` (integer division); correct average is about `20.3333333333`.

For manual inspection only, import `examples/average-calculator-debug-sample` using **Existing Projects into Workspace**. Do not import this copy into the same workspace in which the E2E runner automatically creates the fixture.
