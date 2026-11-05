package com.planmate.discovery.publish;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * The topics, created on start if they do not exist yet.
 *
 * discovery.events is compacted: Redpanda keeps the latest message per event id, so the topic always holds the
 * current state of every event, and a new consumer can rebuild everything from it.
 * discovery.events.dlq holds messages the backend could not process, for 30 days.
 */
@Configuration
public class TopicsConfig {

	@Bean
	NewTopic eventsTopic(DiscoveryProperties properties) {
		return TopicBuilder.name(properties.topics().events())
				.partitions(properties.topics().partitions())
				.replicas(1)
				.config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
				.build();
	}

	@Bean
	NewTopic deadLetterTopic(DiscoveryProperties properties) {
		return TopicBuilder.name(properties.topics().deadLetter())
				.partitions(1)
				.replicas(1)
				.config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
				.config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(30).toMillis()))
				.build();
	}
}
