package com.planmate.discovery.source.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.JsonNode;

class GoogleCalendarClientTest {

	private static DiscoveryProperties properties() {
		DiscoveryProperties.GoogleCalendar config = new DiscoveryProperties.GoogleCalendar(true, "0 40 5,17 * * *",
				"https://oauth2.googleapis.com/token", "https://www.googleapis.com/calendar/v3", "cid", "secret",
				"rt", List.of(), Duration.ofDays(90), 300, Duration.ZERO);
		return new DiscoveryProperties(Duration.ofHours(24), 3,
				new DiscoveryProperties.Sources(null, null, null, null, config), null, null, null);
	}

	@Test
	void refreshesTheTokenThenReadsEveryPageWithTheBearerToken() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		server.expect(once(), requestTo("https://oauth2.googleapis.com/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().string(Matchers.containsString("grant_type=refresh_token")))
				.andExpect(content().string(Matchers.containsString("refresh_token=rt")))
				.andRespond(withSuccess("{\"access_token\":\"AT\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.startsWith(
				"https://www.googleapis.com/calendar/v3/calendars/cal@import.calendar.google.com/events")))
				.andExpect(header("Authorization", "Bearer AT"))
				.andRespond(withSuccess("{\"items\":[{\"id\":\"a\"}],\"nextPageToken\":\"p2\"}", MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.containsString("pageToken=p2")))
				.andExpect(header("Authorization", "Bearer AT"))
				.andRespond(withSuccess("{\"items\":[{\"id\":\"b\"}]}", MediaType.APPLICATION_JSON));

		GoogleCalendarClient client = new GoogleCalendarClient(properties(), builder);
		List<JsonNode> items = client.listEvents("cal@import.calendar.google.com",
				Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2027-02-08T00:00:00Z"), 300, 0);

		assertThat(items).extracting(n -> n.path("id").asString()).containsExactly("a", "b");
		server.verify();
	}
}
