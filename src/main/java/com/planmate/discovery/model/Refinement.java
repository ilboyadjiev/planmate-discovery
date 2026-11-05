package com.planmate.discovery.model;

import java.util.List;

/**
 * What the AI wrote for an event: a short description, practical tips and tags from the allowed list.
 * forTextHash: the title and description it was written for, so it is reused until those change.
 */
public record Refinement(String description, List<String> tips, List<String> tags, String forTextHash) {

	public Refinement {
		tips = tips == null ? List.of() : List.copyOf(tips);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** The description with the tips under it ("Tip: ..."), as the app shows it. */
	public String descriptionWithTips() {
		if (tips.isEmpty()) {
			return description;
		}
		StringBuilder text = new StringBuilder(description).append("\n");
		tips.forEach(tip -> text.append("\nTip: ").append(tip));
		return text.toString();
	}
}
