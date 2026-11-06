package com.planmate.discovery.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Gemini reading a page's events (prompts/event-extraction-prompt.txt), answering as JSON in a fixed shape. */
@Component
public class GeminiEventExtractor implements EventExtractor {

	static final String PROMPT = "classpath:prompts/event-extraction-prompt.txt";
	private static final String[] FIELDS = { "title", "start", "end", "room", "venue", "address", "url", "description" };

	private final DiscoveryProperties.Ai config;
	private final JsonMapper json;
	private final RestClient http;
	private final String prompt;

	public GeminiEventExtractor(DiscoveryProperties properties, JsonMapper json, RestClient.Builder builder,
			ResourceLoader resources) throws IOException {
		this.config = properties.ai();
		this.json = json;
		this.http = builder.clone().baseUrl(config.baseUrl()).build();
		try (InputStream in = resources.getResource(PROMPT).getInputStream()) {
			this.prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}

	@Override
	public List<Extracted> extract(Input input) {
		JsonNode response = http.post()
				.uri("/models/{model}:generateContent", config.model())
				.header("x-goog-api-key", config.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request(input))
				.retrieve()
				.body(JsonNode.class);
		String text = response == null ? null
				: response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString(null);
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Gemini returned no text");
		}
		JsonNode answer = json.readTree(text);
		if (!answer.isArray()) {
			throw new IllegalStateException("Gemini did not answer with a list");
		}
		List<Extracted> events = new ArrayList<>();
		for (JsonNode e : answer) {
			events.add(new Extracted(e.path("title").asString(""), e.path("start").asString(""),
					e.path("end").asString(""), e.path("room").asString(""), e.path("venue").asString(""),
					e.path("address").asString(""), e.path("url").asString(""), e.path("description").asString(""),
					e.path("cancelled").asBoolean(false)));
		}
		return events;
	}

	ObjectNode request(Input input) {
		ObjectNode body = json.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", prompt);
		ObjectNode user = body.putArray("contents").addObject();
		user.put("role", "user");
		user.putArray("parts").addObject().put("text", json.writeValueAsString(input));
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("responseMimeType", "application/json");
		generation.put("temperature", 0.0);
		ObjectNode item = json.createObjectNode().put("type", "OBJECT");
		ObjectNode props = item.putObject("properties");
		ArrayNode required = item.putArray("required");
		for (String field : FIELDS) {
			props.putObject(field).put("type", "STRING");
			required.add(field);
		}
		props.putObject("cancelled").put("type", "BOOLEAN");
		required.add("cancelled");
		ObjectNode schema = generation.putObject("responseSchema").put("type", "ARRAY");
		schema.set("items", item);
		return body;
	}
}
