package com.planmate.discovery.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Checks the AI's answer against the prompt's rules before anything of it is used: one object per event (matched
 * by id), a description of at most 220 characters without emoji, 0 to 2 tips of at most 60 characters, and 2 to 5
 * tags from the allowed list. An answer that breaks a rule is dropped, and that event keeps its source text.
 */
public final class RefinementValidator {

	static final int MAX_DESCRIPTION = 220;
	static final int MAX_TIPS = 2;
	static final int MAX_TIP = 60;
	static final int MIN_TAGS = 2;
	static final int MAX_TAGS = 5;
	private static final Pattern EMOJI = Pattern.compile("[\\p{IsExtended_Pictographic}\\x{FE0F}]");

	private RefinementValidator() {
	}

	/** The valid answers by id, for the ids that were asked about. */
	public static Map<String, Refiner.RefinedText> validate(JsonNode answer, List<RefinementInput> asked, Set<String> allowedTags) {
		Map<String, Refiner.RefinedText> valid = new LinkedHashMap<>();
		if (answer == null || !answer.isArray()) {
			return valid;
		}
		Set<String> askedIds = new LinkedHashSet<>();
		asked.forEach(a -> askedIds.add(a.id()));
		for (JsonNode item : answer) {
			String id = string(item.get("id"));
			if (id == null || !askedIds.contains(id) || valid.containsKey(id)) {
				continue;
			}
			Refiner.RefinedText text = check(item, allowedTags);
			if (text != null) {
				valid.put(id, text);
			}
		}
		return valid;
	}

	static Refiner.RefinedText check(JsonNode item, Set<String> allowedTags) {
		String description = string(item.get("description"));
		if (description == null || description.length() > MAX_DESCRIPTION || EMOJI.matcher(description).find()) {
			return null;
		}
		JsonNode tipsNode = item.get("tips");
		List<String> tips = new ArrayList<>();
		if (tipsNode != null && !tipsNode.isNull()) {
			if (!tipsNode.isArray() || tipsNode.size() > MAX_TIPS) {
				return null;
			}
			for (JsonNode tip : tipsNode) {
				String text = string(tip);
				if (text == null || text.length() > MAX_TIP || EMOJI.matcher(text).find()) {
					return null;
				}
				tips.add(text);
			}
		}
		JsonNode tagsNode = item.get("tags");
		if (tagsNode == null || !tagsNode.isArray()) {
			return null;
		}
		Set<String> tags = new LinkedHashSet<>();
		for (JsonNode tag : tagsNode) {
			String text = string(tag);
			if (text == null || !allowedTags.contains(text.toLowerCase())) {
				return null;
			}
			tags.add(text.toLowerCase());
		}
		if (tags.size() < MIN_TAGS || tags.size() > MAX_TAGS) {
			return null;
		}
		return new Refiner.RefinedText(description, tips, new ArrayList<>(tags));
	}

	private static String string(JsonNode node) {
		if (node == null || !node.isString()) {
			return null;
		}
		String text = node.asString().trim();
		return text.isEmpty() ? null : text;
	}
}
