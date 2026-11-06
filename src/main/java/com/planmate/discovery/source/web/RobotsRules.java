package com.planmate.discovery.source.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a site's robots.txt allows a crawler (RFC 9309): the group for its own name, else the group for "*". Of the
 * Allow and Disallow rules matching a path, the longest wins, and Allow wins a tie. "*" and "$" work as in Google's
 * reading of the file.
 */
final class RobotsRules {

	static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

	private record Rule(boolean allow, String path, Pattern pattern) {
	}

	private final List<Rule> rules;

	private RobotsRules(List<Rule> rules) {
		this.rules = rules;
	}

	static RobotsRules parse(String text, String agent) {
		String me = agent.toLowerCase(Locale.ROOT);
		List<Rule> mine = new ArrayList<>();
		List<Rule> anyone = new ArrayList<>();
		List<String> agents = new ArrayList<>();
		List<Rule> group = new ArrayList<>();
		boolean inRules = false;
		boolean foundMine = false;
		List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n|\\r")));
		lines.add("user-agent: end-of-file");
		for (String raw : lines) {
			String line = raw.replaceFirst("#.*$", "").trim();
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			if (key.equals("user-agent")) {
				if (inRules) { // a new group starts: the previous one is complete
					if (agents.contains(me)) {
						mine.addAll(group);
						foundMine = true;
					} else if (agents.contains("*")) {
						anyone.addAll(group);
					}
					agents.clear();
					group = new ArrayList<>();
					inRules = false;
				}
				agents.add(value.toLowerCase(Locale.ROOT));
			} else if (key.equals("allow") || key.equals("disallow")) {
				inRules = true;
				if (!value.isEmpty()) {
					group.add(new Rule(key.equals("allow"), value, pattern(value)));
				}
			}
		}
		return new RobotsRules(foundMine ? mine : anyone);
	}

	boolean allows(String path) {
		Rule best = null;
		for (Rule r : rules) {
			if (r.pattern().matcher(path).lookingAt()) {
				if (best == null || r.path().length() > best.path().length()
						|| (r.path().length() == best.path().length() && r.allow())) {
					best = r;
				}
			}
		}
		return best == null || best.allow();
	}

	/** "/a*b$" matches paths starting with /a, then anything, ending in b. */
	private static Pattern pattern(String path) {
		boolean anchored = path.endsWith("$");
		String body = anchored ? path.substring(0, path.length() - 1) : path;
		StringBuilder regex = new StringBuilder();
		String[] parts = body.split("\\*", -1);
		for (int i = 0; i < parts.length; i++) {
			regex.append(i == 0 ? "" : ".*").append(Pattern.quote(parts[i]));
		}
		return Pattern.compile(regex + (anchored ? "$" : ""));
	}
}
