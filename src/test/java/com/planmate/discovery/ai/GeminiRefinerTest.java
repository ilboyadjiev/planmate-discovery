package com.planmate.discovery.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;

import tools.jackson.databind.json.JsonMapper;

class GeminiRefinerTest {

	private static final DiscoveryProperties PROPS = new DiscoveryProperties(Duration.ofHours(24), 3, null,
			new DiscoveryProperties.Ai(true, "AI-KEY", "gemini-test-model", "https://ai.example/v1beta", 15, 300,
					Duration.ZERO, List.of(), "classpath:prompts/event-description-refinement-prompt.txt"),
			null, null);

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void sendsThePromptAndTheEventsAndKeepsTheValidAnswers() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		String answer = "[{\\\"id\\\":\\\"tm-1\\\",\\\"description\\\":\\\"Jazz in a cellar club.\\\",\\\"tips\\\":[],\\\"tags\\\":[\\\"#concert\\\",\\\"#munich\\\"]}]";
		server.expect(requestTo("https://ai.example/v1beta/models/gemini-test-model:generateContent"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("x-goog-api-key", "AI-KEY"))
				.andExpect(jsonPath("$.systemInstruction.parts[0].text").value(org.hamcrest.Matchers.startsWith("You write short event blurbs")))
				.andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.containsString("\"id\":\"tm-1\"")))
				.andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
				.andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + answer + "\"}]}}]}",
						MediaType.APPLICATION_JSON));

		GeminiRefiner refiner = new GeminiRefiner(PROPS, new PromptTemplate(PROPS, new DefaultResourceLoader()), json, builder);
		Map<String, Refiner.RefinedText> result = refiner.refine(List.of(
				new RefinementInput("tm-1", "Jazz Night", "Live jazz", "Unterfahrt", "Munich", List.of("#concert"))));

		server.verify();
		assertThat(result.get("tm-1").description()).isEqualTo("Jazz in a cellar club.");
	}

	@Test
	void quotaErrorsAreReportedSoTheEventsAreTriedAgain() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://ai.example"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		GeminiRefiner refiner = new GeminiRefiner(PROPS, new PromptTemplate(PROPS, new DefaultResourceLoader()), json, builder);
		assertThatThrownBy(() -> refiner.refine(List.of(new RefinementInput("a", "A", null, null, null, List.of()))))
				.isInstanceOf(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class);
	}
}
