package org.learningfromexperience.bootfixture.failures;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;

/** A running Spring context with no listening web server, for bounded readiness negatives. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class NeverReadyApplication {
    public static void main(String[] args) throws InterruptedException {
        SpringApplication app = new SpringApplication(NeverReadyApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            System.out.println("AGENTDEBUG_NONWEB_STARTED");
            Thread.sleep(120_000);
        }
    }
}
