package com.planmate.discovery.ai;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One place that talks to Gemini's generateContent REST API. It picks the model from the chain (GeminiModels) and,
 * when a model answers 429, moves down to the next model still free and asks again; the 429 is only thrown on when
 * every available model is rate-limited. Other errors (a wrong key, a bad model id, 5xx, network) are thrown as-is,
 * for the caller's retry/pending handling.
 */
@Component
public class GeminiClient {

	private final DiscoveryProperties.Ai config;
	private final GeminiModels models;
	private final RestClient http;

	public GeminiClient(DiscoveryProperties properties, GeminiModels models, RestClient.Builder builder) {
		this.config = properties.ai();
		this.models = models;
		// Timeouts: spring.http.clients.* in application.yml
		this.http = builder.clone().baseUrl(config.baseUrl()).build();
	}

	/** Sends the request body, falling back down the model chain on 429, and returns the answer's text. */
	public String generate(ObjectNode body) {
		RestClientResponseException rateLimited = null;
		for (String model : models.available()) {
			try {
				return text(call(model, body));
			} catch (RestClientResponseException e) {
				if (e.getStatusCode().value() != 429) {
					throw e;
				}
				models.rateLimited(model);
				rateLimited = e;
			}
		}
		throw rateLimited; // every available model is rate-limited; surfaced as a 429 for the caller to retry
	}

	private JsonNode call(String model, ObjectNode body) {
		return http.post()
				.uri("/models/{model}:generateContent", model)
				.header("x-goog-api-key", config.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(JsonNode.class);
	}

	private static String text(JsonNode response) {
		String text = response == null ? null
				: response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString(null);
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Gemini returned no text");
		}
		return text;
	}
}
