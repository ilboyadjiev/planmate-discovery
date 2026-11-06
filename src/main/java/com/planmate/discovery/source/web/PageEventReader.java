package com.planmate.discovery.source.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.planmate.discovery.ai.AiBudget;
import com.planmate.discovery.ai.EventExtractor;
import com.planmate.discovery.ai.RefinementService;
import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * For pages without a calendar file or schema.org data: the AI reads the events from the page's visible text.
 *
 * - Only when the page's text changed since it was last read (collection "web_feed_extractions"), so an unchanged
 *   page costs nothing.
 * - At most aiPageReadsPerDay pages a day (AiBudget), separate from the budget for descriptions.
 * - Its answers are checked: an event is kept only if its title appears on the page and its start can be read, and
 *   a link only if the page has it.
 * - When the AI cannot be asked (off, budget used up, errors), the run fails, so nothing counts as cancelled.
 */
@Component
public class PageEventReader {

	private static final Logger log = LoggerFactory.getLogger(PageEventReader.class);
	static final String COLLECTION = "web_feed_extractions";
	static final int MAX_PAGE_TEXT = 40_000;
	static final int MAX_DESCRIPTION = 1500;
	private static final TypeReference<List<SourceEvent>> EVENTS = new TypeReference<>() {
	};

	private final DiscoveryProperties.Ai config;
	private final EventExtractor extractor;
	private final AiBudget budget;
	private final MongoTemplate mongo;
	private final JsonMapper json;

	public PageEventReader(DiscoveryProperties properties, EventExtractor extractor, AiBudget budget,
			MongoTemplate mongo, JsonMapper json) {
		this.config = properties.ai();
		this.extractor = extractor;
		this.budget = budget;
		this.mongo = mongo;
		this.json = json;
	}

	public boolean available() {
		return config.enabled() && config.apiKey() != null && !config.apiKey().isBlank();
	}

	public List<SourceEvent> read(WebFeed feed, Document page, Instant now) throws Exception {
		String text = pageText(page);
		if (text == null) {
			return List.of();
		}
		String hash = WebEvents.key(text);
		org.bson.Document cached = mongo.findById(feed.id(), org.bson.Document.class, COLLECTION);
		if (cached != null && hash.equals(cached.getString("textHash"))) {
			return json.readValue(cached.getString("events"), EVENTS);
		}
		if (!available()) {
			throw new IllegalStateException("the page has no calendar or event data, and the AI is off");
		}
		if (!budget.reservePageRead()) {
			throw new IllegalStateException("today's AI page reads are used up; the page is read on a later run");
		}
		List<EventExtractor.Extracted> answer;
		try {
			answer = ask(new EventExtractor.Input(page.location(), feed.name(),
					LocalDate.ofInstant(now, feed.zone()).toString(), feed.zone().getId(), text));
		} catch (Exception e) {
			budget.releasePageRead();
			throw e;
		}
		List<SourceEvent> events = accept(answer, feed, text, page.location());
		if (events.size() < answer.size()) {
			log.info("web-{}: kept {} of the {} events the AI read; the rest did not match the page", feed.id(),
					events.size(), answer.size());
		}
		mongo.save(new org.bson.Document("_id", feed.id()).append("textHash", hash)
				.append("events", json.writeValueAsString(events)).append("readAt", java.util.Date.from(now)), COLLECTION);
		return events;
	}

	private List<EventExtractor.Extracted> ask(EventExtractor.Input input) throws Exception {
		List<Duration> delays = config.retryDelays() == null ? List.of() : config.retryDelays();
		for (int attempt = 0;; attempt++) {
			try {
				return extractor.extract(input);
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				if (attempt >= delays.size() || !RefinementService.temporary(e)) {
					throw e;
				}
				Thread.sleep(delays.get(attempt).toMillis());
			}
		}
	}

	/**
	 * The AI's events that hold up against the page. An event with a date but no time is kept only when it runs over
	 * several days (an exhibition): a concert at "00:00" would be wrong in everyone's calendar.
	 */
	static List<SourceEvent> accept(List<EventExtractor.Extracted> answer, WebFeed feed, String pageText, String pageUrl) {
		String onPage = normalised(pageText);
		List<SourceEvent> events = new ArrayList<>();
		for (EventExtractor.Extracted e : answer) {
			String title = WebEvents.line(e.title(), WebEvents.MAX_TITLE);
			Instant start = WebEvents.instant(e.start(), feed.zone());
			if (title == null || start == null || !onPage.contains(cutChars(normalised(title), 40))) {
				continue; // not on the page: made up or garbled
			}
			Instant end = WebEvents.instant(e.end(), feed.zone());
			if (WebEvents.dateOnly(e.start())) {
				if (end == null || !WebEvents.dateOnly(e.end()) || !end.isAfter(start)) {
					continue;
				}
				end = end.atZone(feed.zone()).plusDays(1).toInstant(); // through the end of the last day
			}
			String url = WebEvents.link(e.url(), pageUrl);
			if (url != null && !pageText.contains(url)) {
				url = null;
			}
			Location where = where(e, feed);
			String description = WebEvents.cut(WebEvents.text(e.description()), MAX_DESCRIPTION);
			events.add(new SourceEvent(WebEvents.key(url == null ? title : url, start.toString()), title, description,
					start, end != null && end.isAfter(start) ? end : null, where, feed.tags(),
					url == null ? pageUrl : url, e.cancelled()));
		}
		return events;
	}

	/**
	 * Another venue as the AI read it; a hall of the feed's own venue ("Muffatwerk – Ampere"); else the feed's venue
	 * (an address without a venue name is the feed's own).
	 */
	static Location where(EventExtractor.Extracted e, WebFeed feed) {
		Location own = feed.location();
		if (blankToNull(e.venue()) != null) {
			Location address = WebEvents.place(e.address(), null);
			String street = address == null ? null : address.street() != null ? address.street() : address.name();
			return WebEvents.place(blankToNull(e.venue()), street, address == null ? null : address.zipcode(),
					address == null ? null : address.city(), null, own);
		}
		String room = blankToNull(e.room());
		if (room == null || own == null) {
			return own;
		}
		String venue = own.name() != null ? own.name() : feed.name();
		String name = room.toLowerCase(Locale.ROOT).contains(venue.toLowerCase(Locale.ROOT)) ? room : venue + " – " + room;
		return new Location(WebEvents.cut(name, 120), own.street(), own.zipcode(), own.city(), own.country());
	}

	/** The page's visible text, links written as "text <url>", without scripts, menus and footers. */
	static String pageText(Document page) {
		Document copy = page.clone();
		copy.select("script, style, noscript, svg, iframe, template, nav, footer, form").remove();
		for (Element a : copy.select("a[href]")) {
			String href = a.absUrl("href");
			if (href.startsWith("http") && !a.text().isBlank()) {
				a.appendText(" <" + href + ">");
			}
		}
		// <main> when the programme is in it; some sites keep it elsewhere and <main> holds only a header
		Element main = copy.selectFirst("main");
		Element root = main != null && copy.body() != null
				&& main.text().length() * 10 >= copy.body().text().length() * 4 ? main : copy.body();
		String text = root == null ? null : WebEvents.text(root.html());
		return text == null ? null : WebEvents.cut(text, MAX_PAGE_TEXT);
	}

	private static String normalised(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}

	private static String cutChars(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
