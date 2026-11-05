package com.planmate.discovery;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PlanMate discovery: finds upcoming public events in public sources (one adapter per source, each on its own
 * schedule), keeps track of them in its own "discovery" database, lets Gemini write short descriptions for new and
 * changed ones, and publishes every new, changed or cancelled event to the discovery.events topic, where the
 * PlanMate backend picks them up.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class DiscoveryApplication {

	public static void main(String[] args) {
		SpringApplication.run(DiscoveryApplication.class, args);
	}

	/** The time, replaceable in tests. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
