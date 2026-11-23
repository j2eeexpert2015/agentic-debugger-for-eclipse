package org.learningfromexperience.bootfixture.orders;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated test fixture endpoint: proves which JVM serves the local HTTP port. */
@RestController
final class FixtureIdentityController {
    @GetMapping("/agentdebug-fixture/identity")
    Map<String, Object> identity() {
        return Map.of("application", "OrdersApplication", "pid", ProcessHandle.current().pid());
    }
}
