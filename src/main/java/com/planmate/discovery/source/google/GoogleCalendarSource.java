package com.planmate.discovery.source.google;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.source.EventSource;

import tools.jackson.databind.JsonNode;

/**
 * One subscribed Google calendar as a PlanMate event source, credited by the calendar's name. Reads upcoming events
 * through {@link GoogleCalendarClient} and maps them to PlanMate's shape. All-day entries are kept only when they
 * run over several days (like the web feeds); cancelled or unreadable entries are left out, and the pipeline
 * cancels an event once it stops appearing.
 */
public class GoogleCalendarSource implements EventSource {

	private final DiscoveryProperties.Calendar calendar;
	private final DiscoveryProperties.GoogleCalendar config;
	private final GoogleCalendarClient client;
	private final java.time.Clock clock;

	public GoogleCalendarSource(DiscoveryProperties.Calendar calendar, DiscoveryProperties.GoogleCalendar config,
			GoogleCalendarClient client, java.time.Clock clock) {
		this.calendar = calendar;
		this.config = config;
		this.client = client;
		this.clock = clock;
	}

	@Override
	public String id() {
		return "gcal-" + calendar.key();
	}

	@Override
	public String displayName() {
		return calendar.name();
	}

	@Override
	public String cron() {
		return config.cron();
	}

	@Override
	public boolean enabled() {
		return config.enabled() && client.configured();
	}

	@Override
	public List<SourceEvent> fetch() throws InterruptedException {
		ZoneId zone = zone();
		Instant now = clock.instant();
		Instant timeMin = now.minus(Duration.ofDays(1));
		Instant timeMax = now.plus(config.lookAhead());
		List<JsonNode> items = client.listEvents(calendar.calendarId(), timeMin, timeMax,
				config.maxEventsPerCalendar(), config.pause().toMillis());
		List<SourceEvent> events = new ArrayList<>();
		for (JsonNode item : items) {
			SourceEvent event = map(item, zone);
			if (event != null) {
				events.add(event);
			}
		}
		return events;
	}

	/** One Google event as a SourceEvent, or null when it is cancelled, has no title/start, or is a single all-day day. */
	SourceEvent map(JsonNode item, ZoneId zone) {
		String id = GoogleCalendarClient.text(item, "id");
		String title = GoogleCalendarClient.text(item, "summary");
		if (id == null || title == null || "cancelled".equals(GoogleCalendarClient.text(item, "status"))) {
			return null;
		}
		JsonNode start = item.path("start");
		JsonNode end = item.path("end");
		Instant startTime;
		Instant endTime;
		String startDateTime = GoogleCalendarClient.text(start, "dateTime");
		if (startDateTime != null) { // a timed event: the value carries its own offset
			startTime = instant(startDateTime);
			endTime = instant(GoogleCalendarClient.text(end, "dateTime"));
		} else { // an all-day event: start.date, end.date (exclusive)
			LocalDate from = date(GoogleCalendarClient.text(start, "date"));
			if (from == null) {
				return null;
			}
			LocalDate to = date(GoogleCalendarClient.text(end, "date"));
			if (to == null) {
				to = from.plusDays(1);
			}
			if (!to.isAfter(from.plusDays(1))) {
				return null; // a single day without a time: no use in a calendar of things to go to
			}
			startTime = from.atStartOfDay(zone).toInstant();
			endTime = to.atStartOfDay(zone).toInstant();
		}
		if (startTime == null) {
			return null;
		}
		return new SourceEvent(id, title, GoogleCalendarClient.text(item, "description"), startTime,
				endTime != null && endTime.isAfter(startTime) ? endTime : null, place(GoogleCalendarClient.text(item, "location")),
				calendar.tags(), GoogleCalendarClient.text(item, "htmlLink"), false);
	}

	/** The place: the venue (the first part of the free-text location) over the calendar's city and country. */
	private Location place(String location) {
		String venue = location == null ? null : location.split(",", 2)[0].trim();
		return new Location(venue == null || venue.isEmpty() ? null : venue, null, null, calendar.city(), calendar.country());
	}

	private ZoneId zone() {
		try {
			return calendar.timeZone() == null || calendar.timeZone().isBlank() ? ZoneId.of("Europe/Berlin")
					: ZoneId.of(calendar.timeZone());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Google calendar " + calendar.key() + ": unknown time zone " + calendar.timeZone());
		}
	}

	private static Instant instant(String rfc3339) {
		if (rfc3339 == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(rfc3339).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static LocalDate date(String date) {
		if (date == null) {
			return null;
		}
		try {
			return LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
