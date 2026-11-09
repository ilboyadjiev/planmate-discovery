package com.planmate.discovery.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.SourceEvent;

class CuratedYamlSourceTest {

	private static CuratedYamlSource source(String file) {
		DiscoveryProperties props = new DiscoveryProperties(Duration.ofHours(24), 3,
				new DiscoveryProperties.Sources(null, new DiscoveryProperties.Curated(true, "0 0 3 * * *", file), null, null, null), null, null, null);
		return new CuratedYamlSource(props, new DefaultResourceLoader());
	}

	@Test
	void readsTheCuratedFile() throws Exception {
		List<SourceEvent> events = source("classpath:curated/curated-test.yml").fetch();
		assertThat(events).hasSize(2);
		SourceEvent sale = events.get(0);
		assertThat(sale.sourceId()).isEqualTo("steam-winter-sale-2026");
		assertThat(sale.startTime()).isEqualTo(Instant.parse("2026-12-18T18:00:00Z"));
		assertThat(sale.endTime()).isEqualTo(Instant.parse("2027-01-02T18:00:00Z"));
		assertThat(sale.location().name()).isEqualTo("Online (Steam)");
		assertThat(sale.tags()).containsExactly("#gaming", "#sale");
		assertThat(events.get(1).cancelledAtSource()).isTrue();
	}

	@Test
	void theShippedFileIsValid() throws Exception {
		assertThat(source("classpath:curated/curated-events.yml").fetch()).isNotNull();
	}

	@Test
	void mistakesFailTheRunInsteadOfCancellingEvents(@TempDir Path dir) throws Exception {
		Path twice = dir.resolve("twice.yml");
		Files.writeString(twice, "events:\n  - {id: a1, title: A, start: '2026-12-01T10:00:00+01:00'}\n"
				+ "  - {id: a1, title: B, start: '2026-12-02T10:00:00+01:00'}\n");
		assertThatThrownBy(() -> source("file:" + twice).fetch()).hasMessageContaining("used twice");

		Path noStart = dir.resolve("nostart.yml");
		Files.writeString(noStart, "events:\n  - {id: a1, title: A}\n");
		assertThatThrownBy(() -> source("file:" + noStart).fetch()).hasMessageContaining("needs a title and a start");

		assertThatThrownBy(() -> source("file:" + dir.resolve("missing.yml")).fetch()).isInstanceOf(java.io.IOException.class);
	}
}
