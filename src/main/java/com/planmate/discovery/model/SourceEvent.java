package com.planmate.discovery.model;

import java.time.Instant;
import java.util.List;

/**
 * One event as an adapter found it, already in PlanMate's terms (not yet refined by the AI).
 *
 * sourceId: the source's own id for it, stable between fetches. url: its page at the source.
 * cancelledAtSource: the source itself says the event is cancelled.
 */
public record SourceEvent(
		String sourceId,
		String title,
		String description,
		Instant startTime,
		Instant endTime,
		Location location,
		List<String> tags,
		String url,
		boolean cancelledAtSource) {

	public SourceEvent {
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** When it is over: its end, or three hours after its start when the source gives no end. */
	public Instant endsAt() {
		if (endTime != null) {
			return endTime;
		}
		return startTime == null ? null : startTime.plusSeconds(3 * 3600);
	}
}
