# Spring Boot debugger fixture

Import this directory into Eclipse as an existing Maven project. It uses Java 21 or later, Spring Boot 3.5.16 and JUnit Jupiter. Maven runs the fixture independently of the debugger distribution.

`OrdersApplication` serves `POST /orders/quote`. It binds to loopback on an ephemeral port. Use the port in the “Tomcat started on port” message for that launch. The sibling `InventoryApplication` supplies a second Boot main for discovery and target-selection tests.

Run the smoke checks from this directory with `mvn test`. They cover a plain service call and a real HTTP request against an embedded server that rejects a zero quantity.

## Verifier oracle

Keep this section out of symptom prompts. The fixture intentionally uses integer division to compute the discount rate. With quantity 2, unit price 60 and discount percent 10, the first incorrect local is `rate=0`. The HTTP response has status 200 and total 120.00. The expected total is 108.00. A quantity of zero instead produces HTTP 400 with the error “Quantity must be positive”.

Run the two reproductions separately from the smoke checks. Both assertions should fail with expected 108.00 and actual 120.00.

```sh
mvn "-DexcludedGroups=unused" "-Dtest=OrderServiceTest#calculatesDiscount,OrderApiIntegrationTest#quoteAppliesDiscount" test
```

In Eclipse, debug either selected JUnit method and set a breakpoint on the subtotal calculation in `OrderService.quote`. The plain test calls the service directly. The Spring-context test sends a real HTTP request, so its service breakpoint occurs on a web-server thread.

## Replaying the HTTP investigation on Windows

Use a disposable Eclipse workspace with the fixture imported. Set breakpoints on the controller's call to `service.quote`, the repository's `return requestedPercent`, and the service's subtotal calculation. In a separate PowerShell 7 terminal at the debugger repository root, start the [fixture client](../../e2e-runner/boot-tests/Invoke-FixtureQuote.ps1) with the workspace path, debugger bridge port, exact launch name and a fresh receipt path. For example:

```powershell
.\e2e-runner\boot-tests\Invoke-FixtureQuote.ps1 -Workspace C:\test\boot-workspace -BridgePort 21765 -LaunchName 'Orders Test' -ApproveFixturePost -Receipt C:\test\quote-result.json
```

Start that saved launch in a NORMAL investigation. The client waits up to 120 seconds for readiness, checks that the loopback listener belongs to the JVM reported by that launch, then submits the fixed quote once. Inspect and resume the HTTP thread at each stop. After the final resume, read the receipt for the status and body and finish the investigation. The client disables proxies and redirects and gives the request 180 seconds to complete.
