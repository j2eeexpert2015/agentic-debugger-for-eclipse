package org.learningfromexperience.bootfixture.orders;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(classes = {OrdersApplication.class, BrokenContextTest.BrokenStartup.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("debug-defect")
class BrokenContextTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenStartup {
        @Bean
        String requiredFixtureSetting() {
            throw new IllegalStateException("P2-05 fixture context failed before the test body");
        }
    }

    @Test
    void requiresContext() {
        fail("Broken-context test body unexpectedly executed");
    }
}
