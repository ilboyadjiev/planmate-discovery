package com.planmate.discovery.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.planmate.discovery.ai.RefinementService;
import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.ChangeType;
import com.planmate.discovery.model.EventStatus;
import com.planmate.discovery.model.OutboxMessage;
import com.planmate.discovery.model.Refinement;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.model.SourceRun;
import com.planmate.discovery.model.TrackedEvent;
import com.planmate.discovery.publish.EventMessage;
import com.planmate.discovery.publish.OutboxRepository;
import com.planmate.discovery.source.EventSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * One run of one source:
 *
 * 1. Fetch everything it lists. A failed fetch ends the run: nothing changes and nothing counts as cancelled.
 * 2. Classify each event by its content hash: NEW, CHANGED, UNCHANGED, or CANCELLED (the source says so, or it
 *    has been missing from successful fetches for staleAfter, 24h, before it happened). Events that are over are
 *    set aside quietly.
 * 3. Send only new events and events whose title or description changed to the AI (plus ones still waiting for it).
 * 4. Save a message for every new, changed or cancelled event to the outbox, then its new state. OutboxPublisher
 *    sends the messages to discovery.events.
 *
 * Unchanged data therefore publishes nothing and asks the AI nothing.
 */
@Service
public class DiscoveryPipeline {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryPipeline.class);
	static final int MAX_FALLBACK_DESCRIPTION = 600;

	private final TrackedEventRepository events;
	private final OutboxRepository outbox;
	private final SourceRunRepository runs;
	private final RefinementService refinement;
	private final JsonMapper json;
	private final Clock clock;
	private final DiscoveryProperties properties;
	private final Set<String> running = ConcurrentHashMap.newKeySet();

	public DiscoveryPipeline(TrackedEventRepository events, OutboxRepository outbox, SourceRunRepository runs,
			RefinementService refinement, JsonMapper json, Clock clock, DiscoveryProperties properties) {
		this.events = events;
		this.outbox = outbox;
		this.runs = runs;
		this.refinement = refinement;
		this.json = json;
		this.clock = clock;
		this.properties = properties;
	}

	/** Runs the source unless a run of it is still going on. Returns the run's summary. */
	public SourceRun run(EventSource source) {
		if (!running.add(source.id())) {
			log.info("{} is still running; this run is skipped", source.id());
			return null;
		}
		try {
			return doRun(source);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			running.remove(source.id());
		}
	}

	private SourceRun doRun(EventSource source) throws InterruptedException {
		Instant now = clock.instant();
		SourceRun run = new SourceRun(source.id(), now);
		List<SourceEvent> fetched;
		try {
			fetched = source.fetch();
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			run.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
			run.setFinishedAt(clock.instant());
			runs.save(run);
			log.warn("{} could not be fetched: {}", source.id(), run.getError());
			return run;
		}
		run.setFetched(fetched.size());

		Map<String, TrackedEvent> known = events.findBySource(source.id()).stream()
				.collect(Collectors.toMap(TrackedEvent::getId, e -> e));
		Map<String, ChangeType> changes = new LinkedHashMap<>();
		Map<String, TrackedEvent> touched = new LinkedHashMap<>();

		for (SourceEvent event : fetched) {
			String id = source.eventId(event.sourceId());
			if (touched.containsKey(id)) {
				continue; // listed twice
			}
			TrackedEvent tracked = known.get(id);
			boolean over = event.endsAt() == null || !event.endsAt().isAfter(now);
			if (tracked == null) {
				if (over || event.cancelledAtSource()) {
					continue; // nothing to tell anyone about
				}
				tracked = new TrackedEvent(id, source.id(), event.sourceId());
				tracked.setFirstSeenAt(now);
				changes.put(id, ChangeType.NEW);
			} else if (over) {
				if (tracked.getStatus() == EventStatus.ACTIVE) {
					tracked.setStatus(EventStatus.EXPIRED);
					touched.put(id, tracked);
				}
				continue;
			} else {
				changes.put(id, classify(tracked, event));
			}
			tracked.setLastSeenAt(now);
			tracked.setMissingSince(null);
			tracked.setEvent(event);
			touched.put(id, tracked);
		}

		// Listed before, not any more
		for (TrackedEvent tracked : known.values()) {
			if (touched.containsKey(tracked.getId()) || tracked.getStatus() != EventStatus.ACTIVE) {
				continue;
			}
			Instant endsAt = tracked.getEvent() == null ? null : tracked.getEvent().endsAt();
			if (endsAt == null || !endsAt.isAfter(now)) {
				tracked.setStatus(EventStatus.EXPIRED);
			} else if (tracked.getMissingSince() == null) {
				tracked.setMissingSince(now);
			} else if (!tracked.getMissingSince().plus(properties.staleAfter()).isAfter(now)) {
				changes.put(tracked.getId(), ChangeType.CANCELLED);
			} else {
				continue;
			}
			touched.put(tracked.getId(), tracked);
		}

		// The AI: new events, events whose text changed, and events still waiting for it
		List<TrackedEvent> toRefine = new ArrayList<>();
		for (TrackedEvent tracked : touched.values()) {
			ChangeType change = changes.get(tracked.getId());
			if (change == null || change == ChangeType.CANCELLED) {
				continue;
			}
			// textHash on the tracked event is the text last published, so a date or place change alone never asks
			boolean textChanged = change == ChangeType.CHANGED
					&& !ContentHasher.textHash(tracked.getEvent()).equals(tracked.getTextHash());
			if (change == ChangeType.NEW || textChanged || tracked.isRefinementPending()) {
				toRefine.add(tracked);
			}
		}
		Map<String, RefinementService.Outcome> outcomes = refinement.refine(toRefine);
		for (TrackedEvent tracked : toRefine) {
			RefinementService.Outcome outcome = outcomes.get(tracked.getId());
			if (outcome == null) {
				continue;
			}
			boolean wasPending = tracked.isRefinementPending();
			tracked.setRefinementPending(outcome.pending());
			if (outcome.refinement() != null) {
				tracked.setRefinement(outcome.refinement());
				run.setRefined(run.getRefined() + 1);
				// Finally refined while nothing else changed: the better text is news for the app
				if (wasPending && changes.get(tracked.getId()) == ChangeType.UNCHANGED) {
					changes.put(tracked.getId(), ChangeType.CHANGED);
				}
			} else if (!outcome.pending()) {
				tracked.setRefinement(null); // the source text is used
			}
		}

		// Messages first, then the state they report (a crash in between repeats the same message id, never loses it)
		for (TrackedEvent tracked : touched.values()) {
			ChangeType change = changes.get(tracked.getId());
			if (change == null) {
				continue;
			}
			switch (change) {
			case NEW -> run.setCreated(run.getCreated() + 1);
			case CHANGED -> run.setChanged(run.getChanged() + 1);
			case UNCHANGED -> run.setUnchanged(run.getUnchanged() + 1);
			case CANCELLED -> run.setCancelled(run.getCancelled() + 1);
			}
			if (change == ChangeType.UNCHANGED) {
				continue;
			}
			tracked.setVersion(tracked.getVersion() + 1);
			tracked.setStatus(change == ChangeType.CANCELLED ? EventStatus.CANCELLED : EventStatus.ACTIVE);
			tracked.setContentHash(ContentHasher.contentHash(tracked.getEvent()));
			tracked.setTextHash(ContentHasher.textHash(tracked.getEvent()));
			tracked.setLastChangedAt(now);
			EventMessage message = message(source, tracked, change, now);
			outbox.save(new OutboxMessage(tracked.getId(), message.type(), message.version(),
					json.writeValueAsString(message), now));
		}
		events.saveAll(touched.values());

		run.setSuccess(true);
		run.setFinishedAt(clock.instant());
		runs.save(run);
		log.info("Run {}", run);
		return run;
	}

	/** How a fetched event compares with what was known about it. */
	static ChangeType classify(TrackedEvent tracked, SourceEvent event) {
		if (event.cancelledAtSource()) {
			return tracked.getStatus() == EventStatus.CANCELLED ? ChangeType.UNCHANGED : ChangeType.CANCELLED;
		}
		if (tracked.getStatus() != EventStatus.ACTIVE) {
			return ChangeType.CHANGED; // back again: cancelled or over before, listed and upcoming now
		}
		return ContentHasher.contentHash(event).equals(tracked.getContentHash()) ? ChangeType.UNCHANGED : ChangeType.CHANGED;
	}

	private EventMessage message(EventSource source, TrackedEvent tracked, ChangeType change, Instant now) {
		String type = switch (change) {
		case NEW -> EventMessage.DISCOVERED;
		case CANCELLED -> EventMessage.CANCELLED;
		default -> EventMessage.UPDATED;
		};
		SourceEvent e = tracked.getEvent();
		Refinement refined = tracked.getRefinement();
		boolean current = refined != null && refined.forTextHash().equals(ContentHasher.textHash(e));
		String description = current ? refined.descriptionWithTips() : fallbackDescription(e.description());
		List<String> tags = current ? refined.tags() : normalisedTags(e.tags());
		return new EventMessage(type, tracked.getId(), tracked.getVersion(), now,
				new EventMessage.Event(tracked.getId(), e.title(), description, e.startTime(), e.endTime(), e.location(),
						tags, source.id(), source.displayName(), e.url(), change == ChangeType.CANCELLED));
	}

	/** The source's own text when the AI did not write one: tidied up and cut at a word near 600 characters. */
	static String fallbackDescription(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String tidy = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
		if (tidy.length() <= MAX_FALLBACK_DESCRIPTION) {
			return tidy;
		}
		int cut = tidy.lastIndexOf(' ', MAX_FALLBACK_DESCRIPTION);
		return tidy.substring(0, cut > 0 ? cut : MAX_FALLBACK_DESCRIPTION).trim() + "…";
	}

	static List<String> normalisedTags(List<String> tags) {
		return tags.stream().map(t -> t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", ""))
				.filter(t -> t.length() > 1).map(t -> t.startsWith("#") ? t : "#" + t).distinct().toList();
	}
}
