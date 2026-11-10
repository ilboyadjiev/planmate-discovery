package com.planmate.discovery.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * The ordered Gemini model chain: the primary model first, then the fallbacks. When a model answers 429 (its quota
 * for the moment is used up) it is put on a cooldown and skipped; requests use the next model still free, and the
 * model comes back by itself once the cooldown passes. So a per-minute limit is ridden out in seconds and a daily
 * limit falls back for the rest of the day, without ever abandoning the preferred model for good.
 */
@Component
public class GeminiModels {

	private static final Logger log = LoggerFactory.getLogger(GeminiModels.class);

	private final List<String> chain;
	private final Duration cooldown;
	private final Clock clock;
	private final ConcurrentHashMap<String, Instant> coolingUntil = new ConcurrentHashMap<>();

	public GeminiModels(DiscoveryProperties properties, Clock clock) {
		this.chain = chain(properties.ai());
		this.cooldown = properties.ai().modelCooldown();
		this.clock = clock;
	}

	/** The primary model, then each distinct non-blank fallback, in order. */
	static List<String> chain(DiscoveryProperties.Ai ai) {
		List<String> chain = new ArrayList<>();
		add(chain, ai.model());
		if (ai.fallbackModels() != null) {
			ai.fallbackModels().forEach(model -> add(chain, model));
		}
		if (chain.isEmpty()) {
			throw new IllegalStateException("discovery.ai.model must be set");
		}
		return List.copyOf(chain);
	}

	private static void add(List<String> chain, String model) {
		if (model != null && !model.isBlank() && !chain.contains(model.trim())) {
			chain.add(model.trim());
		}
	}

	/** The models to try, in order, that are not cooling down; the primary alone when every model is cooling down. */
	List<String> available() {
		Instant now = clock.instant();
		List<String> free = new ArrayList<>();
		for (String model : chain) {
			Instant until = coolingUntil.get(model);
			if (until == null || !until.isAfter(now)) {
				free.add(model);
			}
		}
		return free.isEmpty() ? List.of(chain.get(0)) : free;
	}

	/** Marks a model rate-limited, so it is skipped until its cooldown passes (no-op when the cooldown is off). */
	void rateLimited(String model) {
		if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
			return; // fallback disabled: keep using the model and let the retry delays ride it out
		}
		Instant now = clock.instant();
		Instant previous = coolingUntil.put(model, now.plus(cooldown));
		boolean wasFree = previous == null || !previous.isAfter(now);
		if (wasFree) {
			String next = available().get(0);
			if (next.equals(model)) {
				log.warn("Gemini model {} is rate-limited and no fallback is free; it is retried after the delays", model);
			} else {
				log.warn("Gemini model {} is rate-limited; falling back to {} for up to {}", model, next, cooldown);
			}
		}
	}

	List<String> chain() {
		return chain;
	}
}
