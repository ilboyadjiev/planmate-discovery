package com.planmate.discovery.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A message waiting to be (or already) published to discovery.events (collection "outbox"), saved together with the
 * state change it reports, so a crash between saving and sending never loses it.
 *
 * id: "{eventId}:{version}", so writing the same state change twice keeps one message.
 * payload: the message exactly as it is sent (JSON). publishedAt: null until Redpanda confirmed it.
 */
@Document("outbox")
public class OutboxMessage {

	@Id
	private String id;
	private String eventId;
	private String type;
	private long version;
	private String payload;
	@Indexed
	private Instant createdAt;
	@Indexed(sparse = true)
	private Instant publishedAt;
	private int attempts;
	private String lastError;

	public OutboxMessage() {
	}

	public OutboxMessage(String eventId, String type, long version, String payload, Instant createdAt) {
		this.id = eventId + ":" + version;
		this.eventId = eventId;
		this.type = type;
		this.version = version;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public String getId() { return id; }
	public String getEventId() { return eventId; }
	public String getType() { return type; }
	public long getVersion() { return version; }
	public String getPayload() { return payload; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getPublishedAt() { return publishedAt; }
	public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
	public int getAttempts() { return attempts; }
	public void setAttempts(int attempts) { this.attempts = attempts; }
	public String getLastError() { return lastError; }
	public void setLastError(String lastError) { this.lastError = lastError; }
}
