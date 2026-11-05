package com.planmate.discovery.publish;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.planmate.discovery.model.OutboxMessage;

public interface OutboxRepository extends MongoRepository<OutboxMessage, String> {

	/** Waiting messages, oldest first, so each event's messages go out in order. */
	List<OutboxMessage> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable page);

	long countByPublishedAtIsNull();
}
