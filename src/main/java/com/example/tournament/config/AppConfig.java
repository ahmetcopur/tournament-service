package com.example.tournament.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GameProperties.class)
public class AppConfig {

    /** All game time goes through this clock, so tests can move time to 20:00 and back. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
