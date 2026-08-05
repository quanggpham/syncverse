package com.internship.syncverse.server;

import com.internship.syncverse.server.config.SyncProperties;
import com.internship.syncverse.server.session.SessionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SyncVerseServer {

    public static void main(String[] args) {
        if (args.length != 1 || args[0].startsWith("--")) {
            throw new IllegalArgumentException("Usage: java -jar server.jar <serverName>");
        }
        SpringApplication.run(SyncVerseServer.class,
                "--syncverse.server-name=" + args[0]);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SessionService sessionService(Clock clock, SyncProperties properties) {
        return new SessionService(clock, properties.sessionExpiry());
    }
}
