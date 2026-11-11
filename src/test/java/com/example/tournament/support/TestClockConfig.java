package com.example.tournament.support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC));
    }
}
