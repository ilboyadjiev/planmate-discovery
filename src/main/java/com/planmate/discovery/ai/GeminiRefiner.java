package com.planmate.discovery.ai;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini (generateContent REST API) on discovery's own key. The prompt goes in as the system instruction, the
 * events as a JSON array, and the answer is requested as JSON in a fixed shape; RefinementValidator then checks it.
 */
@Component
public class GeminiRefiner implements Refiner {

	private final DiscoveryProperties.Ai config;
	private final PromptTemplate prompt;
	private final JsonMapper json;
	private final RestClient http;

	public GeminiRefiner(DiscoveryProperties properties, PromptTemplate prompt, JsonMapper json, RestClient.Builder builder) {
		this.config = properties.ai();
		this.prompt = prompt;
		this.json = json;
		// Timeouts: spring.http.clients.* in application.yml
		this.http = builder.clone().baseUrl(config.baseUrl()).build();
	}

	@Override
	public Map<String, RefinedText> refine(List<RefinementInput> batch) {
		JsonNode response = http.post()
				.uri("/models/{model}:generateContent", config.model())
				.header("x-goog-api-key", config.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request(batch))
				.retrieve()
				.body(JsonNode.class);
		String text = response == null ? null
				: response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString(null);
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Gemini returned no text");
		}
		return RefinementValidator.validate(json.readTree(text), batch, prompt.allowedTags());
	}

	ObjectNode request(List<RefinementInput> batch) {
		ObjectNode body = json.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", prompt.text());
		ObjectNode user = body.putArray("contents").addObject();
		user.put("role", "user");
		user.putArray("parts").addObject().put("text", json.writeValueAsString(batch));
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("responseMimeType", "application/json");
		generation.put("temperature", 0.3);
		generation.set("responseSchema", schema());
		return body;
	}

	/** [{ id, description, tips[], tags[] }] */
	private ObjectNode schema() {
		ObjectNode item = json.createObjectNode().put("type", "OBJECT");
		ObjectNode props = item.putObject("properties");
		props.putObject("id").put("type", "STRING");
		props.putObject("description").put("type", "STRING");
		props.putObject("tips").put("type", "ARRAY").putObject("items").put("type", "STRING");
		props.putObject("tags").put("type", "ARRAY").putObject("items").put("type", "STRING");
		ArrayNode required = item.putArray("required");
		required.add("id").add("description").add("tips").add("tags");
		ObjectNode schema = json.createObjectNode().put("type", "ARRAY");
		schema.set("items", item);
		return schema;
	}
}
