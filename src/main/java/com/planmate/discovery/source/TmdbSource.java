package com.planmate.discovery.source;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.databind.JsonNode;

/**
 * Cinema releases from The Movie Database (https://developer.themoviedb.org), per region: the most popular films
 * opening in cinemas there within lookAhead. Each becomes an all-day event on its release day in that region (films
 * open on different days in Germany and Bulgaria), placed in the region's city, so "Around me" in Munich shows the
 * German releases and in Sofia the Bulgarian ones. Titles and texts come in the region's language.
 *
 * Needs a free TMDB API key (discovery.sources.tmdb.api-key): either the "API Key" or the longer "API Read Access
 * Token". TMDB asks apps to credit it: every event says "Source: TMDB" and links to the film's TMDB page.
 *
 * Per region: one or two pages of /discover/movie, then /movie/{id} with its release dates for each film, about 30
 * requests a day per region.
 */
@Component
public class TmdbSource implements EventSource {

	private static final Logger log = LoggerFactory.getLogger(TmdbSource.class);
	static final String MOVIE_PAGE = "https://www.themoviedb.org/movie/";
	static final int PAGE_SIZE = 20;
	static final int MAX_DESCRIPTION = 2000;
	/** TMDB release types: 3 theatrical, 2 limited theatrical. */
	private static final int THEATRICAL = 3;
	private static final int LIMITED = 2;

	private final DiscoveryProperties.Tmdb config;
	private final RestClient http;
	private final Clock clock;

	public TmdbSource(DiscoveryProperties properties, RestClient.Builder builder, Clock clock) {
		this.config = properties.sources().tmdb();
		RestClient.Builder b = builder.clone().baseUrl(config.baseUrl());
		if (bearer(config.apiKey())) {
			b.defaultHeader("Authorization", "Bearer " + config.apiKey().trim());
		}
		this.http = b.build();
		this.clock = clock;
	}

	@Override
	public String id() {
		return "tmdb";
	}

	@Override
	public String displayName() {
		return "TMDB";
	}

	@Override
	public String cron() {
		return config.cron();
	}

	@Override
	public boolean enabled() {
		if (config.enabled() && (config.apiKey() == null || config.apiKey().isBlank())) {
			log.warn("TMDB is enabled but has no API key (TMDB_API_KEY); it stays off");
			return false;
		}
		return config.enabled() && config.regions() != null && !config.regions().isEmpty();
	}

	@Override
	public List<SourceEvent> fetch() throws InterruptedException {
		List<SourceEvent> events = new ArrayList<>();
		for (DiscoveryProperties.Region region : config.regions()) {
			events.addAll(fetch(region));
		}
		return events;
	}

	private List<SourceEvent> fetch(DiscoveryProperties.Region region) throws InterruptedException {
		ZoneId zone = zone(region);
		LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
		LocalDate last = LocalDate.ofInstant(clock.instant().plus(config.lookAhead()), zone);
		String code = region.code().trim().toUpperCase(Locale.ROOT);

		// The most popular films opening in this region's cinemas in the window
		Set<Long> ids = new LinkedHashSet<>();
		int pages = Math.max(1, (config.maxMoviesPerRegion() + PAGE_SIZE - 1) / PAGE_SIZE);
		for (int page = 1; page <= pages && ids.size() < config.maxMoviesPerRegion(); page++) {
			final int number = page;
			JsonNode body = get(uri -> uri.path("/discover/movie")
					.queryParam("region", code)
					.queryParam("language", region.language())
					.queryParam("with_release_type", "2|3")
					.queryParam("release_date.gte", today.toString())
					.queryParam("release_date.lte", last.toString())
					.queryParam("sort_by", "popularity.desc")
					.queryParam("include_adult", "false")
					.queryParam("page", number), new Object[0]);
			for (JsonNode movie : body.path("results")) {
				if (ids.size() < config.maxMoviesPerRegion() && movie.path("id").canConvertToLong()) {
					ids.add(movie.path("id").asLong());
				}
			}
			if (number >= body.path("total_pages").asInt(0)) {
				break;
			}
			pause();
		}

		List<SourceEvent> events = new ArrayList<>();
		for (long id : ids) {
			pause();
			JsonNode movie;
			try {
				movie = get(uri -> uri.path("/movie/{id}")
						.queryParam("language", region.language())
						.queryParam("append_to_response", "release_dates"), id);
			} catch (HttpClientErrorException.NotFound e) {
				continue; // removed from TMDB since the search
			}
			SourceEvent event = map(movie, region, today, last);
			if (event != null) {
				events.add(event);
			}
		}
		return events;
	}

	/** One film as a release-day event in the region, or null when it does not open there within the window. */
	static SourceEvent map(JsonNode movie, DiscoveryProperties.Region region, LocalDate from, LocalDate to) {
		String title = text(movie, "title");
		if (title == null || movie.path("adult").asBoolean(false)) {
			return null;
		}
		LocalDate release = release(movie, region.code(), from, to);
		if (release == null) {
			return null;
		}
		ZoneId zone = zone(region);
		Instant start = release.atStartOfDay(zone).toInstant();
		Instant end = release.plusDays(1).atStartOfDay(zone).toInstant();

		List<String> genres = new ArrayList<>();
		movie.path("genres").forEach(g -> {
			if (text(g, "name") != null) {
				genres.add(text(g, "name"));
			}
		});
		StringBuilder description = new StringBuilder();
		if (text(movie, "overview") != null) {
			description.append(text(movie, "overview"));
		}
		String original = text(movie, "original_title");
		if (original != null && !original.equalsIgnoreCase(title)) {
			description.append(description.isEmpty() ? "" : "\n\n").append("Original title: ").append(original).append('.');
		}
		if (!genres.isEmpty()) {
			description.append(description.isEmpty() ? "" : "\n\n").append("Genre: ").append(String.join(", ", genres)).append('.');
		}
		if (movie.path("runtime").asInt(0) > 0) {
			description.append(description.isEmpty() ? "" : " ").append("Runtime: ").append(movie.path("runtime").asInt())
					.append(" min.");
		}
		String text = description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION) : description.toString();
		return new SourceEvent(region.code().toLowerCase(Locale.ROOT) + "-" + movie.path("id").asLong(), title,
				text.isEmpty() ? null : text, start, end,
				new Location("Cinemas in " + region.city(), null, null, region.city(), region.country()),
				List.of("#film", "#release"), MOVIE_PAGE + movie.path("id").asLong(), false);
	}

	/** Its first cinema release in the region within [from, to]: theatrical, else limited theatrical. */
	static LocalDate release(JsonNode movie, String code, LocalDate from, LocalDate to) {
		LocalDate best = null;
		int bestType = 0;
		for (JsonNode country : movie.path("release_dates").path("results")) {
			if (!code.equalsIgnoreCase(text(country, "iso_3166_1"))) {
				continue;
			}
			for (JsonNode r : country.path("release_dates")) {
				int type = r.path("type").asInt(0);
				String date = text(r, "release_date");
				if ((type != THEATRICAL && type != LIMITED) || date == null || date.length() < 10) {
					continue;
				}
				LocalDate day;
				try {
					day = LocalDate.parse(date.substring(0, 10)); // the local date in that country
				} catch (DateTimeParseException e) {
					continue;
				}
				if (day.isBefore(from) || day.isAfter(to)) {
					continue;
				}
				boolean better = best == null || (type == THEATRICAL && bestType != THEATRICAL)
						|| (type == bestType && day.isBefore(best));
				if (better) {
					best = day;
					bestType = type;
				}
			}
		}
		return best;
	}

	private JsonNode get(Function<UriBuilder, UriBuilder> uri, Object... variables) {
		JsonNode body = http.get().uri(b -> {
			UriBuilder built = uri.apply(b);
			if (!bearer(config.apiKey())) {
				built.queryParam("api_key", config.apiKey());
			}
			return built.build(variables);
		}).retrieve().body(JsonNode.class);
		if (body == null) {
			throw new IllegalStateException("TMDB returned an empty response");
		}
		return body;
	}

	private void pause() throws InterruptedException {
		if (!config.pause().isZero()) {
			Thread.sleep(config.pause().toMillis());
		}
	}

	/** The "API Read Access Token" (a JWT, sent as a header); otherwise the short "API Key" (a query parameter). */
	private static boolean bearer(String key) {
		return key != null && key.trim().startsWith("eyJ");
	}

	private static ZoneId zone(DiscoveryProperties.Region region) {
		try {
			return region.timeZone() == null ? ZoneId.of("Europe/Berlin") : ZoneId.of(region.timeZone());
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("TMDB region " + region.code() + ": unknown time zone " + region.timeZone());
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString().trim();
		return text.isEmpty() ? null : text;
	}
}
