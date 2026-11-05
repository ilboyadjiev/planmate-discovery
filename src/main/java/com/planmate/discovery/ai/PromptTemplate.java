package com.planmate.discovery.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * The instructions for the AI (event-description-refinement-prompt.txt) and the tags it may use, read from the
 * prompt's "Allowed tags:" line, so the prompt and the validation can never disagree.
 */
@Component
public class PromptTemplate {

	private static final String ALLOWED = "Allowed tags:";

	private final String text;
	private final Set<String> allowedTags;

	public PromptTemplate(DiscoveryProperties properties, ResourceLoader resources) throws IOException {
		try (InputStream in = resources.getResource(properties.ai().prompt()).getInputStream()) {
			this.text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		this.allowedTags = parseAllowedTags(text);
	}

	static Set<String> parseAllowedTags(String prompt) {
		String line = prompt.lines().filter(l -> l.startsWith(ALLOWED)).findFirst()
				.orElseThrow(() -> new IllegalStateException("The prompt has no \"" + ALLOWED + "\" line"));
		Set<String> tags = Arrays.stream(line.substring(ALLOWED.length()).split(","))
				.map(String::trim).filter(t -> t.startsWith("#")).collect(Collectors.toCollection(LinkedHashSet::new));
		if (tags.isEmpty()) {
			throw new IllegalStateException("The prompt lists no allowed tags");
		}
		return tags;
	}

	public String text() {
		return text;
	}

	public Set<String> allowedTags() {
		return allowedTags;
	}
}
