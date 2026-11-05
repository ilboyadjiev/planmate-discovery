package com.planmate.discovery.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything configurable, from application.yml (environment variables override it on the server).
 *
 * staleAfter: an event its source stopped returning counts as cancelled after this long, but only if the source
 * fetched successfully in the meantime.
 */
@ConfigurationProperties("discovery")
public record DiscoveryProperties(
		@DefaultValue("24h") Duration staleAfter,
		@DefaultValue Sources sources,
		@DefaultValue Ai ai,
		@DefaultValue Topics topics,
		@DefaultValue Outbox outbox) {

	public record Sources(@DefaultValue Ticketmaster ticketmaster, @DefaultValue Curated curated) {
	}

	/** Ticketmaster Discovery API: public events in the given cities. */
	public record Ticketmaster(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("0 0 */6 * * *") String cron,
			String apiKey,
			@DefaultValue("https://app.ticketmaster.com/discovery/v2") String baseUrl,
			@DefaultValue("Munich") List<String> cities,
			@DefaultValue("DE") String countryCode,
			@DefaultValue("60d") Duration lookAhead,
			@DefaultValue("100") int pageSize,
			@DefaultValue("5") int maxPages,
			@DefaultValue("250ms") Duration pause) {
	}

	/** A hand-written YAML file: Steam sales, game releases and other one-off events. */
	public record Curated(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("0 30 3 * * *") String cron,
			@DefaultValue("classpath:curated/curated-events.yml") String file) {
	}

	/** Gemini, on its own free-tier key, for short descriptions of new and changed events. */
	public record Ai(
			@DefaultValue("true") boolean enabled,
			String apiKey,
			@DefaultValue("gemini-3.5-flash-lite") String model,
			@DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
			@DefaultValue("15") int batchSize,
			@DefaultValue("300") int dailyLimit,
			@DefaultValue("5s") Duration pauseBetweenRequests,
			@DefaultValue("classpath:prompts/event-description-refinement-prompt.txt") String prompt) {
	}

	public record Topics(
			@DefaultValue("discovery.events") String events,
			@DefaultValue("discovery.events.dlq") String deadLetter,
			@DefaultValue("3") int partitions) {
	}

	/** How often waiting messages are sent to Redpanda, and how many at a time. */
	public record Outbox(@DefaultValue("5s") Duration publishInterval, @DefaultValue("100") int batchSize) {
	}
}
