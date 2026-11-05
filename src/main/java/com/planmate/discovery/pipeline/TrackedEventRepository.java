package com.planmate.discovery.pipeline;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.planmate.discovery.model.TrackedEvent;

public interface TrackedEventRepository extends MongoRepository<TrackedEvent, String> {

	List<TrackedEvent> findBySource(String source);
}
