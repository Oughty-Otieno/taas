package com.taas;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaasApplication.class, args);
    }

    /** Every timestamp comes from here so tests can move time (reservation expiry). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
