package com.planmate.discovery.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GeminiClientTest {

	private static final String BASE = "https://ai.example/v1beta";
	private final JsonMapper json = JsonMapper.builder().build();
	private Instant now = Instant.parse("2026-11-10T12:00:00Z");
	private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

	private static DiscoveryProperties props(List<String> fallbacks, Duration cooldown) {
		return new DiscoveryProperties(Duration.ofHours(24), 3, null,
				new DiscoveryProperties.Ai(true, "AI-KEY", "model-a", fallbacks, cooldown, BASE, 15, 300,
						Duration.ZERO, List.of(), "classpath:prompts/event-description-refinement-prompt.txt"),
				null, null);
	}

	private static String answer(String text) {
		return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
	}

	private ObjectNode body() {
		return json.createObjectNode().put("hello", "world");
	}

	@Test
	void fallsBackToTheNextModelWhenOneIsRateLimited() {
		DiscoveryProperties props = props(List.of("model-b"), Duration.ofMinutes(15));
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(BASE + "/models/model-a:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(BASE + "/models/model-b:generateContent"))
				.andRespond(withSuccess(answer("from B"), MediaType.APPLICATION_JSON));

		GeminiClient client = new GeminiClient(props, new GeminiModels(props, clock), builder);

		assertThat(client.generate(body())).isEqualTo("from B");
		server.verify();
	}

	@Test
	void skipsARateLimitedModelOnTheNextCallUntilItsCooldownPasses() {
		DiscoveryProperties props = props(List.of("model-b"), Duration.ofMinutes(15));
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		// model-a is tried once, 429s, and is then skipped: the next call goes straight to model-b.
		server.expect(requestTo(BASE + "/models/model-a:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(BASE + "/models/model-b:generateContent"))
				.andRespond(withSuccess(answer("first"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/models/model-b:generateContent"))
				.andRespond(withSuccess(answer("second"), MediaType.APPLICATION_JSON));

		GeminiClient client = new GeminiClient(props, new GeminiModels(props, clock), builder);

		assertThat(client.generate(body())).isEqualTo("first");
		assertThat(client.generate(body())).isEqualTo("second");
		server.verify();
	}

	@Test
	void throwsTheTooManyRequestsWhenEveryModelIsRateLimited() {
		DiscoveryProperties props = props(List.of("model-b"), Duration.ofMinutes(15));
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(BASE + "/models/model-a:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(BASE + "/models/model-b:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		GeminiClient client = new GeminiClient(props, new GeminiModels(props, clock), builder);

		assertThatThrownBy(() -> client.generate(body()))
				.isInstanceOf(RestClientResponseException.class)
				.satisfies(e -> assertThat(((RestClientResponseException) e).getStatusCode().value()).isEqualTo(429));
		server.verify();
	}

	@Test
	void anOrdinaryErrorIsThrownStraightAwayWithoutFallingBack() {
		DiscoveryProperties props = props(List.of("model-b"), Duration.ofMinutes(15));
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		// A 400 (e.g. a bad key or model) is not a quota problem: no fallback, surfaced to the caller.
		server.expect(requestTo(BASE + "/models/model-a:generateContent")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

		GeminiClient client = new GeminiClient(props, new GeminiModels(props, clock), builder);

		assertThatThrownBy(() -> client.generate(body())).isInstanceOf(RestClientResponseException.class);
		server.verify();
	}
}
