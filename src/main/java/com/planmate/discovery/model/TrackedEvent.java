package com.planmate.discovery.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What discovery knows about one event (collection "events").
 *
 * id: the id the backend stores it under, e.g. "tm-G5v0Z9..." (source prefix + source id).
 * contentHash: everything the source said about it; any difference makes it CHANGED. textHash: only its title and
 * description; only a difference there sends it to the AI again. version: grows with every message published for
 * it, so the backend can ignore older ones.
 */
@Document("events")
public class TrackedEvent {

	@Id
	private String id;
	@Indexed
	private String source;
	private String sourceId;
	private EventStatus status;
	private String contentHash;
	private String textHash;
	private long version;
	private Instant firstSeenAt;
	@Indexed
	private Instant lastSeenAt;
	private Instant lastChangedAt;
	/**
	 * The first successful fetch of its source that no longer listed it; null while it is listed. It counts as
	 * cancelled once that was staleAfter (24h) ago, so a source that was down for a while never cancels anything.
	 */
	private Instant missingSince;
	private SourceEvent event;
	private Refinement refinement;
	/** The AI could not be reached for it: try again on the next run, then publish the better text. */
	private boolean refinementPending;

	public TrackedEvent() {
	}

	public TrackedEvent(String id, String source, String sourceId) {
		this.id = id;
		this.source = source;
		this.sourceId = sourceId;
	}

	public String getId() { return id; }
	public String getSource() { return source; }
	public String getSourceId() { return sourceId; }
	public EventStatus getStatus() { return status; }
	public void setStatus(EventStatus status) { this.status = status; }
	public String getContentHash() { return contentHash; }
	public void setContentHash(String contentHash) { this.contentHash = contentHash; }
	public String getTextHash() { return textHash; }
	public void setTextHash(String textHash) { this.textHash = textHash; }
	public long getVersion() { return version; }
	public void setVersion(long version) { this.version = version; }
	public Instant getFirstSeenAt() { return firstSeenAt; }
	public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
	public Instant getLastSeenAt() { return lastSeenAt; }
	public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
	public Instant getLastChangedAt() { return lastChangedAt; }
	public void setLastChangedAt(Instant lastChangedAt) { this.lastChangedAt = lastChangedAt; }
	public Instant getMissingSince() { return missingSince; }
	public void setMissingSince(Instant missingSince) { this.missingSince = missingSince; }
	public SourceEvent getEvent() { return event; }
	public void setEvent(SourceEvent event) { this.event = event; }
	public Refinement getRefinement() { return refinement; }
	public void setRefinement(Refinement refinement) { this.refinement = refinement; }
	public boolean isRefinementPending() { return refinementPending; }
	public void setRefinementPending(boolean refinementPending) { this.refinementPending = refinementPending; }
}
