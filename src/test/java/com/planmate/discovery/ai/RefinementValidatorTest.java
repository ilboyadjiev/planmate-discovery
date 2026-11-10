package com.planmate.discovery.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RefinementValidatorTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> ALLOWED = Set.of("#concert", "#culture", "#munich", "#free", "#food");
	private static final List<RefinementInput> ASKED = List.of(
			new RefinementInput("a", "A", null, null, null, List.of()),
			new RefinementInput("b", "B", null, null, null, List.of()));

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	@Test
	void theAllowedTagsComeFromThePromptItself() throws Exception {
		String prompt = new ClassPathResource("prompts/event-description-refinement-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
		Set<String> tags = PromptTemplate.parseAllowedTags(prompt);
		// Don't hardcode the catalogue size (it grows as tags are added): count the #tags on the prompt's
		// "Allowed tags:" line independently and assert the parser returned exactly those, well-formed.
		long onPromptLine = prompt.lines().filter(l -> l.startsWith("Allowed tags:"))
				.flatMap(l -> java.util.Arrays.stream(l.substring("Allowed tags:".length()).split(",")))
				.map(String::trim).filter(t -> t.startsWith("#")).distinct().count();
		assertThat(tags).contains("#munich", "#christmasmarket", "#tradition")
				.hasSize((int) onPromptLine).allMatch(t -> t.matches("#[a-z]+"));
	}

	@Test
	void keepsValidAnswersMatchedById() {
		Map<String, Refiner.RefinedText> valid = RefinementValidator.validate(json("""
				[{"id":"b","description":"Live jazz in a cellar club.","tips":["Book ahead"],"tags":["#concert","#Culture"]},
				 {"id":"a","description":"A free food market.","tips":[],"tags":["#food","#free","#munich"]}]
				"""), ASKED, ALLOWED);
		assertThat(valid).containsOnlyKeys("a", "b");
		assertThat(valid.get("b").tags()).containsExactly("#concert", "#culture");
		assertThat(valid.get("b").tips()).containsExactly("Book ahead");
	}

	@Test
	void dropsAnswersThatBreakTheRules() {
		String tooLong = "x".repeat(221);
		assertThat(RefinementValidator.validate(json("""
				[{"id":"a","description":"%s","tips":[],"tags":["#food","#free"]},
				 {"id":"b","description":"Fine.","tips":[],"tags":["#food"]}]
				""".formatted(tooLong)), ASKED, ALLOWED)).isEmpty();
		assertThat(RefinementValidator.validate(json("""
				[{"id":"a","description":"Party time 🎉","tips":[],"tags":["#food","#free"]},
				 {"id":"b","description":"Fine.","tips":["a","b","c"],"tags":["#food","#free"]}]
				"""), ASKED, ALLOWED)).isEmpty();
		assertThat(RefinementValidator.validate(json("""
				[{"id":"a","description":"Fine.","tips":[],"tags":["#food","#invented"]},
				 {"id":"zzz","description":"Not asked.","tips":[],"tags":["#food","#free"]}]
				"""), ASKED, ALLOWED)).isEmpty();
		assertThat(RefinementValidator.validate(json("""
				[{"id":"a","description":"Fine.","tips":["%s"],"tags":["#food","#free"]}]
				""".formatted("t".repeat(61))), ASKED, ALLOWED)).isEmpty();
		assertThat(RefinementValidator.validate(json("{\"not\":\"an array\"}"), ASKED, ALLOWED)).isEmpty();
	}
}
