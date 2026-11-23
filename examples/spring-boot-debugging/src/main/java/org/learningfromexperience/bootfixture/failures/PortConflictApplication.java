package org.learningfromexperience.bootfixture.failures;

import java.net.InetAddress;
import java.net.ServerSocket;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;

/** E2E-only: occupy an ephemeral loopback port in the same fixture JVM. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PortConflictApplication {
    public static void main(String[] args) throws Exception {
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            reservation.setReuseAddress(false);
            int port = reservation.getLocalPort();
            System.err.println("AGENTDEBUG_FIXTURE_PID=" + ProcessHandle.current().pid());
            System.err.println("AGENTDEBUG_RESERVED_LOOPBACK_PORT=" + port);
            System.err.flush();
            SpringApplication app = new SpringApplication(PortConflictApplication.class);
            try (var context = app.run("--server.address=127.0.0.1", "--server.port=" + port)) {
                System.err.println("AGENTDEBUG_UNEXPECTED_PORT_CONFLICT_SUCCESS");
                System.err.flush();
            } catch (RuntimeException expectedFailure) {
                System.err.println("AGENTDEBUG_FIXTURE_PID=" + ProcessHandle.current().pid());
                System.err.println("AGENTDEBUG_EXPECTED_PORT_CONFLICT port=" + port);
                System.err.flush();
                // Ensure the intentionally failed fixture does not keep any non-daemon
                // Spring/Tomcat threads alive, while allowing output collection first.
                Thread.sleep(1500);
                System.exit(43);
            }
        }
    }
}
