package com.planmate.discovery.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.databind.json.JsonMapper;

class TmdbSourceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
	private static final DiscoveryProperties.Region GERMANY = new DiscoveryProperties.Region("DE", "de-DE", "Munich",
			"Germany", "Europe/Berlin");
	private static final DiscoveryProperties.Region BULGARIA = new DiscoveryProperties.Region("BG", "bg-BG", "Sofia",
			"Bulgaria", "Europe/Sofia");

	private static DiscoveryProperties properties(String key) {
		DiscoveryProperties.Tmdb tmdb = new DiscoveryProperties.Tmdb(true, "0 15 4 * * *", key,
				"https://api.themoviedb.org/3", List.of(GERMANY), Duration.ofDays(60), 25, Duration.ZERO);
		return new DiscoveryProperties(Duration.ofHours(24), 3, new DiscoveryProperties.Sources(null, null, null, tmdb),
				null, null, null);
	}

	private static String fixture(String name) throws Exception {
		return new ClassPathResource("tmdb/" + name).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void readsTheRegionsReleasesAsAllDayEventsInItsCity() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(once(), requestTo(Matchers.startsWith("https://api.themoviedb.org/3/discover/movie")))
				.andExpect(queryParam("api_key", "KEY"))
				.andExpect(queryParam("region", "DE"))
				.andExpect(queryParam("language", "de-DE"))
				.andExpect(queryParam("release_date.gte", "2026-11-06"))
				.andExpect(queryParam("release_date.lte", "2027-01-05"))
				.andRespond(withSuccess(fixture("discover.json"), MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.startsWith("https://api.themoviedb.org/3/movie/101?")))
				.andExpect(queryParam("append_to_response", "release_dates"))
				.andRespond(withSuccess(fixture("movie-101.json"), MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.startsWith("https://api.themoviedb.org/3/movie/102?")))
				.andRespond(withSuccess(fixture("movie-102.json"), MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.startsWith("https://api.themoviedb.org/3/movie/103?")))
				.andRespond(withResourceNotFound());

		List<SourceEvent> events = new TmdbSource(properties("KEY"), builder, CLOCK).fetch();
		server.verify();

		// Only a streaming release (102) and a film removed since the search (103): left out
		assertThat(events).hasSize(1);
		SourceEvent film = events.get(0);
		assertThat(film.sourceId()).isEqualTo("de-101");
		assertThat(film.title()).isEqualTo("Der Film");
		// The German cinema release (not the premiere, not the US date), all day in Munich
		assertThat(film.startTime()).isEqualTo(Instant.parse("2026-11-19T23:00:00Z"));
		assertThat(film.endTime()).isEqualTo(Instant.parse("2026-11-20T23:00:00Z"));
		assertThat(film.location()).isEqualTo(new Location("Cinemas in Munich", null, null, "Munich", "Germany"));
		assertThat(film.description()).isEqualTo(
				"Eine Heldin rettet die Stadt.\n\nOriginal title: The Film.\n\nGenre: Action, Abenteuer. Runtime: 128 min.");
		assertThat(film.tags()).containsExactly("#film", "#release");
		assertThat(film.url()).isEqualTo("https://www.themoviedb.org/movie/101");
	}

	@Test
	void eachCountryHasItsOwnReleaseDay() throws Exception {
		var movie = JsonMapper.builder().build().readTree(fixture("movie-101.json"));
		SourceEvent sofia = TmdbSource.map(movie, BULGARIA, LocalDate.parse("2026-11-06"), LocalDate.parse("2027-01-05"));

		assertThat(sofia.sourceId()).isEqualTo("bg-101");
		assertThat(sofia.startTime()).isEqualTo(Instant.parse("2026-11-20T22:00:00Z")); // 21 Nov, midnight in Sofia
		assertThat(sofia.location().city()).isEqualTo("Sofia");
		// Outside the window: nothing
		assertThat(TmdbSource.map(movie, GERMANY, LocalDate.parse("2026-11-21"), LocalDate.parse("2027-01-05"))).isNull();
	}

	@Test
	void theReadAccessTokenIsSentAsAHeader() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(once(), requestTo(Matchers.not(Matchers.containsString("api_key"))))
				.andExpect(header("Authorization", "Bearer eyJtoken"))
				.andRespond(withSuccess("{\"page\":1,\"total_pages\":1,\"results\":[]}", MediaType.APPLICATION_JSON));

		assertThat(new TmdbSource(properties("eyJtoken"), builder, CLOCK).fetch()).isEmpty();
		server.verify();
	}

	@Test
	void staysOffWithoutAKey() {
		assertThat(new TmdbSource(properties(" "), RestClient.builder(), CLOCK).enabled()).isFalse();
	}
}
