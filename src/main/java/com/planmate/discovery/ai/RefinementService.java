package com.planmate.discovery.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.planmate.discovery.config.DiscoveryProperties;
import com.planmate.discovery.model.Refinement;
import com.planmate.discovery.model.SourceEvent;
import com.planmate.discovery.model.TrackedEvent;
import com.planmate.discovery.pipeline.ContentHasher;

/**
 * Sends events to the AI in batches (15 by default), within the daily budget (300), pausing between requests for
 * the free tier's rate limit.
 *
 * Each event comes back with one outcome: a refinement; no refinement because the answer broke the rules (the
 * source text is used, and it is not asked again); or pending, because the AI could not be asked (no budget left
 * today, network or quota error): it is tried again on the next run.
 *
 * When Gemini is overloaded or rate-limited, a request is asked again after each of retryDelays (10s, 30s). If it
 * still fails, nothing else is sent in this run, and the events reserved for the failed request go back to the
 * day's budget.
 */
@Service
public class RefinementService {

	private static final Logger log = LoggerFactory.getLogger(RefinementService.class);
	static final int MAX_INPUT_DESCRIPTION = 1500;

	/** refinement: null when the source text has to do. pending: ask again on the next run. */
	public record Outcome(Refinement refinement, boolean pending) {
	}

	private final DiscoveryProperties.Ai config;
	private final Refiner refiner;
	private final AiBudget budget;
	private int calls;

	public RefinementService(DiscoveryProperties properties, Refiner refiner, AiBudget budget) {
		this.config = properties.ai();
		this.refiner = refiner;
		this.budget = budget;
	}

	public boolean available() {
		return config.enabled() && config.apiKey() != null && !config.apiKey().isBlank();
	}

	/** Requests sent to the AI since start (for tests and logs). */
	public int calls() {
		return calls;
	}

	public Map<String, Outcome> refine(List<TrackedEvent> events) throws InterruptedException {
		Map<String, Outcome> outcomes = new LinkedHashMap<>();
		if (events.isEmpty()) {
			return outcomes;
		}
		if (!available()) {
			events.forEach(e -> outcomes.put(e.getId(), new Outcome(null, false)));
			return outcomes;
		}
		boolean budgetLeft = true;
		boolean aiReachable = true;
		boolean first = true;
		for (int from = 0; from < events.size(); from += config.batchSize()) {
			List<TrackedEvent> batch = events.subList(from, Math.min(events.size(), from + config.batchSize()));
			if (!aiReachable) {
				batch.forEach(e -> outcomes.put(e.getId(), new Outcome(null, true)));
				continue;
			}
			int granted = budgetLeft ? budget.reserve(batch.size()) : 0;
			if (granted < batch.size()) {
				budgetLeft = false;
				log.info("AI budget for today is used up: {} events wait for tomorrow", events.size() - from - granted);
				batch.subList(granted, batch.size()).forEach(e -> outcomes.put(e.getId(), new Outcome(null, true)));
				batch = batch.subList(0, granted);
			}
			if (batch.isEmpty()) {
				continue;
			}
			if (!first) {
				Thread.sleep(config.pauseBetweenRequests().toMillis());
			}
			first = false;
			List<RefinementInput> inputs = new ArrayList<>();
			batch.forEach(e -> inputs.add(input(e)));
			try {
				Map<String, Refiner.RefinedText> answers = ask(inputs);
				for (TrackedEvent e : batch) {
					Refiner.RefinedText text = answers.get(e.getId());
					outcomes.put(e.getId(), new Outcome(text == null ? null : new Refinement(text.description(),
							text.tips(), text.tags(), ContentHasher.textHash(e.getEvent())), false));
				}
				int dropped = batch.size() - answers.size();
				if (dropped > 0) {
					log.warn("The AI's answer for {} of {} events broke the rules; they keep their source text", dropped, batch.size());
				}
			} catch (InterruptedException e) {
				budget.release(batch.size());
				throw e;
			} catch (Exception e) {
				// Not answered, so not counted; and no point asking again in this run
				budget.release(batch.size());
				aiReachable = false;
				log.warn("The AI could not be asked ({}); {} events are tried again on the next run",
						e.getMessage(), events.size() - from);
				batch.forEach(ev -> outcomes.put(ev.getId(), new Outcome(null, true)));
			}
		}
		return outcomes;
	}

	/** One request, asked again after each of retryDelays when Gemini is overloaded, rate-limited or unreachable. */
	private Map<String, Refiner.RefinedText> ask(List<RefinementInput> inputs) throws Exception {
		List<Duration> delays = config.retryDelays() == null ? List.of() : config.retryDelays();
		for (int attempt = 0;; attempt++) {
			try {
				calls++;
				return refiner.refine(inputs);
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				if (attempt >= delays.size() || !temporary(e)) {
					throw e;
				}
				log.info("The AI is busy ({}); asking again in {}s", status(e), delays.get(attempt).toSeconds());
				Thread.sleep(delays.get(attempt).toMillis());
			}
		}
	}

	/** 429, 5xx and network errors pass; a wrong key or model (other 4xx) or a broken answer does not. */
	static boolean temporary(Exception e) {
		if (e instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			return status == 429 || status >= 500;
		}
		return e instanceof ResourceAccessException;
	}

	private static String status(Exception e) {
		return e instanceof RestClientResponseException r ? String.valueOf(r.getStatusCode().value()) : e.getClass().getSimpleName();
	}

	private static RefinementInput input(TrackedEvent tracked) {
		SourceEvent e = tracked.getEvent();
		String description = e.description();
		if (description != null && description.length() > MAX_INPUT_DESCRIPTION) {
			description = description.substring(0, MAX_INPUT_DESCRIPTION);
		}
		return new RefinementInput(tracked.getId(), e.title(), description,
				e.location() == null ? null : e.location().name(), e.location() == null ? null : e.location().city(),
				e.tags());
	}
}
