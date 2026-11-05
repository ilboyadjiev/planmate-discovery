package com.planmate.discovery.source;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.databind.JsonNode;

/**
 * Ticketmaster Discovery API (https://developer.ticketmaster.com): concerts, sports, theatre and more in the
 * configured cities, from now until lookAhead. Needs a free API key (discovery.sources.ticketmaster.api-key).
 *
 * The API allows 5 requests a second and paging up to 1000 results per search, so each city is read page by page
 * with a short pause, at most maxPages pages.
 */
@Component
public class TicketmasterSource implements EventSource {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterSource.class);
	private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));
	static final int MAX_DESCRIPTION = 2000;
	/** Ticketmaster only pages through the first 1000 results of a search. */
	static final int MAX_RESULTS = 1000;

	/** Ticketmaster's segments and genres, as PlanMate tags. */
	private static final Map<String, String> TAGS = Map.ofEntries(
			Map.entry("music", "#concert"), Map.entry("sports", "#sports"), Map.entry("arts & theatre", "#theatre"),
			Map.entry("film", "#film"), Map.entry("comedy", "#comedy"), Map.entry("festival", "#festival"),
			Map.entry("dance/electronic", "#party"), Map.entry("hip-hop/rap", "#concert"),
			Map.entry("classical", "#culture"), Map.entry("opera", "#culture"), Map.entry("theatre", "#theatre"),
			Map.entry("musical", "#theatre"), Map.entry("fairs & festivals", "#festival"),
			Map.entry("family", "#family"), Map.entry("children's theatre", "#family"));

	private final DiscoveryProperties.Ticketmaster config;
	private final RestClient http;
	private final Clock clock;

	public TicketmasterSource(DiscoveryProperties properties, RestClient.Builder builder, Clock clock) {
		this.config = properties.sources().ticketmaster();
		this.http = builder.baseUrl(config.baseUrl()).build();
		this.clock = clock;
	}

	@Override
	public String id() {
		return "tm";
	}

	@Override
	public String displayName() {
		return "Ticketmaster";
	}

	@Override
	public String cron() {
		return config.cron();
	}

	@Override
	public boolean enabled() {
		if (config.enabled() && (config.apiKey() == null || config.apiKey().isBlank())) {
			log.warn("Ticketmaster is enabled but has no API key (TICKETMASTER_API_KEY); it stays off");
			return false;
		}
		return config.enabled();
	}

	@Override
	public List<SourceEvent> fetch() throws InterruptedException {
		Instant from = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant to = from.plus(config.lookAhead());
		Map<String, SourceEvent> found = new LinkedHashMap<>();
		for (String city : config.cities()) {
			int pages = Math.min(config.maxPages(), MAX_RESULTS / Math.max(1, config.pageSize()));
			for (int page = 0; page < pages; page++) {
				final int number = page;
				JsonNode body = http.get()
						.uri(uri -> uri.path("/events.json")
								.queryParam("apikey", config.apiKey())
								.queryParam("city", city)
								.queryParam("countryCode", config.countryCode())
								.queryParam("startDateTime", API_TIME.format(from))
								.queryParam("endDateTime", API_TIME.format(to))
								.queryParam("size", config.pageSize())
								.queryParam("page", number)
								.queryParam("sort", "date,asc")
								.queryParam("locale", "*")
								.build())
						.retrieve()
						.body(JsonNode.class);
				if (body == null) {
					throw new IllegalStateException("Ticketmaster returned an empty response");
				}
				for (JsonNode event : body.path("_embedded").path("events")) {
					SourceEvent mapped = map(event);
					if (mapped != null) {
						found.putIfAbsent(mapped.sourceId(), mapped);
					}
				}
				int totalPages = body.path("page").path("totalPages").asInt(0);
				if (number + 1 >= totalPages) {
					break;
				}
				Thread.sleep(config.pause().toMillis());
			}
		}
		return new ArrayList<>(found.values());
	}

	/** One Ticketmaster event in PlanMate's terms, or null when it has no usable id, name or date. */
	static SourceEvent map(JsonNode e) {
		String id = text(e, "id");
		String name = text(e, "name");
		JsonNode dates = e.path("dates");
		Instant start = when(dates.path("start"), text(dates, "timezone"));
		if (id == null || name == null || start == null) {
			return null;
		}
		Instant end = when(dates.path("end"), text(dates, "timezone"));
		String description = Stream.of(text(e, "description"), text(e, "info"), text(e, "pleaseNote"))
				.filter(t -> t != null).distinct().collect(Collectors.joining("\n\n"));
		if (description.length() > MAX_DESCRIPTION) {
			description = description.substring(0, MAX_DESCRIPTION);
		}
		boolean cancelled = "cancelled".equalsIgnoreCase(text(dates.path("status"), "code"));
		return new SourceEvent(id, name, description.isEmpty() ? null : description, start,
				end != null && end.isAfter(start) ? end : null, venue(e.path("_embedded").path("venues").path(0)),
				tags(e), text(e, "url"), cancelled);
	}

	/** dateTime (UTC) when Ticketmaster gives one, else the local date and time in the event's time zone. */
	private static Instant when(JsonNode point, String timezone) {
		String dateTime = text(point, "dateTime");
		if (dateTime != null) {
			return Instant.parse(dateTime);
		}
		String localDate = text(point, "localDate");
		String localTime = text(point, "localTime");
		if (localDate == null || localTime == null || point.path("timeTBA").asBoolean(false)) {
			return null; // no time to put in a calendar
		}
		ZoneId zone = timezone == null ? ZoneId.of("Europe/Berlin") : ZoneId.of(timezone);
		return LocalDate.parse(localDate).atTime(LocalTime.parse(localTime)).atZone(zone).toInstant();
	}

	private static Location venue(JsonNode v) {
		if (v.isMissingNode() || v.isNull()) {
			return null;
		}
		return new Location(text(v, "name"), text(v.path("address"), "line1"), text(v, "postalCode"),
				text(v.path("city"), "name"), text(v.path("country"), "name"));
	}

	private static List<String> tags(JsonNode e) {
		Set<String> tags = new LinkedHashSet<>();
		for (JsonNode c : e.path("classifications")) {
			for (String part : new String[] { "segment", "genre", "subGenre" }) {
				String name = text(c.path(part), "name");
				String tag = name == null ? null : TAGS.get(name.toLowerCase(Locale.ROOT));
				if (tag != null) {
					tags.add(tag);
				}
			}
			if (c.path("family").asBoolean(false)) {
				tags.add("#family");
			}
		}
		return new ArrayList<>(tags);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString().trim();
		return text.isEmpty() || "Undefined".equalsIgnoreCase(text) ? null : text;
	}
}
