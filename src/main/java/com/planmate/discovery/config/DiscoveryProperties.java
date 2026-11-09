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
 *
 * maxPerSeries: of a show listed once per date (same title at the same venue), only the next this-many dates are
 * published; later dates follow as earlier ones pass. 0 publishes every date.
 */
@ConfigurationProperties("discovery")
public record DiscoveryProperties(
		@DefaultValue("24h") Duration staleAfter,
		@DefaultValue("3") int maxPerSeries,
		@DefaultValue Sources sources,
		@DefaultValue Ai ai,
		@DefaultValue Topics topics,
		@DefaultValue Outbox outbox) {

	public record Sources(@DefaultValue Ticketmaster ticketmaster, @DefaultValue Curated curated,
			@DefaultValue Feeds feeds, @DefaultValue Tmdb tmdb, @DefaultValue GoogleCalendar googleCalendar) {
	}

	/**
	 * Events from Google Calendars that a PlanMate ingestion account has subscribed to (e.g. a venue's published
	 * webcal/.ics feed added to Google Calendar). The discovery service reads them with the Google Calendar API,
	 * authorized by that account's OAuth refresh token, so it never fetches the original feed itself. One source
	 * per calendar, credited by the calendar's name. Set up: enable the Google Calendar API, create an OAuth client,
	 * authorize the account once for the calendar.readonly scope, and put the client id, secret and refresh token in
	 * the environment. Only subscribe to and republish calendars the operator is entitled to.
	 */
	public record GoogleCalendar(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("0 40 5,17 * * *") String cron,
			@DefaultValue("https://oauth2.googleapis.com/token") String tokenUrl,
			@DefaultValue("https://www.googleapis.com/calendar/v3") String apiBaseUrl,
			String clientId,
			String clientSecret,
			String refreshToken,
			List<Calendar> calendars,
			@DefaultValue("90d") Duration lookAhead,
			@DefaultValue("300") int maxEventsPerCalendar,
			@DefaultValue("200ms") Duration pause) {
	}

	/**
	 * key: short, stable id (its events are stored as "gcal-{key}-..."), never rename it. name: credited under each
	 * event. calendarId: the Google calendar's id (often an …@group.calendar.google.com address). city/country: a
	 * fallback place and the city tag. tags: added to every event.
	 */
	public record Calendar(String key, String name, String calendarId, String city, String country, String timeZone,
			List<String> tags) {
	}

	/**
	 * Cinema releases from The Movie Database (TMDB): the most popular films opening in each region within
	 * lookAhead, at most maxMoviesPerRegion per region. Each region's releases are shown in its city.
	 */
	public record Tmdb(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("0 15 4 * * *") String cron,
			String apiKey,
			@DefaultValue("https://api.themoviedb.org/3") String baseUrl,
			List<Region> regions,
			@DefaultValue("60d") Duration lookAhead,
			@DefaultValue("25") int maxMoviesPerRegion,
			@DefaultValue("100ms") Duration pause) {
	}

	/** code: ISO country (DE); language for titles and texts (de-DE); city the releases are shown in. */
	public record Region(String code, String language, String city, String country, String timeZone) {
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

	/**
	 * Calendars and event pages of venues and city listings (web-feeds.yml): iCal feeds, pages with schema.org event
	 * data, and, when a page has neither, its text read by the AI (aiFallback). Each feed is a source of its own, run
	 * one after the other. lookAhead: events further away are left out. maxEventsPerFeed and maxPageBytes keep a
	 * huge or broken page from flooding PlanMate.
	 */
	public record Feeds(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("0 45 5,17 * * *") String cron,
			@DefaultValue("classpath:feeds/web-feeds.yml") String file,
			@DefaultValue("true") boolean aiFallback,
			@DefaultValue("400") int aiPageReadsPerDay,
			@DefaultValue("120d") Duration lookAhead,
			@DefaultValue("300") int maxEventsPerFeed,
			@DefaultValue("3000000") int maxPageBytes,
			@DefaultValue("2s") Duration pause,
			@DefaultValue("PlanMateBot/1.0 (+https://www.planmate.org)") String userAgent) {
	}

	/**
	 * Gemini, on its own free-tier key, for short descriptions of new and changed events. retryDelays: how long to
	 * wait before asking again when Gemini is overloaded or rate-limited (429, 5xx, network errors).
	 */
	public record Ai(
			@DefaultValue("true") boolean enabled,
			String apiKey,
			@DefaultValue("gemini-3.5-flash-lite") String model,
			@DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
			@DefaultValue("15") int batchSize,
			@DefaultValue("300") int dailyLimit,
			@DefaultValue("5s") Duration pauseBetweenRequests,
			@DefaultValue({ "10s", "30s" }) List<Duration> retryDelays,
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
