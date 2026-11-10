package com.planmate.discovery.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.planmate.discovery.ai.EventExtractor;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

class WebFeedParsersTest {

	static final WebFeed MUNICH_VENUE = new WebFeed("venue", "Venue", "https://venue.example/programm", null, null,
			new Location("Venue", "Hauptstraße 1", "80331", "Munich", "Germany"), List.of("#concert"), false);

	static String resource(String name) throws IOException {
		try (InputStream in = WebFeedParsersTest.class.getResourceAsStream("/web/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static Document page(String name) throws IOException {
		return Jsoup.parse(resource(name), "https://venue.example/programm");
	}

	// --- iCal ---

	@Test
	void readsAnIcsFile() throws IOException {
		List<SourceEvent> events = IcsParser.parse(resource("venue.ics"), MUNICH_VENUE);

		assertThat(events).extracting(SourceEvent::title).containsExactly("Jazz am Abend, live", "Christkindlmarkt", "Talk");
		SourceEvent jazz = events.get(0);
		assertThat(jazz.startTime()).isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
		assertThat(jazz.endTime()).isEqualTo(Instant.parse("2026-11-14T22:00:00Z"));
		assertThat(jazz.description()).isEqualTo("Ein langer Abend mit Jazz.\nZweite Zeile mit einem sehr langen Text, der gefaltet ist.");
		assertThat(jazz.url()).isEqualTo("https://venue.example/events/jazz-42");
		assertThat(jazz.tags()).containsExactly("#concert");
		// Another venue than the feed's: only the city and country come from the feed
		assertThat(jazz.location()).isEqualTo(new Location("Jazzclub Unterfahrt", "Einsteinstraße 42", "81675", "München", "Germany"));
		assertThat(jazz.sourceId()).isEqualTo(IcsParser.parse(resource("venue.ics"), MUNICH_VENUE).get(0).sourceId());

		SourceEvent market = events.get(1);
		assertThat(market.cancelledAtSource()).isTrue();
		assertThat(market.startTime()).isEqualTo(Instant.parse("2026-11-30T23:00:00Z")); // midnight in Munich
		assertThat(market.endTime()).isEqualTo(Instant.parse("2026-12-02T23:00:00Z"));
		assertThat(market.location()).isEqualTo(MUNICH_VENUE.location()); // no place of its own: the venue's

		SourceEvent talk = events.get(2);
		assertThat(talk.startTime()).isEqualTo(Instant.parse("2026-11-15T17:00:00Z"));
		assertThat(talk.endTime()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));
	}

	// --- schema.org ---

	@Test
	void readsSchemaOrgEventsFromAPage() throws IOException {
		List<SourceEvent> events = JsonLdParser.parse(page("venue-jsonld.html"), MUNICH_VENUE);

		assertThat(events).extracting(SourceEvent::title).containsExactly("Dark Tenor & Friends", "Tollwood", "Mit Zeilenumbruch");
		SourceEvent tenor = events.get(0);
		assertThat(tenor.startTime()).isEqualTo(Instant.parse("2026-11-20T19:00:00Z"));
		assertThat(tenor.endTime()).isEqualTo(Instant.parse("2026-11-20T22:00:00Z")); // local time, in the feed's zone
		assertThat(tenor.url()).isEqualTo("https://venue.example/events/dark-tenor");
		assertThat(tenor.description()).isEqualTo("Klassik trifft Pop.\n\nZweiter Absatz");
		assertThat(tenor.location()).isEqualTo(new Location("Olympiahalle", "Spiridon-Louis-Ring 21", "80809", "München", "DE"));

		SourceEvent tollwood = events.get(1);
		assertThat(tollwood.cancelledAtSource()).isTrue();
		assertThat(tollwood.startTime()).isEqualTo(Instant.parse("2026-11-24T23:00:00Z"));
		assertThat(tollwood.endTime()).isEqualTo(Instant.parse("2026-11-28T23:00:00Z")); // through its last day
		assertThat(tollwood.location().name()).isEqualTo("Theresienwiese");
		assertThat(tollwood.location().city()).isEqualTo("München");
		assertThat(tollwood.url()).isEqualTo("https://venue.example/programm"); // no link of its own

		assertThat(events.get(2).startTime()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));
	}

	@Test
	void pagesWithoutEventDataGiveNothing() throws IOException {
		assertThat(JsonLdParser.parse(page("venue-plain.html"), MUNICH_VENUE)).isEmpty();
	}

	// --- Pages read by the AI ---

	@Test
	void theAiReadsTheVisibleTextWithLinks() throws IOException {
		String text = PageEventReader.pageText(page("venue-plain.html"));

		assertThat(text).contains("Smile and Burn", "Fr. 20.11.2026, 20:00",
				"Mehr Infos <https://venue.example/konzert/smile-and-burn>");
		assertThat(text).doesNotContain("not text", "Kontakt", "Impressum");
	}

	@Test
	void onlyEventsThatAreOnThePageAreKept() throws IOException {
		String text = PageEventReader.pageText(page("venue-plain.html"));
		List<EventExtractor.Extracted> answer = List.of(
				new EventExtractor.Extracted("Smile and Burn", "2026-11-20T20:00", "", "Hansa 39", "", "",
						"https://venue.example/konzert/smile-and-burn", "Punkrock aus Berlin", false),
				new EventExtractor.Extracted("Kabarett mit Matthias", "2026-11-21T19:30", "", "", "", "",
						"https://evil.example/phishing", "", true),
				new EventExtractor.Extracted("Made-up headliner", "2026-11-22T20:00", "", "", "", "", "", "", false),
				new EventExtractor.Extracted("Smile and Burn", "sometime", "", "", "", "", "", "", false),
				new EventExtractor.Extracted("Smile and Burn", "2026-11-20", "", "", "", "", "", "", false),
				new EventExtractor.Extracted("Programm", "2026-11-20", "2026-11-27", "", "Haus der Kunst",
						"Prinzregentenstraße 1, 80538 München", "", "", false));

		List<SourceEvent> events = PageEventReader.accept(answer, MUNICH_VENUE, text, "https://venue.example/programm");

		// Not on the page, no readable start, and a single day without a time: left out
		assertThat(events).extracting(SourceEvent::title).containsExactly("Smile and Burn", "Kabarett mit Matthias", "Programm");
		SourceEvent smile = events.get(0);
		assertThat(smile.startTime()).isEqualTo(Instant.parse("2026-11-20T19:00:00Z"));
		assertThat(smile.url()).isEqualTo("https://venue.example/konzert/smile-and-burn");
		// A hall of the feed's own venue keeps the venue's address
		assertThat(smile.location()).isEqualTo(new Location("Venue – Hansa 39", "Hauptstraße 1", "80331", "Munich", "Germany"));
		assertThat(smile.cancelledAtSource()).isFalse();
		assertThat(events.get(1).cancelledAtSource()).isTrue();
		SourceEvent exhibition = events.get(2);
		assertThat(exhibition.endTime()).isEqualTo(Instant.parse("2026-11-27T23:00:00Z"));
		assertThat(exhibition.location()).isEqualTo(new Location("Haus der Kunst", "Prinzregentenstraße 1", "80538", "München", "Germany"));
		// A link the page does not have is replaced by the page itself
		assertThat(events.get(1).url()).isEqualTo("https://venue.example/programm");
		assertThat(events.get(1).location()).isEqualTo(MUNICH_VENUE.location());
	}

	// --- Shared helpers ---

	@Test
	void readsTimesAsPagesWriteThem() {
		var berlin = java.time.ZoneId.of("Europe/Berlin");
		assertThat(WebEvents.instant("2026-11-14T20:00:00+01:00", berlin)).isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
		assertThat(WebEvents.instant("2026-11-14T20:00:00+0100", berlin)).isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
		assertThat(WebEvents.instant("2026-11-14T19:00:00Z", berlin)).isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
		assertThat(WebEvents.instant("2026-11-14 20:00", berlin)).isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
		assertThat(WebEvents.instant("2026-11-14", java.time.ZoneId.of("Europe/Sofia"))).isEqualTo(Instant.parse("2026-11-13T22:00:00Z"));
		assertThat(WebEvents.instant("next friday", berlin)).isNull();
	}

	@Test
	void linksMustBeWebLinks() {
		assertThat(WebEvents.link("/a", "https://venue.example/programm")).isEqualTo("https://venue.example/a");
		assertThat(WebEvents.link("javascript:alert(1)", "https://venue.example/")).isNull();
		assertThat(WebEvents.link("mailto:x@y.z", null)).isNull();
	}

	// --- robots.txt ---

	@Test
	void followsRobotsTxt() {
		String robots = """
				User-agent: *
				Disallow: /intern/
				Allow: /intern/programm
				Disallow: /*.pdf$

				User-agent: GPTBot
				Disallow: /
				""";
		RobotsRules rules = RobotsRules.parse(robots, "PlanMateBot");
		assertThat(rules.allows("/programm")).isTrue();
		assertThat(rules.allows("/intern/team")).isFalse();
		assertThat(rules.allows("/intern/programm")).isTrue(); // the longer rule wins
		assertThat(rules.allows("/files/plan.pdf")).isFalse();
		assertThat(rules.allows("/files/plan.pdf?x=1")).isTrue();

		RobotsRules forUs = RobotsRules.parse(robots + "\nUser-agent: PlanMateBot\nDisallow: /\n", "PlanMateBot");
		assertThat(forUs.allows("/programm")).isFalse(); // our own group replaces the one for everyone
		assertThat(RobotsRules.ALLOW_ALL.allows("/anything")).isTrue();
	}

	// --- The feed file ---

	@Test
	void feedEntriesAreChecked() {
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new WebFeed("Bad Id", "X", "https://x.example", null, null, null, null, false).validate())
				.hasMessageContaining("Bad Id");
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new WebFeed("ok", "X", "ftp://x.example", null, null, null, null, false).validate())
				.hasMessageContaining("http(s)");
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new WebFeed("ok", "X", "https://x.example", "pdf", null, null, null, false).validate())
				.hasMessageContaining("format");
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new WebFeed("ok", "X", "https://x.example", null, "Mars/Olympus", null, null, false).validate())
				.hasMessageContaining("time zone");
		new WebFeed("ok", "X", "https://x.example", "json-ld", "Europe/Sofia", null, null, false).validate();
	}
}
