package com.planmate.discovery.source.google;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.pipeline.DiscoveryPipeline;

/**
 * Runs each configured Google calendar as its own source, one after the other, like the web feeds. Off until the
 * OAuth credentials are set and at least one calendar is configured.
 */
@Component
public class GoogleCalendarRunner {

	private final DiscoveryProperties.GoogleCalendar config;
	private final GoogleCalendarClient client;
	private final DiscoveryPipeline pipeline;
	private final Clock clock;

	public GoogleCalendarRunner(DiscoveryProperties properties, GoogleCalendarClient client,
			DiscoveryPipeline pipeline, Clock clock) {
		this.config = properties.sources().googleCalendar();
		this.client = client;
		this.pipeline = pipeline;
		this.clock = clock;
	}

	public boolean enabled() {
		return config.enabled() && client.configured() && !sources().isEmpty();
	}

	public String cron() {
		return config.cron();
	}

	public void runAll() {
		boolean first = true;
		for (GoogleCalendarSource source : sources()) {
			if (!first) {
				try {
					Thread.sleep(config.pause().toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			first = false;
			pipeline.run(source);
		}
	}

	List<GoogleCalendarSource> sources() {
		List<GoogleCalendarSource> sources = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (DiscoveryProperties.Calendar calendar : config.calendars() == null ? List.<DiscoveryProperties.Calendar>of() : config.calendars()) {
			validate(calendar, keys);
			sources.add(new GoogleCalendarSource(calendar, config, client, clock));
		}
		return sources;
	}

	private static void validate(DiscoveryProperties.Calendar calendar, Set<String> keys) {
		if (calendar.key() == null || !calendar.key().matches("[a-z0-9][a-z0-9-]{1,40}")) {
			throw new IllegalArgumentException("Google calendar key must be 2-41 lower case letters, digits and dashes: " + calendar.key());
		}
		if (!keys.add(calendar.key())) {
			throw new IllegalArgumentException("Google calendar key used twice: " + calendar.key());
		}
		if (calendar.name() == null || calendar.name().isBlank()) {
			throw new IllegalArgumentException("Google calendar " + calendar.key() + " needs a name");
		}
		if (calendar.calendarId() == null || calendar.calendarId().isBlank()) {
			throw new IllegalArgumentException("Google calendar " + calendar.key() + " needs a calendar-id");
		}
	}
}
