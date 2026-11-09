package com.planmate.discovery.source.google;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.JsonNode;

/**
 * Logs in to Google as the PlanMate ingestion account (an OAuth2 refresh token, exchanged for a short-lived access
 * token) and reads a subscribed calendar with the Google Calendar API. Only the account's own calendars are read,
 * never the original feed, so the service never fetches the source site itself.
 *
 * Credentials come from the environment (discovery.sources.google-calendar.*): the OAuth client id and secret, and
 * a refresh token for the account, authorized once for the calendar.readonly scope.
 */
@Component
public class GoogleCalendarClient {

	private static final Logger log = LoggerFactory.getLogger(GoogleCalendarClient.class);

	/** Google caps a page at 2500; we ask for less and stop at maxEventsPerCalendar. */
	private static final int PAGE_SIZE = 250;

	private final DiscoveryProperties.GoogleCalendar config;
	private final RestClient http;

	private String accessToken;
	private Instant accessTokenExpiry = Instant.EPOCH;

	public GoogleCalendarClient(DiscoveryProperties properties, RestClient.Builder builder) {
		this.config = properties.sources().googleCalendar();
		this.http = builder.clone().build();
	}

	/** True once the OAuth client id, secret and refresh token are all set. */
	public boolean configured() {
		return notBlank(config.clientId()) && notBlank(config.clientSecret()) && notBlank(config.refreshToken());
	}

	/** A valid access token for the account, refreshed shortly before it expires. */
	synchronized String accessToken() {
		if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
			return accessToken;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", config.clientId().trim());
		form.add("client_secret", config.clientSecret().trim());
		form.add("refresh_token", config.refreshToken().trim());
		form.add("grant_type", "refresh_token");
		JsonNode res;
		try {
			res = http.post().uri(config.tokenUrl()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form).retrieve().body(JsonNode.class);
		} catch (RestClientResponseException e) {
			String body = e.getResponseBodyAsString();
			if (body != null && body.contains("invalid_grant")) {
				// A lapsed token is an operator action, not a transient error: make it unmistakable in the logs.
				log.error("Google rejected the refresh token (invalid_grant): it has expired or been revoked. "
						+ "Re-authorize the PlanMate ingestion account and set GOOGLE_CALENDAR_REFRESH_TOKEN again. "
						+ "In Testing mode Google expires the refresh token after 7 days (see docs/DISCOVERY_SETUP.md). "
						+ "Google calendar ingestion is paused until then.");
			} else {
				log.error("Google refused the refresh-token request ({}): {}", e.getStatusCode(), body);
			}
			throw new GoogleAuthException("Google refresh-token exchange failed", e);
		}
		String token = res == null ? null : text(res, "access_token");
		if (token == null) {
			log.error("Google's token response had no access_token — check the OAuth client id and secret");
			throw new IllegalStateException("Google did not return an access token (check the OAuth credentials)");
		}
		long expiresIn = res.path("expires_in").asLong(3600L);
		accessToken = token;
		accessTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
		return token;
	}

	/**
	 * The events of one calendar between timeMin and timeMax, as single occurrences in start order (recurring events
	 * are expanded), at most "max" of them.
	 */
	public List<JsonNode> listEvents(String calendarId, Instant timeMin, Instant timeMax, int max, long pauseMillis)
			throws InterruptedException {
		List<JsonNode> items = new ArrayList<>();
		String pageToken = null;
		do {
			URI uri = UriComponentsBuilder.fromUriString(config.apiBaseUrl())
					.pathSegment("calendars", calendarId, "events")
					.queryParam("singleEvents", "true")
					.queryParam("orderBy", "startTime")
					.queryParam("showDeleted", "false")
					.queryParam("timeMin", timeMin.toString())
					.queryParam("timeMax", timeMax.toString())
					.queryParam("maxResults", Math.min(PAGE_SIZE, Math.max(1, max)))
					.queryParamIfPresent("pageToken", Optional.ofNullable(pageToken))
					.encode().build().toUri();
			JsonNode body = http.get().uri(uri).header("Authorization", "Bearer " + accessToken())
					.retrieve().body(JsonNode.class);
			if (body == null) {
				break;
			}
			for (JsonNode item : body.path("items")) {
				if (items.size() < max) {
					items.add(item);
				}
			}
			pageToken = text(body, "nextPageToken");
			if (pageToken != null && items.size() < max && pauseMillis > 0) {
				Thread.sleep(pauseMillis);
			}
		} while (pageToken != null && items.size() < max);
		return items;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString().trim();
		return text.isEmpty() ? null : text;
	}
}
