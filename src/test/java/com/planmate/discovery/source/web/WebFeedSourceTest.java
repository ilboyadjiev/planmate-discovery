package com.planmate.discovery.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.pipeline.DiscoveryPipeline;

class WebFeedSourceTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

	private final PageFetcher fetcher = mock(PageFetcher.class);
	private final PageEventReader ai = mock(PageEventReader.class);

	private static DiscoveryProperties.Feeds config(boolean aiFallback, Duration lookAhead, int max) {
		return new DiscoveryProperties.Feeds(true, "0 45 5,17 * * *", "classpath:feeds/web-feeds.yml", aiFallback, 40,
				lookAhead, max, 3_000_000, Duration.ZERO, "PlanMateBot/1.0");
	}

	private WebFeedSource source(String format, DiscoveryProperties.Feeds config) {
		WebFeed feed = new WebFeed("venue", "Venue", "https://venue.example/programm", format, null,
				WebFeedParsersTest.MUNICH_VENUE.location(), List.of());
		return new WebFeedSource(feed, config, fetcher, ai, NOW);
	}

	private void serves(String resource, MediaType type) throws Exception {
		when(fetcher.get("https://venue.example/programm")).thenReturn(new PageFetcher.Page("https://venue.example/programm",
				type, WebFeedParsersTest.resource(resource).getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void autoReadsCalendarsAndSchemaOrgWithoutTheAi() throws Exception {
		serves("venue.ics", MediaType.TEXT_PLAIN); // a calendar served as text is still recognised
		assertThat(source(null, config(true, Duration.ofDays(120), 300)).fetch()).hasSize(3);

		serves("venue-jsonld.html", MediaType.TEXT_HTML);
		assertThat(source("auto", config(true, Duration.ofDays(120), 300)).fetch()).hasSize(3);
		verify(ai, never()).read(any(), any(), any());
	}

	@Test
	void pagesWithoutEventDataGoToTheAi() throws Exception {
		serves("venue-plain.html", MediaType.TEXT_HTML);
		SourceEvent read = new SourceEvent("x", "Smile and Burn", null, Instant.parse("2026-11-20T19:00:00Z"), null,
				null, List.of(), null, false);
		when(ai.read(any(), any(), eq(NOW.instant()))).thenReturn(List.of(read));

		assertThat(source(null, config(true, Duration.ofDays(120), 300)).fetch()).containsExactly(read);
	}

	@Test
	void aPageWhereNothingCanBeReadFailsTheRun() throws Exception {
		serves("venue-plain.html", MediaType.TEXT_HTML);
		// Failing (rather than "no events") keeps a redesigned site from cancelling all its events
		assertThatThrownBy(() -> source(null, config(false, Duration.ofDays(120), 300)).fetch())
				.hasMessageContaining("ai-fallback");
		assertThatThrownBy(() -> source("json-ld", config(true, Duration.ofDays(120), 300)).fetch())
				.hasMessageContaining("schema.org");
	}

	@Test
	void keepsTheSoonestEventsWithinLookAhead() throws Exception {
		serves("venue.ics", MediaType.parseMediaType("text/calendar; charset=utf-8"));
		// 14 Nov, 15 Nov and 1 Dec: ten days ahead leaves the two in November, and at most one is kept
		assertThat(source(null, config(true, Duration.ofDays(10), 300)).fetch()).extracting(SourceEvent::title)
				.containsExactly("Jazz am Abend, live", "Talk");
		assertThat(source(null, config(true, Duration.ofDays(10), 1)).fetch()).extracting(SourceEvent::title)
				.containsExactly("Jazz am Abend, live");
	}

	@Test
	void theShippedFeedFileIsValid() throws Exception {
		DiscoveryProperties properties = new DiscoveryProperties(Duration.ofHours(24), 3,
				new DiscoveryProperties.Sources(null, null, config(true, Duration.ofDays(120), 300), null, null), null, null, null);
		WebFeedRunner runner = new WebFeedRunner(properties, new DefaultResourceLoader(), mock(DiscoveryPipeline.class),
				fetcher, ai, NOW);

		List<WebFeedSource> sources = runner.sources();

		assertThat(sources).isNotEmpty();
		assertThat(sources).extracting(WebFeedSource::id).contains("web-olympiapark", "web-ndk").doesNotHaveDuplicates();
	}
}
