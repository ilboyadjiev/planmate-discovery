package com.planmate.discovery.ai;

import java.util.List;
import java.util.Map;

/**
 * Writes short descriptions, tips and tags for a batch of events. Returns the valid results by event id; events
 * left out had an answer that broke the rules. Throws when the AI could not be asked at all (network, quota).
 */
public interface Refiner {

	Map<String, RefinedText> refine(List<RefinementInput> batch) throws Exception;

	/** One validated answer. */
	record RefinedText(String description, List<String> tips, List<String> tags) {
	}
}
