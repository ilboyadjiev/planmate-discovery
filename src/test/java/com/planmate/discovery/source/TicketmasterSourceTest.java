package com.planmate.discovery.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.SourceEvent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;

class TicketmasterSourceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

	private static DiscoveryProperties properties() {
		DiscoveryProperties.Ticketmaster tm = new DiscoveryProperties.Ticketmaster(true, "0 0 * * * *", "KEY",
				"https://app.ticketmaster.com/discovery/v2", List.of("Munich"), "DE", Duration.ofDays(60), 100, 5,
				Duration.ZERO);
		return new DiscoveryProperties(Duration.ofHours(24), 3, new DiscoveryProperties.Sources(tm, null, null, null, null), null, null, null);
	}

	private static String fixture() throws Exception {
		return new ClassPathResource("ticketmaster/events-page.json").getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void readsTheCityPageByPageAndMapsEventsToPlanMateTerms() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("https://app.ticketmaster.com/discovery/v2/events.json")))
				.andExpect(queryParam("apikey", "KEY"))
				.andExpect(queryParam("city", "Munich"))
				.andExpect(queryParam("startDateTime", "2026-11-05T10:00:00Z"))
				.andExpect(queryParam("endDateTime", "2027-01-04T10:00:00Z"))
				.andExpect(queryParam("page", "0"))
				.andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

		List<SourceEvent> events = new TicketmasterSource(properties(), builder, CLOCK).fetch();
		server.verify();

		assertThat(events).extracting(SourceEvent::sourceId).containsExactly("G5vYZ9Kf1AbC", "G5vYZ9Kf2Cancel");
		SourceEvent jazz = events.get(0);
		assertThat(jazz.title()).isEqualTo("Jazz Night with the Munich Big Band");
		assertThat(jazz.startTime()).isEqualTo(Instant.parse("2026-11-20T18:00:00Z"));
		assertThat(jazz.endTime()).isNull();
		assertThat(jazz.endsAt()).isEqualTo(Instant.parse("2026-11-20T21:00:00Z"));
		assertThat(jazz.description()).isEqualTo("Doors open at 18:30.\n\nNo re-entry.");
		assertThat(jazz.location().name()).isEqualTo("Jazzclub Unterfahrt");
		assertThat(jazz.location().street()).isEqualTo("Einsteinstraße 42");
		assertThat(jazz.location().city()).isEqualTo("München");
		assertThat(jazz.tags()).containsExactly("#concert");
		assertThat(jazz.url()).isEqualTo("https://www.ticketmaster.de/event/jazz-night");
		assertThat(jazz.cancelledAtSource()).isFalse();

		SourceEvent cancelled = events.get(1);
		assertThat(cancelled.cancelledAtSource()).isTrue();
		// No dateTime: the local time in the event's time zone (20:00 in Munich is 19:00 UTC in November)
		assertThat(cancelled.startTime()).isEqualTo(Instant.parse("2026-11-21T19:00:00Z"));
		assertThat(cancelled.tags()).containsExactly("#theatre", "#comedy", "#family");
	}

	@Test
	void aFailedRequestFailsTheWholeFetch() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("https://app.ticketmaster.com"))).andRespond(withServerError());
		assertThatThrownBy(() -> new TicketmasterSource(properties(), builder, CLOCK).fetch())
				.isInstanceOf(HttpServerErrorException.class);
	}

	@Test
	void staysOffWithoutAnApiKey() {
		DiscoveryProperties.Ticketmaster noKey = new DiscoveryProperties.Ticketmaster(true, "0 0 * * * *", " ",
				"https://x", List.of("Munich"), "DE", Duration.ofDays(1), 10, 1, Duration.ZERO);
		DiscoveryProperties props = new DiscoveryProperties(Duration.ofHours(24), 3,
				new DiscoveryProperties.Sources(noKey, null, null, null, null), null, null, null);
		assertThat(new TicketmasterSource(props, RestClient.builder(), CLOCK).enabled()).isFalse();
	}
}
