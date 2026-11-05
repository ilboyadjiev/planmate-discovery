package com.planmate.discovery.publish;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.OutboxMessage;

/**
 * Sends waiting outbox messages to discovery.events, keyed by event id, oldest first, and marks each one published
 * once Redpanda confirmed it. If Redpanda is unreachable, the messages wait and the next round tries again, in the
 * same order, so nothing is lost or reordered.
 */
@Component
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
	static final int MAX_ERROR = 500;

	private final OutboxRepository outbox;
	private final KafkaTemplate<String, String> kafka;
	private final DiscoveryProperties properties;
	private final Clock clock;

	public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, DiscoveryProperties properties,
			Clock clock) {
		this.outbox = outbox;
		this.kafka = kafka;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${discovery.outbox.publish-interval:5s}", initialDelayString = "${discovery.outbox.initial-delay:10s}")
	public void publishScheduled() {
		publishWaiting();
	}

	/** Sends what is waiting; returns how many messages were published. */
	public synchronized int publishWaiting() {
		int published = 0;
		List<OutboxMessage> waiting;
		do {
			waiting = outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, properties.outbox().batchSize()));
			for (OutboxMessage message : waiting) {
				try {
					kafka.send(properties.topics().events(), message.getEventId(), message.getPayload())
							.get(30, TimeUnit.SECONDS);
					message.setPublishedAt(clock.instant());
					message.setLastError(null);
					published++;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return published;
				} catch (Exception e) {
					message.setAttempts(message.getAttempts() + 1);
					String error = e.getCause() != null ? e.getCause().toString() : e.toString();
					message.setLastError(error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error);
					outbox.save(message);
					log.warn("Could not publish {} ({} attempts): {}", message.getId(), message.getAttempts(), message.getLastError());
					return published; // keep the order: later messages wait for this one
				}
				outbox.save(message);
			}
		} while (waiting.size() == properties.outbox().batchSize());
		if (published > 0) {
			log.info("Published {} messages to {}", published, properties.topics().events());
		}
		return published;
	}
}
