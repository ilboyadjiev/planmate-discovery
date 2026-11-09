package com.planmate.discovery.source.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GoogleCalendarSourceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
	private static final DiscoveryProperties.Calendar CAL = new DiscoveryProperties.Calendar("golemiatquiz",
			"Големият куиз", "cal@import.calendar.google.com", "Sofia", "Bulgaria", "Europe/Sofia", List.of("#quiz"));
	private static final DiscoveryProperties.GoogleCalendar CONFIG = new DiscoveryProperties.GoogleCalendar(true,
			"0 40 5,17 * * *", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/calendar/v3",
			"id", "secret", "token", List.of(CAL), Duration.ofDays(90), 300, Duration.ZERO);

	private static List<JsonNode> items(String resource) throws Exception {
		JsonNode tree = JsonMapper.builder().build()
				.readTree(new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8));
		List<JsonNode> items = new ArrayList<>();
		tree.path("items").forEach(items::add);
		return items;
	}

	@Test
	void readsTimedAndMultiDayEventsAndLeavesOutCancelledSingleDayAndUntitledOnes() throws Exception {
		GoogleCalendarClient client = mock(GoogleCalendarClient.class);
		when(client.configured()).thenReturn(true);
		when(client.listEvents(eq("cal@import.calendar.google.com"), any(), any(), anyInt(), anyLong()))
				.thenReturn(items("google/events.json"));

		GoogleCalendarSource source = new GoogleCalendarSource(CAL, CONFIG, client, CLOCK);

		assertThat(source.id()).isEqualTo("gcal-golemiatquiz");
		assertThat(source.displayName()).isEqualTo("Големият куиз");
		assertThat(source.enabled()).isTrue();

		List<SourceEvent> events = source.fetch();
		// ev2 cancelled, ev3 single all-day, ev5 no title: all left out
		assertThat(events).extracting(SourceEvent::title).containsExactly("ГОЛЕМИЯТ QUIZ @ Bar'loga", "Quiz week");

		SourceEvent quiz = events.get(0);
		assertThat(quiz.sourceId()).isEqualTo("ev1");
		assertThat(quiz.startTime()).isEqualTo(Instant.parse("2026-11-20T17:00:00Z")); // 19:00 +02:00
		assertThat(quiz.endTime()).isEqualTo(Instant.parse("2026-11-20T19:00:00Z"));
		assertThat(quiz.description()).isEqualTo("Pub quiz night");
		assertThat(quiz.url()).isEqualTo("https://tockify.com/golemiatquiz/detail/112/1791478800000");
		assertThat(quiz.tags()).containsExactly("#quiz");
		// The venue is the first part of the free-text location; the city and country come from the calendar
		assertThat(quiz.location()).isEqualTo(new Location("Бар'лога", null, null, "Sofia", "Bulgaria"));

		SourceEvent week = events.get(1);
		ZoneId sofia = ZoneId.of("Europe/Sofia");
		assertThat(week.startTime()).isEqualTo(LocalDate.parse("2026-11-27").atStartOfDay(sofia).toInstant());
		assertThat(week.endTime()).isEqualTo(LocalDate.parse("2026-11-30").atStartOfDay(sofia).toInstant());
	}

	@Test
	void isOffWithoutCredentials() {
		GoogleCalendarClient client = mock(GoogleCalendarClient.class);
		when(client.configured()).thenReturn(false);
		assertThat(new GoogleCalendarSource(CAL, CONFIG, client, CLOCK).enabled()).isFalse();
	}
}
