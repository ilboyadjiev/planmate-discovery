package com.planmate.discovery.source;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A hand-written YAML file (curated-events.yml): Steam sales, game releases and other one-off events no API
 * provides. A file that cannot be read or has a mistake fails the whole run, so nothing is cancelled by accident.
 */
@Component
public class CuratedYamlSource implements EventSource {

	/** The file's shape. */
	record CuratedFile(List<Entry> events) {
	}

	record Entry(String id, String title, String description, String start, String end, Location location,
			List<String> tags, String url, Boolean cancelled) {
	}

	private static final YAMLMapper YAML = YAMLMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private final DiscoveryProperties.Curated config;
	private final ResourceLoader resources;

	public CuratedYamlSource(DiscoveryProperties properties, ResourceLoader resources) {
		this.config = properties.sources().curated();
		this.resources = resources;
	}

	@Override
	public String id() {
		return "curated";
	}

	@Override
	public String displayName() {
		return "PlanMate Picks";
	}

	@Override
	public String cron() {
		return config.cron();
	}

	@Override
	public boolean enabled() {
		return config.enabled();
	}

	@Override
	public List<SourceEvent> fetch() throws Exception {
		CuratedFile file;
		try (InputStream in = resources.getResource(config.file()).getInputStream()) {
			file = YAML.readValue(in, CuratedFile.class);
		}
		List<SourceEvent> events = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Entry e : file == null || file.events() == null ? List.<Entry>of() : file.events()) {
			if (e.id() == null || !e.id().matches("[a-z0-9][a-z0-9-]{1,80}")) {
				throw new IllegalArgumentException("Curated event id must be lower case letters, digits and dashes: " + e.id());
			}
			if (!ids.add(e.id())) {
				throw new IllegalArgumentException("Curated event id used twice: " + e.id());
			}
			if (e.title() == null || e.title().isBlank() || e.start() == null) {
				throw new IllegalArgumentException("Curated event " + e.id() + " needs a title and a start");
			}
			Instant start = OffsetDateTime.parse(e.start()).toInstant();
			Instant end = e.end() == null ? null : OffsetDateTime.parse(e.end()).toInstant();
			if (end != null && !end.isAfter(start)) {
				throw new IllegalArgumentException("Curated event " + e.id() + " ends before it starts");
			}
			events.add(new SourceEvent(e.id(), e.title().trim(), e.description() == null ? null : e.description().trim(),
					start, end, e.location(), e.tags(), e.url(), Boolean.TRUE.equals(e.cancelled())));
		}
		return events;
	}
}
