# Spring Boot debugger fixture

Import this directory into Eclipse as an existing Maven project. It uses Java 21 or later, Spring Boot 3.5.16 and JUnit Jupiter. Maven runs the fixture independently of the debugger distribution.

`OrdersApplication` serves `POST /orders/quote`. It binds to loopback on an ephemeral port. The sibling `InventoryApplication` supplies a second Boot main for discovery and target-selection tests. The fixture-only `/__agentdebug/identity` endpoint supports JVM identity verification before the authorized test HTTP request.

Run fixture smoke checks here with `mvn test`. They cover a service call and a real HTTP request against an embedded server that rejects zero quantity.

## Verifier oracle

The fixture intentionally uses integer division to compute the discount rate. With quantity 2, unit price 60 and discount percent 10, the first incorrect local is `rate=0`. The HTTP response has status 200 and total 120.00; expected total is 108.00. A quantity of zero instead yields HTTP 400 with `Quantity must be positive`.

Reproduce the known failing JUnit methods separately:

```sh
mvn "-DexcludedGroups=unused" "-Dtest=OrderServiceTest#calculatesDiscount,OrderApiIntegrationTest#quoteAppliesDiscount" test
```

## Java end-to-end debugging

From the **repository root**, with a disposable Eclipse test installation configured in `dev-e2e/e2e.properties`:

```sh
java dev-e2e/DevE2E.java test --groups phase2,boot,boot-quote --approve-fixture-post
```

To run the HTTP quote defect alone:

```sh
java dev-e2e/DevE2E.java test --groups boot-quote --approve-fixture-post
```

The Java test runner starts/reuses the configured dedicated Eclipse workspace, checks the bridge and target JVM identity, places appropriate application breakpoints, performs one approved loopback POST through the Java HTTP client, verifies actual Java frames and HTTP response, and cleans up only owned resources. The command does not automatically install a new plugin; use `all` when a rebuild/redeploy is needed and close the configured Eclipse installation first.
