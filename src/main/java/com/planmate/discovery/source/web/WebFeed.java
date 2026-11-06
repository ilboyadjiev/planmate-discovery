package com.planmate.discovery.source.web;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import com.planmate.discovery.model.Location;

/**
 * One entry of web-feeds.yml: a venue's or a city's event calendar.
 *
 * id: short and stable (lower case, digits, dashes); its events are stored as "web-{id}-...", so never rename it.
 * name: shown under each event ("Source: Muffatwerk").
 * format: auto (iCal, else schema.org event data, else the AI reads the page), ics, json-ld or ai.
 * timeZone: for times given without one (default Europe/Berlin).
 * location: the venue, or just the city, for events whose own place is missing or incomplete.
 * tags: added to every event, e.g. [ "#concert" ].
 */
public record WebFeed(String id, String name, String url, String format, String timeZone, Location location,
		List<String> tags) {

	public enum Format {
		AUTO, ICS, JSON_LD, AI
	}

	public WebFeed {
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** Checks the entry; throws with a message naming the entry and the mistake. */
	void validate() {
		if (id == null || !id.matches("[a-z0-9][a-z0-9-]{1,40}")) {
			throw new IllegalArgumentException("Web feed id must be 2-41 lower case letters, digits and dashes: " + id);
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Web feed " + id + " needs a name");
		}
		if (url == null || !url.matches("(?i)https?://[^\\s/]+.*")) {
			throw new IllegalArgumentException("Web feed " + id + " needs an http(s) url");
		}
		formatValue();
		zone();
	}

	Format formatValue() {
		if (format == null || format.isBlank()) {
			return Format.AUTO;
		}
		try {
			return Format.valueOf(format.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Web feed " + id + ": format must be auto, ics, json-ld or ai, not " + format);
		}
	}

	ZoneId zone() {
		try {
			return timeZone == null || timeZone.isBlank() ? ZoneId.of("Europe/Berlin") : ZoneId.of(timeZone.trim());
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("Web feed " + id + ": unknown time zone " + timeZone);
		}
	}
}
