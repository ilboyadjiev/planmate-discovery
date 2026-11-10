package com.planmate.discovery.ai;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * At most dailyLimit events a day go to the AI for descriptions, and at most aiPageReadsPerDay web pages are read
 * by it (collection "ai_usage", one document per UTC day). Both are counted only after the AI answers, with $inc
 * (which treats a missing field as 0): a failed or skipped request costs nothing, a crash before the answer can
 * never leak and wedge the budget, and the day's doc never needs an up-front "events: 0" to be reservable.
 */
@Component
public class AiBudget {

	static final String COLLECTION = "ai_usage";

	private final MongoTemplate mongo;
	private final Clock clock;
	private final int dailyLimit;
	private final int pageReadLimit;

	public AiBudget(MongoTemplate mongo, Clock clock, DiscoveryProperties properties) {
		this.mongo = mongo;
		this.clock = clock;
		this.dailyLimit = properties.ai().dailyLimit();
		this.pageReadLimit = properties.sources() == null || properties.sources().feeds() == null ? 0
				: properties.sources().feeds().aiPageReadsPerDay();
	}

	/** Events still allowed today (0 when the day is used up). Checked before sending; counted after with recordEvents. */
	public int remainingEvents() {
		return Math.max(0, dailyLimit - used("events"));
	}

	/** Counts events actually sent to the AI today (call only after the AI answered). */
	public void recordEvents(int count) {
		if (count <= 0) {
			return;
		}
		mongo.upsert(Query.query(Criteria.where("_id").is(today())), new Update().inc("events", count), COLLECTION);
	}

	/** Events sent to the AI today. */
	public int used() {
		return used("events");
	}

	/** True while today still has room for a page read. Checked before the read; the read is then counted, only if
	 *  it succeeds, with recordPageRead — so nothing is reserved up front and a failed read can never leak budget. */
	public boolean canReadPage() {
		return pageReadLimit > 0 && used("pages") < pageReadLimit;
	}

	/** Counts one page actually read by the AI today (call only after the AI answered). */
	public void recordPageRead() {
		mongo.upsert(Query.query(Criteria.where("_id").is(today())), new Update().inc("pages", 1), COLLECTION);
	}

	/** Web pages read by the AI today. */
	public int pageReadsUsed() {
		return used("pages");
	}

	private int used(String counter) {
		org.bson.Document doc = mongo.findById(today(), org.bson.Document.class, COLLECTION);
		return doc == null ? 0 : ((Number) doc.getOrDefault(counter, 0)).intValue();
	}

	private String today() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
	}
}
