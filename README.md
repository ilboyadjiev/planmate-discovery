# PlanMate Discovery Service

Finds upcoming public events in public sources, keeps track of them, lets Gemini write short descriptions for new
and changed ones, and publishes every new, changed or cancelled event to Redpanda. The PlanMate backend consumes the
topic and shows the events in the app, hosted by "PlanMate Picks".

Spring Boot 4.1, Java 21. Setup on the server: `backend/docs/DISCOVERY_SETUP.md`.

## How a run works

Each source is one adapter (`source/`) with its own cron schedule in `application.yml`:

| Source | Adapter | Schedule |
|---|---|---|
| Ticketmaster Discovery API (Munich) | `TicketmasterSource` | every 2 hours |
| Cinema releases in Germany and Bulgaria (TMDB) | `TmdbSource` | daily, 04:15 |
| Hand-picked events (Steam sales, releases, one-offs) | `CuratedYamlSource` | daily, 03:30 |
| Venue and city calendars (`web-feeds.yml`) | `source/web/WebFeedSource`, one source per feed | 05:45 and 17:45 |

### Cinema releases (TMDB)

The 25 most popular films opening in cinemas in each region within 60 days (`discovery.sources.tmdb.regions`:
Germany in Munich, Bulgaria in Sofia), with titles and texts in the region's language. Each is an all-day event
on its release day there (a film often opens on different days in the two countries), at "Cinemas in <city>", so
it shows in that city's "Around me" and "For you". Ids are `tmdb-<region>-<TMDB id>`. TMDB's terms ask for credit:
events say "Source: TMDB", link to the film's TMDB page, and the Impressum carries TMDB's notice.

### Web feeds

`web-feeds.yml` lists programme pages of venues and cities (Munich and Sofia so far). Each feed is a source of its
own (`web-<id>`), so a broken site never affects another's events. Per page, in this order (`format: auto`):

1. **iCalendar** (`.ics`): read directly (`IcsParser`).
2. **schema.org event data** in the page (`<script type="application/ld+json">`, what sites publish for search
   engines): read directly (`JsonLdParser`).
3. **Otherwise Gemini reads the page's visible text** (`PageEventReader`, prompt `event-extraction-prompt.txt`):
   only when the text changed since the last read (`web_feed_extractions`), at most 40 pages a day
   (`ai-page-reads-per-day`, its own limit). Every event it returns is checked: its title must appear on the page,
   its start must be readable, and a link is kept only if the page has it.

Rules for all feeds: `PageFetcher` identifies itself as `PlanMateBot/1.0 (+https://www.planmate.org)`, follows
each site's `robots.txt`, and reads at most 3 MB per page. Events without a time on a single day are left out (a
concert at "00:00" would be wrong); dated runs such as exhibitions are kept. A page where nothing can be read fails
the run instead of returning no events, so a redesigned site never cancels its events.

A run (`DiscoveryPipeline`):

1. **Fetch** everything the source lists. A failed fetch ends the run without changing anything.
2. **Classify** each event by a content hash: new, changed, unchanged, or cancelled. Cancelled means the source says
   so, or the event has been missing from 24 hours of successful fetches before it happened. Events that are over
   are set aside quietly.
   Of a show listed once per date (same title, same venue), only the next 3 dates are taken in
   (`discovery.max-per-series`); later dates follow as earlier ones pass.
3. **Refine**: only new events and events whose title or description changed go to Gemini, 15 per request and at
   most 300 a day, with the prompt in `src/main/resources/prompts/`. Answers are validated against the prompt's
   rules; invalid ones fall back to the source's text. When Gemini is overloaded or rate-limited (429, 5xx) a
   request is asked again after 10s and 30s; if it still fails, the run sends nothing more, the failed request does
   not count against the day's budget, and the source text is published and refined on a later run.
4. **Outbox**: every new, changed or cancelled event is saved to the `outbox` collection together with its state
   change. `OutboxPublisher` sends it to `discovery.events`, keyed by event id, and marks it published once Redpanda
   confirmed it.

A run on unchanged data publishes nothing and makes no AI call.

## Messages on `discovery.events`

Compacted topic, keyed by event id (`tm-<ticketmaster id>`, `curated-<id from the file>`):

```json
{
  "type": "EventDiscovered",
  "eventId": "tm-G5vYZ9Kf1AbC",
  "version": 1,
  "occurredAt": "2026-11-05T10:00:00Z",
  "event": {
    "id": "tm-G5vYZ9Kf1AbC",
    "title": "Jazz Night",
    "description": "Live jazz in a cellar club.\n\nTip: Book ahead",
    "startTime": "2026-11-20T18:00:00Z",
    "endTime": null,
    "location": { "name": "Jazzclub Unterfahrt", "street": "Einsteinstraße 42", "zipcode": "81675", "city": "München", "country": "Germany" },
    "tags": ["#concert", "#munich"],
    "source": "tm",
    "sourceName": "Ticketmaster",
    "sourceUrl": "https://www.ticketmaster.de/event/...",
    "cancelled": false
  }
}
```

`type` is `EventDiscovered`, `EventUpdated` or `EventCancelled`; `version` grows with every message for an event.
The backend skips older versions, adds `Source: <sourceName>` to the description, and sends what it cannot process
to `discovery.events.dlq`.

## State (database `planmate_discovery`)

| Collection | What |
|---|---|
| `events` | Every event seen: hashes, version, status, last refinement |
| `outbox` | Messages, published or waiting |
| `source_runs` | One document per run: success, counts, errors |
| `ai_usage` | Events sent to Gemini and pages it read, per day |
| `web_feed_extractions` | Per AI-read feed: its page text's hash and the events read from it |

## Configuration

`src/main/resources/application.yml`; on the server through environment variables:

| Variable | What |
|---|---|
| `DISCOVERY_MONGODB_URI` | MongoDB, database `planmate_discovery` |
| `KAFKA_BOOTSTRAP_SERVERS` | Redpanda, e.g. `redpanda:9092` |
| `TICKETMASTER_API_KEY` | Ticketmaster key; empty keeps Ticketmaster off |
| `TMDB_API_KEY` | TMDB "API Key" or "API Read Access Token"; empty keeps TMDB off |
| `GEMINI_DISCOVERY_API_KEY` | Gemini key (discovery's own); empty publishes source texts |
| `GEMINI_DISCOVERY_MODEL` | Gemini model id |
| `CURATED_EVENTS_FILE` | `file:/config/curated-events.yml` on the server |
| `WEB_FEEDS_FILE` | `file:/config/web-feeds.yml` on the server |
| `WEB_FEEDS_ENABLED` | `false` turns the web feeds off |

## Adding a source

A venue or city calendar needs no code: add it to `web-feeds.yml`. For an API or another kind of source:

1. Write a class in `source/` implementing `EventSource`: an `id()` (the event id prefix), a `displayName()` (shown
   as "Source: ..."), its `cron()` and `fetch()`. `fetch()` must throw if it could not read everything.
2. Add its settings to `DiscoveryProperties` and `application.yml`.
3. Add a test with a recorded response, like `TicketmasterSourceTest`.

The scheduler picks up every `EventSource` bean automatically.

## Running and testing locally

```bash
./mvnw test                 # needs Docker (MongoDB and Redpanda start through Testcontainers)
./mvnw spring-boot:run      # needs MongoDB on localhost:27017 and Redpanda on localhost:9092
```
# planmate-discovery
