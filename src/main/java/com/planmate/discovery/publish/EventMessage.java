package com.planmate.discovery.publish;

import java.time.Instant;
import java.util.List;

import com.planmate.discovery.model.Location;

/**
 * A message on discovery.events, keyed by eventId (the topic is compacted, so the latest message per event stays).
 *
 * type: EventDiscovered, EventUpdated or EventCancelled. version: grows with every message for the event; the
 * backend ignores a message older than what it already has. event: always the full event, also when cancelled.
 */
public record EventMessage(String type, String eventId, long version, Instant occurredAt, Event event) {

	public static final String DISCOVERED = "EventDiscovered";
	public static final String UPDATED = "EventUpdated";
	public static final String CANCELLED = "EventCancelled";

	/**
	 * The event in the backend's terms. description: already refined, tips included; the backend adds the
	 * "Source: ..." line. source: the adapter id ("ticketmaster"); sourceName: how to credit it ("Ticketmaster").
	 */
	public record Event(
			String id,
			String title,
			String description,
			Instant startTime,
			Instant endTime,
			Location location,
			List<String> tags,
			String source,
			String sourceName,
			String sourceUrl,
			String imageUrl,
			boolean cancelled) {
	}
}
