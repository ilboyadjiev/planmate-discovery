package com.planmate.discovery.source.web;

import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.pipeline.DiscoveryPipeline;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Runs every feed in web-feeds.yml, one after the other with a short pause (kind to the sites, and to the AI's rate
 * limit). The file is read again on every run, so feeds can be added or changed on the server without a restart.
 * A file with a mistake runs nothing and says why in the log.
 */
@Component
public class WebFeedRunner {

	private static final Logger log = LoggerFactory.getLogger(WebFeedRunner.class);

	record FeedFile(List<WebFeed> feeds) {
	}

	private static final YAMLMapper YAML = YAMLMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
			.build();

	private final DiscoveryProperties.Feeds config;
	private final ResourceLoader resources;
	private final DiscoveryPipeline pipeline;
	private final PageFetcher fetcher;
	private final PageEventReader ai;
	private final Clock clock;

	public WebFeedRunner(DiscoveryProperties properties, ResourceLoader resources, DiscoveryPipeline pipeline,
			PageFetcher fetcher, PageEventReader ai, Clock clock) {
		this.config = properties.sources().feeds();
		this.resources = resources;
		this.pipeline = pipeline;
		this.fetcher = fetcher;
		this.ai = ai;
		this.clock = clock;
	}

	public boolean enabled() {
		return config.enabled();
	}

	public String cron() {
		return config.cron();
	}

	public void runAll() {
		List<WebFeedSource> sources;
		try {
			sources = sources();
		} catch (Exception e) {
			log.error("Web feeds are not run: {} could not be read: {}", config.file(), e.getMessage());
			return;
		}
		boolean first = true;
		for (WebFeedSource source : sources) {
			if (!first) {
				try {
					Thread.sleep(config.pause().toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			first = false;
			pipeline.run(source);
		}
	}

	List<WebFeedSource> sources() throws Exception {
		FeedFile file;
		try (InputStream in = resources.getResource(config.file()).getInputStream()) {
			file = YAML.readValue(in, FeedFile.class);
		}
		List<WebFeedSource> sources = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (WebFeed feed : file == null || file.feeds() == null ? List.<WebFeed>of() : file.feeds()) {
			feed.validate();
			if (!ids.add(feed.id())) {
				throw new IllegalArgumentException("Web feed id used twice: " + feed.id());
			}
			sources.add(new WebFeedSource(feed, config, fetcher, ai, clock));
		}
		return sources;
	}
}
