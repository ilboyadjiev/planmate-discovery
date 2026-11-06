package com.planmate.discovery.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import com.planmate.discovery.ai.AiBudget;
import com.planmate.discovery.ai.EventExtractor;
import com.planmate.discovery.ai.RefinementInput;
import com.planmate.discovery.ai.Refiner;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.OutboxMessage;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.model.TrackedEvent;
import com.planmate.discovery.publish.OutboxPublisher;
import com.planmate.discovery.publish.OutboxRepository;
import com.planmate.discovery.source.EventSource;
import com.planmate.discovery.source.web.PageEventReader;
import com.planmate.discovery.source.web.WebFeed;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service against real MongoDB and Redpanda (Docker): a test source, a counting stand-in for the AI and
 * a clock the test moves forward.
 */
@SpringBootTest(properties = {
		"discovery.run-on-startup=false",
		"discovery.sources.ticketmaster.enabled=false",
		"discovery.sources.curated.enabled=false",
		"discovery.sources.feeds.enabled=false",
		"discovery.sources.tmdb.enabled=false",
		"discovery.sources.feeds.ai-page-reads-per-day=2",
		"discovery.ai.api-key=test-key",
		"discovery.ai.pause-between-requests=0s",
		"discovery.ai.retry-delays=0s,0s",
		"discovery.ai.batch-size=2",
		"discovery.outbox.initial-delay=1h",
		"discovery.outbox.publish-interval=1h" })
@Testcontainers
class DiscoveryIntegrationTest {

	@Container
	@ServiceConnection
	static MongoDBContainer mongo = new MongoDBContainer("mongo:6");

	@Container
	@ServiceConnection
	static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v25.3.18");

	/** A clock the test moves forward. */
	static final class MovableClock extends Clock {
		Instant now = Instant.parse("2026-11-05T10:00:00Z");

		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
		void advance(Duration d) { now = now.plus(d); }
	}

	/** The source under test: returns whatever the test puts in, or fails. */
	static final class TestSource implements EventSource {
		List<SourceEvent> events = new ArrayList<>();
		boolean failing;

		@Override public String id() { return "test"; }
		@Override public String displayName() { return "Test Source"; }
		@Override public String cron() { return "0 0 0 1 1 *"; }
		@Override public boolean enabled() { return false; } // run by the test, not the scheduler
		@Override public List<SourceEvent> fetch() {
			if (failing) {
				throw new IllegalStateException("source is down");
			}
			return List.copyOf(events);
		}
	}

	/** Stands in for Gemini: counts calls and events, can fail or break the rules. */
	static final class FakeRefiner implements Refiner {
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger events = new AtomicInteger();
		boolean failing;
		/** Answers 503 "high demand" this many times before it answers. */
		int busyTimes;

		@Override
		public Map<String, RefinedText> refine(List<RefinementInput> batch) {
			calls.incrementAndGet();
			if (busyTimes > 0) {
				busyTimes--;
				throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
						HttpHeaders.EMPTY, "{\"error\":{\"code\":503}}".getBytes(), null);
			}
			events.addAndGet(batch.size());
			if (failing) {
				throw new IllegalStateException("quota exceeded");
			}
			Map<String, RefinedText> answers = new java.util.LinkedHashMap<>();
			batch.forEach(in -> answers.put(in.id(), new RefinedText("Refined: " + in.title(), List.of("Book ahead"),
					List.of("#concert", "#munich"))));
			return answers;
		}
	}

	/** Stands in for Gemini reading a page: reads every "<h2>" as an event on 20 Nov at 20:00. */
	static final class FakeExtractor implements EventExtractor {
		final AtomicInteger calls = new AtomicInteger();
		boolean failing;

		@Override
		public List<Extracted> extract(Input input) {
			calls.incrementAndGet();
			if (failing) {
				throw new IllegalStateException("quota exceeded");
			}
			return input.page().lines().filter(l -> l.startsWith("Show "))
					.map(l -> new Extracted(l.trim(), "2026-11-20T20:00", "", "", "", "", "", "", false)).toList();
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean @Primary FakeExtractor fakeExtractor() { return new FakeExtractor(); }
		@Bean @Primary MovableClock testClock() { return new MovableClock(); }
		@Bean TestSource testSource() { return new TestSource(); }
		@Bean @Primary FakeRefiner fakeRefiner() { return new FakeRefiner(); }
	}

	@Autowired DiscoveryPipeline pipeline;
	@Autowired OutboxPublisher publisher;
	@Autowired OutboxRepository outbox;
	@Autowired MongoTemplate mongoTemplate;
	@Autowired TestSource source;
	@Autowired FakeRefiner ai;
	@Autowired MovableClock clock;
	@Autowired JsonMapper json;
	@Autowired AiBudget budget;
	@Autowired PageEventReader pageReader;
	@Autowired FakeExtractor extractor;

	private KafkaConsumer<String, String> consumer;

	@BeforeEach
	void setUp() {
		mongoTemplate.getDb().drop();
		source.events.clear();
		source.failing = false;
		ai.failing = false;
		ai.busyTimes = 0;
		ai.calls.set(0);
		ai.events.set(0);
		extractor.calls.set(0);
		extractor.failing = false;
		clock.now = Instant.parse("2026-11-05T10:00:00Z");
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
		consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
		consumer.subscribe(List.of("discovery.events"));
		// Join the group, then start after everything already on the topic (earlier tests' messages)
		for (int i = 0; i < 60 && consumer.assignment().isEmpty(); i++) {
			consumer.poll(Duration.ofMillis(250));
		}
		assertThat(consumer.assignment()).isNotEmpty();
		consumer.seekToEnd(consumer.assignment());
		consumer.assignment().forEach(consumer::position);
	}

	@AfterEach
	void tearDown() {
		consumer.close();
	}

	private List<ConsumerRecord<String, String>> poll() {
		List<ConsumerRecord<String, String>> records = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			consumer.poll(Duration.ofMillis(500)).forEach(records::add);
		}
		return records;
	}

	private static SourceEvent event(String id, String title, String description, String start) {
		return new SourceEvent(id, title, description, Instant.parse(start), null,
				new Location("Unterfahrt", "Einsteinstraße 42", "81675", "Munich", "Germany"), List.of("#jazz"),
				"https://tickets.example/" + id, false);
	}

	private JsonNode message(ConsumerRecord<String, String> record) {
		return json.readTree(record.value());
	}

	/** One run, then publishing; the messages that arrived. */
	private List<ConsumerRecord<String, String>> runAndPublish() {
		pipeline.run(source);
		publisher.publishWaiting();
		return poll();
	}

	@Test
	void newEventsArePublishedOnceAndAnUnchangedRunPublishesNothingAndAsksNoAi() {
		source.events.add(event("1", "Jazz Night", "Live jazz with the big band.", "2026-11-20T18:00:00Z"));
		source.events.add(event("2", "Rock Night", "Loud guitars.", "2026-11-21T19:00:00Z"));
		source.events.add(event("3", "Last week", "Already over.", "2026-10-01T19:00:00Z"));

		List<ConsumerRecord<String, String>> first = runAndPublish();
		assertThat(first).extracting(ConsumerRecord::key).containsExactlyInAnyOrder("test-1", "test-2");
		JsonNode jazz = message(first.stream().filter(r -> r.key().equals("test-1")).findFirst().orElseThrow());
		assertThat(jazz.path("type").asString()).isEqualTo("EventDiscovered");
		assertThat(jazz.path("version").asLong()).isEqualTo(1);
		assertThat(jazz.path("event").path("description").asString()).isEqualTo("Refined: Jazz Night\n\nTip: Book ahead");
		assertThat(jazz.path("event").path("tags").toString()).isEqualTo("[\"#concert\",\"#munich\"]");
		assertThat(jazz.path("event").path("sourceName").asString()).isEqualTo("Test Source");
		assertThat(jazz.path("event").path("startTime").asString()).isEqualTo("2026-11-20T18:00:00Z");
		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(ai.events.get()).isEqualTo(2);

		// The acceptance check: the same data again publishes nothing and asks the AI nothing
		clock.advance(Duration.ofHours(6));
		assertThat(runAndPublish()).isEmpty();
		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(outbox.countByPublishedAtIsNull()).isZero();
	}

	@Test
	void aNewTimeIsAnUpdateWithoutAiAndNewTextAsksTheAiAgain() {
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		runAndPublish();

		source.events.set(0, event("1", "Jazz Night", "Live jazz.", "2026-11-20T19:00:00Z"));
		List<ConsumerRecord<String, String>> moved = runAndPublish();
		assertThat(moved).hasSize(1);
		assertThat(message(moved.get(0)).path("type").asString()).isEqualTo("EventUpdated");
		assertThat(message(moved.get(0)).path("version").asLong()).isEqualTo(2);
		assertThat(message(moved.get(0)).path("event").path("description").asString()).startsWith("Refined: Jazz Night");
		assertThat(ai.calls.get()).isEqualTo(1);

		source.events.set(0, event("1", "Jazz Night (sold out)", "Live jazz, now sold out.", "2026-11-20T19:00:00Z"));
		List<ConsumerRecord<String, String>> retitled = runAndPublish();
		assertThat(message(retitled.get(0)).path("version").asLong()).isEqualTo(3);
		assertThat(message(retitled.get(0)).path("event").path("description").asString()).startsWith("Refined: Jazz Night (sold out)");
		assertThat(ai.calls.get()).isEqualTo(2);
	}

	@Test
	void anEventMissingForTwentyFourHoursOfSuccessfulFetchesIsCancelled() {
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		runAndPublish();

		source.events.clear();
		assertThat(runAndPublish()).isEmpty(); // missing since now
		clock.advance(Duration.ofHours(23));
		assertThat(runAndPublish()).isEmpty();

		// The source is down for days: that never counts
		source.failing = true;
		clock.advance(Duration.ofHours(30));
		assertThat(runAndPublish()).isEmpty();
		source.failing = false;

		clock.advance(Duration.ofHours(1));
		List<ConsumerRecord<String, String>> cancelled = runAndPublish();
		assertThat(cancelled).hasSize(1);
		JsonNode message = message(cancelled.get(0));
		assertThat(message.path("type").asString()).isEqualTo("EventCancelled");
		assertThat(message.path("event").path("cancelled").asBoolean()).isTrue();
		assertThat(message.path("event").path("title").asString()).isEqualTo("Jazz Night");

		// Listed again later: back, as an update
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		List<ConsumerRecord<String, String>> back = runAndPublish();
		assertThat(message(back.get(0)).path("type").asString()).isEqualTo("EventUpdated");
		assertThat(message(back.get(0)).path("event").path("cancelled").asBoolean()).isFalse();
	}

	@Test
	void eventsTheSourceMarksCancelledAreCancelledRightAway() {
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		runAndPublish();
		SourceEvent e = source.events.get(0);
		source.events.set(0, new SourceEvent(e.sourceId(), e.title(), e.description(), e.startTime(), null, e.location(),
				e.tags(), e.url(), true));
		assertThat(message(runAndPublish().get(0)).path("type").asString()).isEqualTo("EventCancelled");
		assertThat(runAndPublish()).isEmpty();
	}

	@Test
	void whenTheAiCannotBeAskedTheSourceTextIsPublishedAndRefinedOnTheNextRun() {
		ai.failing = true;
		source.events.add(event("1", "Jazz Night", "Live   jazz\twith friends.", "2026-11-20T18:00:00Z"));
		List<ConsumerRecord<String, String>> first = runAndPublish();
		assertThat(message(first.get(0)).path("event").path("description").asString()).isEqualTo("Live jazz with friends.");
		assertThat(message(first.get(0)).path("event").path("tags").toString()).isEqualTo("[\"#jazz\"]");
		assertThat(mongoTemplate.findById("test-1", TrackedEvent.class).isRefinementPending()).isTrue();

		ai.failing = false;
		List<ConsumerRecord<String, String>> retried = runAndPublish();
		assertThat(message(retried.get(0)).path("type").asString()).isEqualTo("EventUpdated");
		assertThat(message(retried.get(0)).path("event").path("description").asString()).startsWith("Refined: Jazz Night");
		assertThat(runAndPublish()).isEmpty();
	}

	@Test
	void theAiGetsAtMostItsDailyBudgetAndTheRestWaitsForTomorrow() {
		mongoTemplate.save(new org.bson.Document("_id", "2026-11-05").append("events", 299), "ai_usage");
		source.events.add(event("1", "One", "a", "2026-11-20T18:00:00Z"));
		source.events.add(event("2", "Two", "b", "2026-11-20T18:00:00Z"));
		source.events.add(event("3", "Three", "c", "2026-11-20T18:00:00Z"));
		runAndPublish();
		assertThat(ai.events.get()).isEqualTo(1); // 300 reached
		assertThat(mongoTemplate.findAll(TrackedEvent.class)).filteredOn(TrackedEvent::isRefinementPending).hasSize(2);

		clock.advance(Duration.ofDays(1));
		runAndPublish();
		assertThat(ai.events.get()).isEqualTo(3);
	}

	@Test
	void messagesWaitInTheOutboxUntilRedpandaConfirmsThem() {
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		pipeline.run(source);
		OutboxMessage waiting = outbox.findAll().get(0);
		assertThat(waiting.getId()).isEqualTo("test-1:1");
		assertThat(waiting.getPublishedAt()).isNull();
		assertThat(publisher.publishWaiting()).isEqualTo(1);
		assertThat(outbox.findById("test-1:1").orElseThrow().getPublishedAt()).isNotNull();
		assertThat(publisher.publishWaiting()).isZero();
	}

	@Test
	void aBusyAiIsAskedAgainAndAnswersInTheSameRun() {
		ai.busyTimes = 2;
		source.events.add(event("1", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));
		List<ConsumerRecord<String, String>> first = runAndPublish();
		assertThat(message(first.get(0)).path("event").path("description").asString()).startsWith("Refined: Jazz Night");
		assertThat(ai.calls.get()).isEqualTo(3);
		assertThat(budget.used()).isEqualTo(1);
	}

	@Test
	void aBusyAiStopsTheRunsRequestsAndFailedRequestsDoNotCountAgainstTheBudget() {
		ai.busyTimes = 100;
		for (int i = 1; i <= 5; i++) {
			source.events.add(event(String.valueOf(i), "Show " + i, "Text " + i, "2026-11-20T18:00:00Z"));
		}
		List<ConsumerRecord<String, String>> first = runAndPublish();
		assertThat(first).hasSize(5); // published with their own text
		assertThat(ai.calls.get()).isEqualTo(3); // the first batch, asked again twice; the other batches not at all
		assertThat(budget.used()).isZero();
		assertThat(mongoTemplate.findAll(TrackedEvent.class)).allMatch(TrackedEvent::isRefinementPending);

		ai.busyTimes = 0;
		assertThat(runAndPublish()).hasSize(5);
		assertThat(budget.used()).isEqualTo(5);
	}

	@Test
	void ofAShowListedOncePerDateOnlyTheNextThreeDatesAreTakenIn() {
		for (int day = 10; day <= 14; day++) {
			source.events.add(event("comedy-" + day, "Comedy. Dinner. Show.", "Dinner and comedy.", "2026-11-" + day + "T18:00:00Z"));
		}
		source.events.add(event("jazz", "Jazz Night", "Live jazz.", "2026-11-20T18:00:00Z"));

		assertThat(runAndPublish()).extracting(ConsumerRecord::key)
				.containsExactlyInAnyOrder("test-comedy-10", "test-comedy-11", "test-comedy-12", "test-jazz");
		assertThat(runAndPublish()).isEmpty();

		// The first date has passed: the next one comes in
		clock.now = Instant.parse("2026-11-10T23:00:00Z");
		assertThat(runAndPublish()).extracting(ConsumerRecord::key).containsExactly("test-comedy-13");
	}

	@Test
	void theAiReadsAPageOnlyWhenItsTextChangedAndWithinItsOwnDailyLimit() throws Exception {
		WebFeed feed = new WebFeed("venue", "Venue", "https://venue.example/programm", "ai", null,
				new Location("Venue", null, null, "Munich", "Germany"), List.of());
		java.util.function.Function<String, org.jsoup.nodes.Document> page = shows -> org.jsoup.Jsoup.parse(
				"<main>" + shows + "</main>", "https://venue.example/programm");

		assertThat(pageReader.read(feed, page.apply("<p>Show A</p><p>Show B</p>"), clock.now)).hasSize(2);
		assertThat(pageReader.read(feed, page.apply("<p>Show A</p><p>Show B</p>"), clock.now)).hasSize(2);
		assertThat(extractor.calls.get()).as("an unchanged page is not read again").isEqualTo(1);
		assertThat(budget.used()).as("page reads do not use the descriptions' budget").isZero();

		// A failed read does not count
		extractor.failing = true;
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> pageReader.read(feed, page.apply("<p>Show C</p>"), clock.now));
		assertThat(budget.pageReadsUsed()).isEqualTo(1);
		extractor.failing = false;

		assertThat(pageReader.read(feed, page.apply("<p>Show C</p>"), clock.now)).extracting(SourceEvent::title)
				.containsExactly("Show C");
		// Two reads today: the next changed page waits for tomorrow (the run fails, so nothing is cancelled)
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> pageReader.read(feed, page.apply("<p>Show D</p>"), clock.now))
				.hasMessageContaining("used up");
		clock.advance(Duration.ofDays(1));
		assertThat(pageReader.read(feed, page.apply("<p>Show D</p>"), clock.now)).hasSize(1);
	}
}
