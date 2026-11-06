package com.planmate.discovery.ai;

import java.util.List;

/**
 * Reads the events listed in a web page's text (Gemini in production, a fake in tests). Its answers are only
 * suggestions: PageEventReader checks every one against the page.
 */
public interface EventExtractor {

	/** One page: who publishes it, where it is, the local date there, its time zone and its visible text. */
	record Input(String pageUrl, String source, String today, String timeZone, String page) {
	}

	/**
	 * One event as the AI read it; empty strings for what the page does not say. room: a hall or stage of the
	 * publisher's own venue; venue: another venue.
	 */
	record Extracted(String title, String start, String end, String room, String venue, String address, String url,
			String description, boolean cancelled) {
	}

	List<Extracted> extract(Input input) throws Exception;
}
