package com.planmate.discovery.ai;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * At most dailyLimit events a day go to the AI for descriptions, and at most aiPageReadsPerDay web pages are read
 * by it (collection "ai_usage", one document per UTC day). Descriptions are reserved before sending (so a crash
 * right after sending never goes over the limit). Page reads are counted only after the AI answers: a failed or
 * skipped read costs nothing, and a crash before the answer can never leak a reservation and wedge the budget.
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

	/** Reserves up to `wanted` events for today and returns how many may be sent (0 when the day is used up). */
	public int reserve(int wanted) {
		return reserve("events", wanted, dailyLimit);
	}

	/** Gives back events reserved today that were never answered (the request failed). */
	public void release(int count) {
		release("events", count);
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

	private int reserve(String counter, int wanted, int limit) {
		if (wanted <= 0) {
			return 0;
		}
		String day = today();
		mongo.upsert(Query.query(Criteria.where("_id").is(day)), new Update().setOnInsert(counter, 0), COLLECTION);
		for (int n = Math.min(wanted, limit - used(counter)); n > 0; n = Math.min(n - 1, limit - used(counter))) {
			Object updated = mongo.findAndModify(
					Query.query(Criteria.where("_id").is(day).and(counter).lte(limit - n)),
					new Update().inc(counter, n), FindAndModifyOptions.options().returnNew(true),
					org.bson.Document.class, COLLECTION);
			if (updated != null) {
				return n;
			}
		}
		return 0;
	}

	private void release(String counter, int count) {
		if (count <= 0) {
			return;
		}
		mongo.findAndModify(Query.query(Criteria.where("_id").is(today()).and(counter).gte(count)),
				new Update().inc(counter, -count), org.bson.Document.class, COLLECTION);
	}

	private int used(String counter) {
		org.bson.Document doc = mongo.findById(today(), org.bson.Document.class, COLLECTION);
		return doc == null ? 0 : ((Number) doc.getOrDefault(counter, 0)).intValue();
	}

	private String today() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
	}
}
