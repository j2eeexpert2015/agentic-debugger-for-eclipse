package org.learningfromexperience.bootfixture.failures;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;

/** A deliberately failing E2E-only Boot fixture, never used by the Orders application. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class StartupFailureApplication {
    @Bean
    Object failOnPurpose() {
        // Print before Boot's potentially long exception trace; the bridge currently
        // exposes only the first part of each Eclipse process-output stream.
        System.err.println("AGENTDEBUG_FIXTURE_PID=" + ProcessHandle.current().pid());
        System.err.println("AGENTDEBUG_EXPECTED_STARTUP_FAILURE bean-creation");
        System.err.flush();
        throw new IllegalStateException("AGENTDEBUG_EXPECTED_STARTUP_FAILURE");
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            SpringApplication.run(StartupFailureApplication.class, args);
            System.err.println("AGENTDEBUG_UNEXPECTED_STARTUP_SUCCESS");
            System.err.flush();
        } catch (RuntimeException expectedFailure) {
            System.err.println("AGENTDEBUG_FIXTURE_PID=" + ProcessHandle.current().pid());
            System.err.println("AGENTDEBUG_EXPECTED_STARTUP_FAILURE caught-at-main");
            System.err.flush();
            // Let the Eclipse process monitor collect the output before this fixture exits.
            Thread.sleep(1500);
            System.exit(41);
        }
    }
}
