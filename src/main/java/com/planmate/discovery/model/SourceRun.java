package com.planmate.discovery.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One run of a source (collection "source_runs"): when, whether the fetch worked, and what came of it. */
@Document("source_runs")
public class SourceRun {

	@Id
	private String id;
	private String source;
	private Instant startedAt;
	private Instant finishedAt;
	private boolean success;
	private String error;
	private int fetched;
	private int created;
	private int changed;
	private int unchanged;
	private int cancelled;
	private int refined;

	public SourceRun() {
	}

	public SourceRun(String source, Instant startedAt) {
		this.source = source;
		this.startedAt = startedAt;
	}

	public String getId() { return id; }
	public String getSource() { return source; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getFinishedAt() { return finishedAt; }
	public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
	public boolean isSuccess() { return success; }
	public void setSuccess(boolean success) { this.success = success; }
	public String getError() { return error; }
	public void setError(String error) { this.error = error; }
	public int getFetched() { return fetched; }
	public void setFetched(int fetched) { this.fetched = fetched; }
	public int getCreated() { return created; }
	public void setCreated(int created) { this.created = created; }
	public int getChanged() { return changed; }
	public void setChanged(int changed) { this.changed = changed; }
	public int getUnchanged() { return unchanged; }
	public void setUnchanged(int unchanged) { this.unchanged = unchanged; }
	public int getCancelled() { return cancelled; }
	public void setCancelled(int cancelled) { this.cancelled = cancelled; }
	public int getRefined() { return refined; }
	public void setRefined(int refined) { this.refined = refined; }

	@Override
	public String toString() {
		return source + ": " + (success ? "ok" : "failed (" + error + ")") + ", fetched " + fetched + ", new " + created
				+ ", changed " + changed + ", unchanged " + unchanged + ", cancelled " + cancelled + ", refined " + refined;
	}
}
