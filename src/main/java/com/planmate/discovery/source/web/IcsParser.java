package com.planmate.discovery.source.web;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.planmate.discovery.model.SourceEvent;

/**
 * Events from an iCalendar file (.ics, RFC 5545), as venues and city calendars publish them.
 *
 * Reads UID, SUMMARY, DESCRIPTION, LOCATION, URL, DTSTART, DTEND or DURATION, STATUS and RECURRENCE-ID. Times may
 * be UTC, in a TZID zone, or floating (taken in the feed's zone). All-day entries are kept only when they run over
 * several days (exhibitions, markets), from midnight of the first day to midnight after the last. A repeating
 * event (RRULE) is taken only at its first date: venue calendars list each date of a show on its own.
 */
final class IcsParser {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

	/** One content line: NAME;PARAM=VALUE:value */
	record Property(String name, Map<String, String> params, String value) {
	}

	private IcsParser() {
	}

	static boolean looksLikeIcs(String text) {
		return text != null && text.stripLeading().replace("﻿", "").regionMatches(true, 0, "BEGIN:VCALENDAR", 0, 15);
	}

	static List<SourceEvent> parse(String ics, WebFeed feed) {
		if (!looksLikeIcs(ics)) {
			throw new IllegalArgumentException("Not an iCalendar file");
		}
		ZoneId zone = feed.zone();
		List<SourceEvent> events = new ArrayList<>();
		Map<String, Property> current = null;
		int depth = 0; // inside VALARM and other parts of an event
		for (String line : unfold(ics)) {
			Property p = property(line);
			if (p == null) {
				continue;
			}
			if (p.name().equals("BEGIN")) {
				if (p.value().equalsIgnoreCase("VEVENT")) {
					current = new HashMap<>();
					depth = 0;
				} else if (current != null) {
					depth++;
				}
			} else if (p.name().equals("END")) {
				if (p.value().equalsIgnoreCase("VEVENT") && current != null) {
					SourceEvent e = event(current, feed, zone);
					if (e != null) {
						events.add(e);
					}
					current = null;
				} else if (current != null) {
					depth--;
				}
			} else if (current != null && depth == 0) {
				current.putIfAbsent(p.name(), p);
			}
		}
		return events;
	}

	private static SourceEvent event(Map<String, Property> p, WebFeed feed, ZoneId zone) {
		String title = WebEvents.line(text(p.get("SUMMARY")), WebEvents.MAX_TITLE);
		Instant start = time(p.get("DTSTART"), zone);
		if (title == null || start == null) {
			return null;
		}
		Instant end = time(p.get("DTEND"), zone);
		if (end == null && p.get("DURATION") != null) {
			try {
				end = start.plus(Duration.parse(p.get("DURATION").value().trim()));
			} catch (DateTimeParseException e) {
				// no end then
			}
		}
		if (allDay(p.get("DTSTART")) && (end == null || !end.isAfter(start.plus(Duration.ofDays(1))))) {
			return null; // a single day without a time: no use in a calendar of things to go to
		}
		String uid = value(p.get("UID"));
		String recurrence = value(p.get("RECURRENCE-ID"));
		String id = uid == null ? WebEvents.key(title, start.toString())
				: WebEvents.key(uid, recurrence == null ? "" : recurrence);
		String description = WebEvents.cut(WebEvents.text(text(p.get("DESCRIPTION"))), WebEvents.MAX_DESCRIPTION);
		boolean cancelled = "CANCELLED".equalsIgnoreCase(value(p.get("STATUS")));
		return new SourceEvent(id, title, description, start, end != null && end.isAfter(start) ? end : null,
				WebEvents.place(text(p.get("LOCATION")), feed.location()), feed.tags(),
				WebEvents.link(value(p.get("URL")), null), cancelled);
	}

	/** DTSTART/DTEND: 20261114T190000Z, 20261114T190000 (TZID or floating), or 20261114 (all day). */
	static Instant time(Property p, ZoneId feedZone) {
		String v = value(p);
		if (v == null) {
			return null;
		}
		try {
			if (v.length() == 8) {
				return LocalDate.parse(v, DATE).atStartOfDay(feedZone).toInstant();
			}
			if (v.endsWith("Z") || v.endsWith("z")) {
				return LocalDateTime.parse(v.substring(0, v.length() - 1), DATE_TIME).toInstant(ZoneOffset.UTC);
			}
			return LocalDateTime.parse(v, DATE_TIME).atZone(zone(p.params().get("TZID"), feedZone)).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static boolean allDay(Property p) {
		return p != null && ("DATE".equalsIgnoreCase(p.params().get("VALUE")) || value(p).length() == 8);
	}

	/** A TZID like "Europe/Berlin" (or "/Europe/Berlin"); anything else (Windows names) means the feed's zone. */
	private static ZoneId zone(String tzid, ZoneId fallback) {
		if (tzid == null || tzid.isBlank()) {
			return fallback;
		}
		try {
			return ZoneId.of(tzid.replaceFirst("^/", "").trim());
		} catch (DateTimeException e) {
			return fallback;
		}
	}

	/** Lines with folded continuations (a line starting with a space or tab) joined again. */
	static List<String> unfold(String ics) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = null;
		for (String raw : ics.replace("﻿", "").split("\\r?\\n|\\r")) {
			if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && line != null) {
				line.append(raw, 1, raw.length());
			} else {
				if (line != null) {
					lines.add(line.toString());
				}
				line = new StringBuilder(raw);
			}
		}
		if (line != null) {
			lines.add(line.toString());
		}
		return lines;
	}

	static Property property(String line) {
		int colon = -1;
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (c == ':' && !quoted) {
				colon = i;
				break;
			}
		}
		if (colon <= 0) {
			return null;
		}
		String[] head = line.substring(0, colon).split(";");
		Map<String, String> params = new HashMap<>();
		for (int i = 1; i < head.length; i++) {
			int eq = head[i].indexOf('=');
			if (eq > 0) {
				params.put(head[i].substring(0, eq).trim().toUpperCase(Locale.ROOT),
						head[i].substring(eq + 1).replace("\"", "").trim());
			}
		}
		return new Property(head[0].trim().toUpperCase(Locale.ROOT), params, line.substring(colon + 1));
	}

	private static String value(Property p) {
		if (p == null) {
			return null;
		}
		String v = p.value().trim();
		return v.isEmpty() ? null : v;
	}

	/** A TEXT value with its escapes undone: \n, \, \; \\ */
	private static String text(Property p) {
		String v = value(p);
		if (v == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(v.length());
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			if (c == '\\' && i + 1 < v.length()) {
				char n = v.charAt(++i);
				out.append(n == 'n' || n == 'N' ? '\n' : n);
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
