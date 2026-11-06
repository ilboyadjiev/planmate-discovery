package com.planmate.discovery.source.web;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events from the schema.org data web pages embed for search engines (script type="application/ld+json"): Event
 * and its kinds (MusicEvent, TheaterEvent, ComedyEvent, Festival, ...), also inside @graph, lists (ItemList) and a
 * venue's own data. Most venue and ticket sites have it, so no AI is needed.
 */
final class JsonLdParser {

	/** Lenient: pages often have raw line breaks in texts and trailing commas. */
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_TRAILING_COMMA,
					JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_SINGLE_QUOTES)
			.build();
	private static final int MAX_DEPTH = 10;

	private JsonLdParser() {
	}

	static List<SourceEvent> parse(Document page, WebFeed feed) {
		List<SourceEvent> events = new ArrayList<>();
		for (Element script : page.select("script[type~=(?i)application/ld\\+json]")) {
			JsonNode data;
			try {
				data = JSON.readTree(script.data().trim());
			} catch (JacksonException e) {
				continue; // a broken block on an otherwise fine page
			}
			collect(data, page.location(), feed, events, 0);
		}
		return events;
	}

	private static void collect(JsonNode node, String pageUrl, WebFeed feed, List<SourceEvent> events, int depth) {
		if (node == null || depth > MAX_DEPTH) {
			return;
		}
		if (node.isArray()) {
			node.forEach(n -> collect(n, pageUrl, feed, events, depth + 1));
			return;
		}
		if (!node.isObject()) {
			return;
		}
		if (isEvent(node)) {
			SourceEvent e = event(node, pageUrl, feed);
			if (e != null) {
				events.add(e);
			}
			collect(node.path("subEvent"), pageUrl, feed, events, depth + 1);
			return;
		}
		for (var field : node.properties()) {
			if ((field.getValue().isObject() || field.getValue().isArray())) {
				collect(field.getValue(), pageUrl, feed, events, depth + 1);
			}
		}
	}

	/** "Event", "MusicEvent", "Festival", "schema:Event", "https://schema.org/TheaterEvent", or a list with one. */
	static boolean isEvent(JsonNode node) {
		JsonNode type = node.path("@type");
		List<String> types = new ArrayList<>();
		if (type.isArray()) {
			type.forEach(t -> types.add(t.asString("")));
		} else {
			types.add(type.asString(""));
		}
		return types.stream().map(t -> t.replaceFirst("^.*[/:#]", "")).anyMatch(t -> t.endsWith("Event") || t.equals("Festival"));
	}

	private static SourceEvent event(JsonNode e, String pageUrl, WebFeed feed) {
		ZoneId zone = feed.zone();
		String title = WebEvents.line(text(e.path("name")), WebEvents.MAX_TITLE);
		Instant start = WebEvents.instant(text(e.path("startDate")), zone);
		if (title == null || start == null) {
			return null;
		}
		if (text(e.path("eventAttendanceMode")) != null
				&& text(e.path("eventAttendanceMode")).contains("OnlineEventAttendanceMode")) {
			return null; // online only: not "around me"
		}
		Instant end = WebEvents.instant(text(e.path("endDate")), zone);
		if (WebEvents.dateOnly(text(e.path("startDate")))) {
			// A date without a time: only runs over several days (exhibitions), through the end of the last day
			if (end == null || !WebEvents.dateOnly(text(e.path("endDate"))) || !end.isAfter(start)) {
				return null;
			}
			end = end.atZone(zone).plusDays(1).toInstant();
		}
		String url = WebEvents.link(text(e.path("url")), pageUrl);
		String id = text(e.path("@id"));
		String key = id != null && !id.isBlank() ? WebEvents.key(id, start.toString())
				: WebEvents.key(url == null ? title : url, start.toString());
		String status = text(e.path("eventStatus"));
		boolean cancelled = status != null && status.toLowerCase(Locale.ROOT).contains("cancelled");
		String description = WebEvents.cut(WebEvents.text(text(e.path("description"))), WebEvents.MAX_DESCRIPTION);
		return new SourceEvent(key, title, description, start, end != null && end.isAfter(start) ? end : null,
				place(e.path("location"), feed.location()), feed.tags(), url == null ? pageUrl : url, cancelled);
	}

	/** Place { name, address: "..." or PostalAddress }, or just a text; the first of a list. */
	private static Location place(JsonNode location, Location feedPlace) {
		JsonNode l = location.isArray() ? location.path(0) : location;
		if (l.isString()) {
			return WebEvents.place(l.asString(), feedPlace);
		}
		if (!l.isObject() || l.path("@type").asString("").contains("VirtualLocation")) {
			return WebEvents.place(null, feedPlace);
		}
		String name = WebEvents.line(text(l.path("name")), 120);
		JsonNode a = l.path("address");
		if (a.isString()) {
			Location fromText = WebEvents.place(a.asString(), null);
			// "address": "Zellstraße 4, 81667 München": its first part is the street, not a venue
			String street = fromText == null ? null : fromText.street() != null ? fromText.street() : fromText.name();
			return WebEvents.place(name, street, fromText == null ? null : fromText.zipcode(),
					fromText == null ? null : fromText.city(), null, feedPlace);
		}
		JsonNode country = a.path("addressCountry");
		return WebEvents.place(name, text(a.path("streetAddress")), text(a.path("postalCode")),
				text(a.path("addressLocality")), country.isObject() ? text(country.path("name")) : text(country), feedPlace);
	}

	/** A text value; a list gives its first entry. */
	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isArray()) {
			return node.isEmpty() ? null : text(node.path(0));
		}
		if (node.isObject()) {
			return text(node.path("@value").isMissingNode() ? node.path("@id") : node.path("@value"));
		}
		String t = node.asString().trim();
		return t.isEmpty() ? null : t;
	}
}
