package com.planmate.discovery.source.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.source.EventSource;

/**
 * One web feed as a source of its own ("web-muffatwerk"), so one broken site never affects another's events.
 *
 * format auto: an iCalendar file is read as such; a web page by its schema.org event data; and a page with neither
 * by the AI, when aiFallback is on. A page where nothing can be read fails the run rather than return no events, so
 * a redesign of the site never makes its events count as cancelled.
 *
 * Kept: events from a day ago up to lookAhead ahead, at most maxEventsPerFeed, the soonest first.
 */
public class WebFeedSource implements EventSource {

	private final WebFeed feed;
	private final DiscoveryProperties.Feeds config;
	private final PageFetcher fetcher;
	private final PageEventReader ai;
	private final Clock clock;

	public WebFeedSource(WebFeed feed, DiscoveryProperties.Feeds config, PageFetcher fetcher, PageEventReader ai,
			Clock clock) {
		this.feed = feed;
		this.config = config;
		this.fetcher = fetcher;
		this.ai = ai;
		this.clock = clock;
	}

	@Override
	public String id() {
		return "web-" + feed.id();
	}

	@Override
	public String displayName() {
		return feed.name().trim();
	}

	@Override
	public String cron() {
		return config.cron();
	}

	@Override
	public boolean enabled() {
		return config.enabled();
	}

	@Override
	public List<SourceEvent> fetch() throws Exception {
		Instant now = clock.instant();
		PageFetcher.Page page = fetcher.get(feed.url());
		List<SourceEvent> events = read(page, now);
		Instant from = now.minusSeconds(24 * 3600);
		Instant to = now.plus(config.lookAhead());
		return events.stream()
				.filter(e -> e.endsAt().isAfter(from) && e.startTime().isBefore(to))
				.sorted(Comparator.comparing(SourceEvent::startTime))
				.limit(Math.max(1, config.maxEventsPerFeed()))
				.toList();
	}

	private List<SourceEvent> read(PageFetcher.Page page, Instant now) throws Exception {
		WebFeed.Format format = feed.formatValue();
		boolean calendar = page.contentType() != null && "calendar".equalsIgnoreCase(page.contentType().getSubtype());
		if (format == WebFeed.Format.ICS || (format == WebFeed.Format.AUTO && (calendar || IcsParser.looksLikeIcs(page.text())))) {
			return IcsParser.parse(page.text(), feed);
		}
		Document html = html(page);
		if (format == WebFeed.Format.AI) {
			return ai.read(feed, html, now);
		}
		List<SourceEvent> structured = JsonLdParser.parse(html, feed);
		if (!structured.isEmpty() || format == WebFeed.Format.JSON_LD) {
			if (structured.isEmpty()) {
				throw new IOException("no schema.org event data on " + feed.url());
			}
			return structured;
		}
		if (!config.aiFallback()) {
			throw new IOException("no calendar or schema.org event data on " + feed.url()
					+ " (turn on ai-fallback, or set format: ai for this feed)");
		}
		return ai.read(feed, html, now);
	}

	/** Parsed with the charset the server or the page names (Jsoup checks both), else UTF-8. */
	private static Document html(PageFetcher.Page page) throws IOException {
		String charset = page.contentType() == null || page.contentType().getCharset() == null ? null
				: page.contentType().getCharset().name();
		return Jsoup.parse(new ByteArrayInputStream(page.body()), charset, page.url());
	}
}
