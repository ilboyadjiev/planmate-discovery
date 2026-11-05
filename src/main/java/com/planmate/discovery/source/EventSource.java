package com.planmate.discovery.source;

import java.util.List;

import com.planmate.discovery.model.SourceEvent;

/**
 * One public source of events (one adapter class per source). Each runs on its own schedule from application.yml.
 *
 * fetch() returns everything upcoming the source lists right now, or throws if it could not be read completely:
 * a failed fetch never makes events count as cancelled.
 */
public interface EventSource {

	/** Short, stable id, also the start of every event id ("tm" gives "tm-G5v0Z9..."). */
	String id();

	/** How the backend credits it under each event: "Source: Ticketmaster". */
	String displayName();

	/** Spring cron expression from application.yml. */
	String cron();

	boolean enabled();

	List<SourceEvent> fetch() throws Exception;

	/** The id an event of this source is stored and published under. */
	default String eventId(String sourceId) {
		return id() + "-" + sourceId;
	}
}
