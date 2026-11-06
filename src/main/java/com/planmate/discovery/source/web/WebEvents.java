package com.planmate.discovery.source.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

import com.planmate.discovery.model.Location;

/** What the web feed readers share: ids, times, places, links and text from web pages. */
final class WebEvents {

	static final int MAX_TITLE = 200;
	static final int MAX_DESCRIPTION = 2000;
	private static final Pattern ZIP_CITY = Pattern.compile("^(\\d{4,5})\\s+(\\D.*)$");
	private static final Pattern OFFSET_WITHOUT_COLON = Pattern.compile("([+-]\\d{2})(\\d{2})$");

	private WebEvents() {
	}

	/** A short id that stays the same for the same key ("uid@venue.de", or link and start). */
	static String key(String... parts) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 10);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * A date or time as web pages write it: with an offset ("2026-11-14T20:00:00+01:00", "...+0100", "...Z"), or
	 * local ("2026-11-14T20:00", "2026-11-14 20:00:00"), taken in the feed's zone. A date alone is the start of that
	 * day. Null when it cannot be read.
	 */
	static Instant instant(String text, ZoneId zone) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim().replaceFirst("^(\\d{4}-\\d{2}-\\d{2}) (\\d)", "$1T$2");
		t = OFFSET_WITHOUT_COLON.matcher(t).replaceFirst("$1:$2");
		try {
			return OffsetDateTime.parse(t).toInstant();
		} catch (DateTimeParseException e) {
			// not with an offset
		}
		try {
			return ZonedDateTime.parse(t).toInstant();
		} catch (DateTimeParseException e) {
			// not with a zone
		}
		try {
			return LocalDateTime.parse(t).atZone(zone).toInstant();
		} catch (DateTimeParseException e) {
			// not a local time
		}
		try {
			return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t).atStartOfDay(zone).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** "2026-11-14" (a date without a time). */
	static boolean dateOnly(String text) {
		return text != null && text.trim().matches("\\d{4}-\\d{2}-\\d{2}");
	}

	/**
	 * A place written as one line ("Muffathalle, Zellstraße 4, 81667 München, Germany"), completed from the feed's
	 * own place where parts are missing. Null when nothing is known.
	 */
	static Location place(String text, Location feedPlace) {
		String name = null;
		String street = null;
		String zipcode = null;
		String city = null;
		if (text != null && !text.isBlank()) {
			String[] parts = text.split("\\s*[,\\n]\\s*");
			for (int i = 0; i < parts.length; i++) {
				String part = parts[i].trim();
				Matcher zipCity = ZIP_CITY.matcher(part);
				if (part.isEmpty()) {
					continue;
				}
				if (zipCity.matches()) {
					zipcode = zipCity.group(1);
					city = zipCity.group(2).trim();
				} else if (i == 0) {
					name = part;
				} else if (street == null && part.matches(".*\\p{L}.*\\d.*")) {
					street = part;
				} else if (city == null && zipcode == null && part.matches("[\\p{L} .'-]+") && i < parts.length - 1) {
					city = part;
				}
			}
			if (name != null && street == null && name.matches(".*\\p{L}.*\\s\\d+\\w?$")) {
				street = name; // the line is only an address: "Zellstraße 4"
			}
		}
		return place(name, street, zipcode, city, null, feedPlace);
	}

	/** A place from its parts, completed from the feed's own place where parts are missing. */
	static Location place(String name, String street, String zipcode, String city, String country, Location feedPlace) {
		Location f = feedPlace == null ? new Location(null, null, null, null, null) : feedPlace;
		boolean otherVenue = (blank(name) ? !blank(street) : !name.trim().equalsIgnoreCase(String.valueOf(f.name())));
		Location l = otherVenue
				// Somewhere else than the feed's venue: only its city and country can be taken from the feed
				? new Location(firstText(name, null), firstText(street, null),
						firstText(zipcode, null), firstText(city, f.city()),
						firstText(country, f.country()))
				: new Location(firstText(name, f.name()), firstText(street, f.street()),
						firstText(zipcode, f.zipcode()), firstText(city, f.city()), firstText(country, f.country()));
		boolean empty = l.name() == null && l.street() == null && l.zipcode() == null && l.city() == null
				&& l.country() == null;
		return empty ? null : l;
	}

	/** A link resolved against the page it is on; null unless it is http(s). */
	static String link(String href, String base) {
		if (href == null || href.isBlank()) {
			return null;
		}
		try {
			URI uri = base == null ? URI.create(href.trim()) : URI.create(base).resolve(href.trim());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
			return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null ? uri.toString() : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Text from HTML (or plain text with entities): tags gone, paragraphs kept as blank lines, entities decoded. */
	static String text(String html) {
		if (html == null || html.isBlank()) {
			return null;
		}
		Document doc = Jsoup.parseBodyFragment(html);
		doc.outputSettings().prettyPrint(false);
		doc.select("br").after("\\n");
		doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr").before("\\n\\n");
		String marked = doc.body().html();
		String plain = Jsoup.clean(marked, "", Safelist.none(), new Document.OutputSettings().prettyPrint(false));
		String decoded = Parser.unescapeEntities(plain.replace("\\n", "\n"), false);
		String tidy = decoded.replace(' ', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ")
				.replaceAll(" *\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
		return tidy.isEmpty() ? null : tidy;
	}

	/** One line of text (titles), at most max characters. */
	static String line(String html, int max) {
		String t = text(html);
		if (t == null) {
			return null;
		}
		t = t.replaceAll("\\s+", " ").trim();
		return t.length() <= max ? t : t.substring(0, max).trim();
	}

	static String cut(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max).trim();
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String firstText(String a, String b) {
		if (a != null && !a.isBlank()) {
			return a.trim();
		}
		return b == null || b.isBlank() ? null : b.trim();
	}
}
