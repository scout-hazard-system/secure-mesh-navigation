package dev.warp.stream;

import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact, persistent personalization profile using small weighted tags. */
public final class ScoutProfileSketch {
  private static final int VERSION = 1;
  private static final int MAX_TAGS = 32;
  private static final int MAX_WEIGHT = 127;
  private static final int MIN_WEIGHT = -127;
  private static final int PRUNE_WEIGHT_THRESHOLD = 1;
  private static final double DECAY_FACTOR = 0.97d;
  private static final long DECAY_INTERVAL_MS = 6L * 60L * 60L * 1000L;

  private final LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
  private long lastDecayAtMs = System.currentTimeMillis();

  public static ScoutProfileSketch fromJson(String raw) {
    ScoutProfileSketch sketch = new ScoutProfileSketch();
    if (TextUtils.isEmpty(raw)) {
      return sketch;
    }
    try {
      JSONObject json = new JSONObject(raw);
      JSONArray tags = json.optJSONArray("tags");
      if (tags != null) {
        for (int i = 0; i < tags.length(); i++) {
          JSONArray pair = tags.optJSONArray(i);
          if (pair == null || pair.length() < 2) {
            continue;
          }
          String key = normalizeTag(pair.optString(0, ""));
          int value = clampWeight(pair.optInt(1, 0));
          if (!TextUtils.isEmpty(key) && value != 0) {
            sketch.weights.put(key, value);
          }
        }
      }
      long storedDecayAt = json.optLong("d", System.currentTimeMillis());
      sketch.lastDecayAtMs = storedDecayAt > 0 ? storedDecayAt : System.currentTimeMillis();
      sketch.pruneAndTrim();
      return sketch;
    } catch (Exception ignored) {
      return sketch;
    }
  }

  public JSONObject toMemoryHintJson() {
    applyTimeDecayIfDue();
    JSONObject root = new JSONObject();
    JSONArray tags = new JSONArray();
    List<Map.Entry<String, Integer>> sorted = sortedByWeightDesc();
    for (Map.Entry<String, Integer> entry : sorted) {
      JSONArray pair = new JSONArray();
      pair.put(entry.getKey());
      pair.put(entry.getValue());
      tags.put(pair);
    }
    try {
      root.put("v", VERSION);
      root.put("tags", tags);
    } catch (Exception ignored) {
      // best-effort memory hint payload
    }
    return root;
  }

  public String toJson() {
    applyTimeDecayIfDue();
    JSONObject root = new JSONObject();
    JSONArray tags = new JSONArray();
    for (Map.Entry<String, Integer> entry : sortedByWeightDesc()) {
      JSONArray pair = new JSONArray();
      pair.put(entry.getKey());
      pair.put(entry.getValue());
      tags.put(pair);
    }
    try {
      root.put("v", VERSION);
      root.put("d", lastDecayAtMs);
      root.put("tags", tags);
    } catch (Exception ignored) {
      // best-effort persistence payload
    }
    return root.toString();
  }

  public void observeUserPrompt(String prompt) {
    if (TextUtils.isEmpty(prompt)) {
      return;
    }
    String text = prompt.toLowerCase(Locale.ROOT);
    if (text.contains("avoid toll")) {
      reinforce("avoid_tolls", 12);
    }
    if (text.contains("avoid highway") || text.contains("no highway")) {
      reinforce("avoid_highways", 10);
    }
    if (text.contains("fastest")) {
      reinforce("prefer_fastest_route", 8);
    }
    if (text.contains("shortest")) {
      reinforce("prefer_shortest_route", 8);
    }
    if (text.contains("scenic")) {
      reinforce("prefer_scenic_route", 8);
    }
    if (text.contains("brief") || text.contains("short answer")) {
      reinforce("response_brief", 10);
      reinforce("response_detailed", -6);
    }
    if (text.contains("detailed")) {
      reinforce("response_detailed", 10);
      reinforce("response_brief", -6);
    }
    if (text.contains("gas") || text.contains("fuel")) {
      reinforce("needs_fuel_stops", 5);
    }
    if (text.contains("charge") || text.contains("ev") || text.contains("electric")) {
      reinforce("needs_ev_charging", 7);
    }
    pruneAndTrim();
  }

  public void observePlatformContext(
      String localCallContext, String broadcastifyStreamContext, String hazardApiStreamContext) {
    observePlatformContextSection(localCallContext, "local");
    observePlatformContextSection(broadcastifyStreamContext, "broadcastify");
    observePlatformContextSection(hazardApiStreamContext, "hazard");
    pruneAndTrim();
  }

  private void observePlatformContextSection(String rawContext, String namespace) {
    if (TextUtils.isEmpty(rawContext)) {
      return;
    }
    String normalizedContext = rawContext.toLowerCase(Locale.ROOT);
    String[] entries = normalizedContext.split("\\|");
    int entriesConsidered = 0;
    for (String entry : entries) {
      if (entriesConsidered >= 8) {
        break;
      }
      String trimmedEntry = entry == null ? "" : entry.trim();
      if (trimmedEntry.isEmpty()) {
        continue;
      }
      String[] tokens = trimmedEntry.split("[^a-z0-9_]+");
      int tokenBudget = 0;
      for (String token : tokens) {
        if (tokenBudget >= 4) {
          break;
        }
        if (token.length() < 3 || token.length() > 32) {
          continue;
        }
        if (isCommonNoiseToken(token)) {
          continue;
        }
        reinforce(namespace + "_" + token, 2);
        tokenBudget += 1;
      }
      entriesConsidered += 1;
    }
  }

  private boolean isCommonNoiseToken(String token) {
    return "status".equals(token)
        || "route".equals(token)
        || "routes".equals(token)
        || "clusters".equals(token)
        || "hazards".equals(token)
        || "unknown".equals(token)
        || "none".equals(token)
        || "api".equals(token)
        || "rms".equals(token)
        || "count".equals(token);
  }

  public void observeAssistantResponse(String responseText) {
    if (TextUtils.isEmpty(responseText)) {
      return;
    }
    String text = responseText.toLowerCase(Locale.ROOT);
    if (text.contains("toll")) {
      reinforce("routing_cost_awareness", 3);
    }
    if (text.contains("traffic")) {
      reinforce("traffic_sensitive", 4);
    }
    pruneAndTrim();
  }

  private void reinforce(String rawTag, int delta) {
    String tag = normalizeTag(rawTag);
    if (TextUtils.isEmpty(tag) || delta == 0) {
      return;
    }
    int current = weights.containsKey(tag) ? weights.get(tag) : 0;
    int next = clampWeight(current + delta);
    if (next == 0) {
      weights.remove(tag);
      return;
    }
    weights.put(tag, next);
  }

  private void applyTimeDecayIfDue() {
    long now = System.currentTimeMillis();
    if (now <= lastDecayAtMs || (now - lastDecayAtMs) < DECAY_INTERVAL_MS) {
      return;
    }
    for (Map.Entry<String, Integer> entry : weights.entrySet()) {
      int decayed = (int) Math.round(entry.getValue() * DECAY_FACTOR);
      entry.setValue(clampWeight(decayed));
    }
    lastDecayAtMs = now;
    pruneAndTrim();
  }

  private void pruneAndTrim() {
    Iterator<Map.Entry<String, Integer>> it = weights.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Integer> entry = it.next();
      if (Math.abs(entry.getValue()) <= PRUNE_WEIGHT_THRESHOLD) {
        it.remove();
      }
    }
    if (weights.size() <= MAX_TAGS) {
      return;
    }
    List<Map.Entry<String, Integer>> sorted = sortedByWeightDesc();
    weights.clear();
    int kept = 0;
    for (Map.Entry<String, Integer> entry : sorted) {
      if (kept >= MAX_TAGS) {
        break;
      }
      weights.put(entry.getKey(), entry.getValue());
      kept += 1;
    }
  }

  private List<Map.Entry<String, Integer>> sortedByWeightDesc() {
    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(weights.entrySet());
    Collections.sort(
        sorted,
        Comparator.<Map.Entry<String, Integer>>comparingInt(e -> Math.abs(e.getValue()))
            .reversed());
    return sorted;
  }

  private static int clampWeight(int value) {
    if (value > MAX_WEIGHT) {
      return MAX_WEIGHT;
    }
    if (value < MIN_WEIGHT) {
      return MIN_WEIGHT;
    }
    return value;
  }

  private static String normalizeTag(String tag) {
    if (tag == null) {
      return "";
    }
    String normalized = tag.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
    return normalized;
  }
}
