package com.planmate.discovery.source.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.planmate.discovery.config.DiscoveryProperties;

/**
 * Downloads feed pages politely: as PlanMateBot (with a link explaining who it is), only where the site's
 * robots.txt allows it, and at most maxPageBytes per page. robots.txt is read again after a day. Redirects are
 * followed (many sites send /events on to /de/events), except from https to http.
 */
@Component
public class PageFetcher {

	static final Duration ROBOTS_TTL = Duration.ofHours(24);
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	/** A downloaded page or file; text() decodes it with the charset the server named (else UTF-8). */
	public record Page(String url, MediaType contentType, byte[] body) {
		public String text() {
			Charset charset = contentType == null || contentType.getCharset() == null ? StandardCharsets.UTF_8
					: contentType.getCharset();
			return new String(body, charset);
		}
	}

	private record CachedRobots(RobotsRules rules, Instant readAt) {
	}

	private final DiscoveryProperties.Feeds config;
	private final RestClient http;
	private final Clock clock;
	private final Map<String, CachedRobots> robots = new ConcurrentHashMap<>();

	public PageFetcher(DiscoveryProperties properties, RestClient.Builder builder, Clock clock) {
		this.config = properties.sources().feeds();
		this.clock = clock;
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(CONNECT_TIMEOUT)
				.build());
		requests.setReadTimeout(READ_TIMEOUT);
		this.http = builder.clone()
				.requestFactory(requests)
				.defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
				.defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en, de;q=0.8, bg;q=0.7, *;q=0.5")
				.build();
	}

	/** The page at url; throws when robots.txt forbids it, the server answers with an error, or it is too big. */
	public Page get(String url) throws IOException {
		return get(url, false);
	}

	/**
	 * The page at url. When ignoreRobots is true the robots.txt check is skipped (use only for a site the operator
	 * has separately allowed); otherwise this throws when robots.txt forbids the page.
	 */
	public Page get(String url, boolean ignoreRobots) throws IOException {
		URI uri = URI.create(url);
		if (!ignoreRobots && !rulesFor(uri).allows(pathOf(uri))) {
			throw new IOException("robots.txt of " + uri.getHost() + " does not allow PlanMateBot to read " + url);
		}
		return http.get().uri(uri)
				.header(HttpHeaders.ACCEPT, "text/calendar, text/html, application/xhtml+xml, */*;q=0.8")
				.exchange((request, response) -> {
					if (!response.getStatusCode().is2xxSuccessful()) {
						throw new IOException(url + " answered " + response.getStatusCode().value());
					}
					return new Page(url, response.getHeaders().getContentType(), limited(response.getBody(), url));
				});
	}

	private RobotsRules rulesFor(URI uri) throws IOException {
		String origin = uri.getScheme() + "://" + uri.getRawAuthority();
		CachedRobots cached = robots.get(origin);
		Instant now = clock.instant();
		if (cached != null && cached.readAt().plus(ROBOTS_TTL).isAfter(now)) {
			return cached.rules();
		}
		RobotsRules rules = http.get().uri(URI.create(origin + "/robots.txt"))
				.header(HttpHeaders.ACCEPT, "text/plain")
				.exchange((request, response) -> {
					int status = response.getStatusCode().value();
					if (status >= 500) {
						// The site has trouble: try again on the next run rather than guess
						throw new IOException("robots.txt of " + uri.getHost() + " answered " + status);
					}
					if (status >= 400) {
						return RobotsRules.ALLOW_ALL; // no robots.txt: nothing is forbidden
					}
					return RobotsRules.parse(new String(limited(response.getBody(), origin + "/robots.txt"),
							StandardCharsets.UTF_8), "PlanMateBot");
				});
		robots.put(origin, new CachedRobots(rules, now));
		return rules;
	}

	private byte[] limited(InputStream in, String url) throws IOException {
		byte[] body = in.readNBytes(config.maxPageBytes() + 1);
		if (body.length > config.maxPageBytes()) {
			throw new IOException(url + " is larger than " + config.maxPageBytes() + " bytes");
		}
		return body;
	}

	private static String pathOf(URI uri) {
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
	}
}
