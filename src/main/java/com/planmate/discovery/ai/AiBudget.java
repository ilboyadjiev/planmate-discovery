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
 * At most dailyLimit events a day go to the AI (collection "ai_usage", one document per UTC day). Events are
 * counted before they are sent, so even a crash right after sending never goes over the limit.
 */
@Component
public class AiBudget {

	static final String COLLECTION = "ai_usage";

	private final MongoTemplate mongo;
	private final Clock clock;
	private final int dailyLimit;

	public AiBudget(MongoTemplate mongo, Clock clock, DiscoveryProperties properties) {
		this.mongo = mongo;
		this.clock = clock;
		this.dailyLimit = properties.ai().dailyLimit();
	}

	/** Reserves up to `wanted` events for today and returns how many may be sent (0 when the day is used up). */
	public int reserve(int wanted) {
		if (wanted <= 0) {
			return 0;
		}
		String day = today();
		mongo.upsert(Query.query(Criteria.where("_id").is(day)), new Update().setOnInsert("events", 0), COLLECTION);
		for (int n = wanted; n > 0; n = Math.min(n - 1, dailyLimit - used())) {
			Object updated = mongo.findAndModify(
					Query.query(Criteria.where("_id").is(day).and("events").lte(dailyLimit - n)),
					new Update().inc("events", n), FindAndModifyOptions.options().returnNew(true),
					org.bson.Document.class, COLLECTION);
			if (updated != null) {
				return n;
			}
		}
		return 0;
	}

	/** Events sent to the AI today. */
	public int used() {
		org.bson.Document doc = mongo.findById(today(), org.bson.Document.class, COLLECTION);
		return doc == null ? 0 : ((Number) doc.getOrDefault("events", 0)).intValue();
	}

	private String today() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
	}
}
