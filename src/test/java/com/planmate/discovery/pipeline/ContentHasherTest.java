package com.planmate.discovery.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

class ContentHasherTest {

	private static SourceEvent event(String title, String description, String start, List<String> tags) {
		return new SourceEvent("1", title, description, Instant.parse(start), null, new Location("Club", null, null, "Munich", null),
				tags, "https://x", false);
	}

	@Test
	void sameContentSameHashWhateverTheTagOrder() {
		assertThat(ContentHasher.contentHash(event("A", "d", "2026-12-01T18:00:00Z", List.of("#a", "#b"))))
				.isEqualTo(ContentHasher.contentHash(event("A", "d", "2026-12-01T18:00:00Z", List.of("#b", "#a"))));
	}

	@Test
	void aNewTimeChangesTheContentButNotTheText() {
		SourceEvent before = event("A", "d", "2026-12-01T18:00:00Z", List.of());
		SourceEvent moved = event("A", "d", "2026-12-01T19:00:00Z", List.of());
		assertThat(ContentHasher.contentHash(moved)).isNotEqualTo(ContentHasher.contentHash(before));
		assertThat(ContentHasher.textHash(moved)).isEqualTo(ContentHasher.textHash(before));
		assertThat(ContentHasher.textHash(event("A", "new text", "2026-12-01T18:00:00Z", List.of())))
				.isNotEqualTo(ContentHasher.textHash(before));
	}

	@Test
	void missingAndEmptyAreToldApart() {
		assertThat(ContentHasher.textHash(event("A", null, "2026-12-01T18:00:00Z", List.of())))
				.isNotEqualTo(ContentHasher.textHash(event("A", "", "2026-12-01T18:00:00Z", List.of())));
	}
}
