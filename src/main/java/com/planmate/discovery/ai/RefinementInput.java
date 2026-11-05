package com.planmate.discovery.ai;

import java.util.List;

/** What the AI gets for one event: only the text it writes from, plus where it takes place. */
public record RefinementInput(String id, String title, String description, String venue, String city, List<String> sourceTags) {
}
