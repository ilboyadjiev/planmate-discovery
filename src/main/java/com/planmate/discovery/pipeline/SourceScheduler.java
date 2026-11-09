package com.planmate.discovery.pipeline;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.planmate.discovery.source.EventSource;
import com.planmate.discovery.source.google.GoogleCalendarRunner;
import com.planmate.discovery.source.web.WebFeedRunner;

/**
 * Runs every enabled source on its own cron schedule from application.yml, and once shortly after start, so a new
 * deployment does not wait hours for its first data. The web feeds (web-feeds.yml) share one schedule and run one
 * after the other.
 */
@Component
public class SourceScheduler implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(SourceScheduler.class);

	private final List<EventSource> sources;
	private final DiscoveryPipeline pipeline;
	private final WebFeedRunner feeds;
	private final GoogleCalendarRunner googleCalendars;
	private final boolean runOnStartup;

	public SourceScheduler(List<EventSource> sources, DiscoveryPipeline pipeline, WebFeedRunner feeds,
			GoogleCalendarRunner googleCalendars, @Value("${discovery.run-on-startup:true}") boolean runOnStartup) {
		this.sources = sources;
		this.pipeline = pipeline;
		this.feeds = feeds;
		this.googleCalendars = googleCalendars;
		this.runOnStartup = runOnStartup;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		for (EventSource source : sources) {
			if (!source.enabled()) {
				log.info("Source {} is off", source.id());
				continue;
			}
			log.info("Source {} runs on '{}'", source.id(), source.cron());
			registrar.addTriggerTask(() -> pipeline.run(source), new CronTrigger(source.cron()));
		}
		if (feeds.enabled()) {
			log.info("Web feeds run on '{}'", feeds.cron());
			registrar.addTriggerTask(feeds::runAll, new CronTrigger(feeds.cron()));
		} else {
			log.info("Web feeds are off");
		}
		if (googleCalendars.enabled()) {
			log.info("Google calendars run on '{}'", googleCalendars.cron());
			registrar.addTriggerTask(googleCalendars::runAll, new CronTrigger(googleCalendars.cron()));
		} else {
			log.info("Google calendars are off");
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void runOnceAtStart() {
		if (!runOnStartup) {
			return;
		}
		Thread.ofVirtual().name("discovery-startup-run").start(() -> {
			sources.stream().filter(EventSource::enabled).forEach(pipeline::run);
			if (feeds.enabled()) {
				feeds.runAll();
			}
			if (googleCalendars.enabled()) {
				googleCalendars.runAll();
			}
		});
	}
}
