package com.planmate.discovery.pipeline;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.planmate.discovery.model.SourceRun;

public interface SourceRunRepository extends MongoRepository<SourceRun, String> {
}
