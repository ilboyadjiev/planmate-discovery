package com.planmate.discovery.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

import com.planmate.discovery.model.Location;
import com.planmate.discovery.model.SourceEvent;

/**
 * Fingerprints of an event, to tell new, changed and unchanged events apart without comparing field by field.
 * Tags count as a set (order does not matter); everything else exactly.
 */
public final class ContentHasher {

	private static final char SEP = '\u001f';

	private ContentHasher() {
	}

	/** Everything the source said about the event. */
	public static String contentHash(SourceEvent e) {
		Location l = e.location();
		return sha256(join(e.title(), e.description(), e.startTime(), e.endTime(),
				l == null ? null : join(l.name(), l.street(), l.zipcode(), l.city(), l.country()),
				String.join(",", new TreeSet<>(e.tags())), e.url(), e.cancelledAtSource()));
	}

	/** Only what the AI writes from: title and description. */
	public static String textHash(SourceEvent e) {
		return sha256(join(e.title(), e.description()));
	}

	private static String join(Object... parts) {
		StringBuilder text = new StringBuilder();
		for (Object part : parts) {
			text.append(Objects.toString(part, "␀")).append(SEP);
		}
		return text.toString();
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
