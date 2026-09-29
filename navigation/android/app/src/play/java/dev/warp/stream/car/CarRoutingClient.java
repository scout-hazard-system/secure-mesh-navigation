package dev.warp.stream.car;

import android.net.Uri;
import dev.warp.stream.BuildConfig;
import dev.warp.stream.PrivacyHttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Blocking backend helper for Android Auto route search and route-options previews. */
final class CarRoutingClient {
  private static final MediaType JSON_MEDIA =
      MediaType.get("application/json; charset=utf-8");

  static final class AddressCandidate {
    final double lat;
    final double lon;
    final String displayName;
    final boolean fromCatalog;

    AddressCandidate(double lat, double lon, String displayName, boolean fromCatalog) {
      this.lat = lat;
      this.lon = lon;
      this.displayName = displayName;
      this.fromCatalog = fromCatalog;
    }
  }

  static final class RouteAlternative {
    final int index;
    final double distanceMeters;
    final double durationSeconds;
    final double etaSpeedLimitSeconds;
    final double maxspeedCoverage;
    final boolean hasTollHint;
    final boolean hasFerryHint;

    RouteAlternative(
        int index,
        double distanceMeters,
        double durationSeconds,
        double etaSpeedLimitSeconds,
        double maxspeedCoverage,
        boolean hasTollHint,
        boolean hasFerryHint) {
      this.index = index;
      this.distanceMeters = distanceMeters;
      this.durationSeconds = durationSeconds;
      this.etaSpeedLimitSeconds = etaSpeedLimitSeconds;
      this.maxspeedCoverage = maxspeedCoverage;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
    }
  }

  static final class RouteOptionsSummary {
    final int alternatives;
    final double shortestMeters;
    final double fastestSeconds;
    final boolean hasTollHint;
    final boolean hasFerryHint;
    final String hazardStatus;
    final String wazeRouteMode;
    final String wazeAppUrl;

    RouteOptionsSummary(
        int alternatives,
        double shortestMeters,
        double fastestSeconds,
        boolean hasTollHint,
        boolean hasFerryHint,
        String hazardStatus,
        String wazeRouteMode,
        String wazeAppUrl) {
      this.alternatives = alternatives;
      this.shortestMeters = shortestMeters;
      this.fastestSeconds = fastestSeconds;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
      this.hazardStatus = hazardStatus;
      this.wazeRouteMode = wazeRouteMode;
      this.wazeAppUrl = wazeAppUrl;
    }
  }

  private final PrivacyHttpClient client;

  CarRoutingClient(PrivacyHttpClient client) {
    this.client = client;
  }
  Runnable guard(Runnable task) {
    return client.guard(task);
  }

  List<AddressCandidate> searchDestinations(
      String baseUrl, String query, Double biasLat, Double biasLon) {
    try (PrivacyHttpClient.Scope ignored = client.scope()) {
      return searchDestinationsInScope(baseUrl, query, biasLat, biasLon);
    }
  }

  private List<AddressCandidate> searchDestinationsInScope(
      String baseUrl, String query, Double biasLat, Double biasLon) {
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    Map<String, AddressCandidate> deduped = new LinkedHashMap<>();
    List<AddressCandidate> catalogMatches = fetchCatalogResolve(baseUrl, trimmed, biasLat, biasLon);
    for (AddressCandidate catalog : catalogMatches) {
      deduped.putIfAbsent(keyFor(catalog), catalog);
    }

    if (deduped.isEmpty()) {
      List<AddressCandidate> geocode = fetchGeocodeFallback(baseUrl, trimmed, biasLat, biasLon, 6);
      for (AddressCandidate candidate : geocode) {
        deduped.putIfAbsent(keyFor(candidate), candidate);
      }
    }

    List<AddressCandidate> out = new ArrayList<>(deduped.values());
    if (out.size() > 6) {
      out = out.subList(0, 6);
    }
    return out;
  }

  void upsertCatalog(
      String baseUrl, String query, AddressCandidate candidate, Double biasLat, Double biasLon) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("query", query);
      payload.put("display_name", candidate.displayName);
      payload.put("lat", candidate.lat);
      payload.put("lon", candidate.lon);
      payload.put("source", "android_auto");
      if (biasLat != null && biasLon != null) {
        payload.put("bias_lat", biasLat);
        payload.put("bias_lon", biasLon);
      }
      Request request =
          new Request.Builder()
              .url(baseUrl + "/api/platform/address-catalog/upsert")
              .post(RequestBody.create(payload.toString(), JSON_MEDIA))
              .build();
      try (Response ignored = client.newCall(request).execute()) {
        // best effort
      }
    } catch (Exception ignored) {
      // best effort
    }
  }

  RouteOptionsSummary fetchRouteOptions(
      String baseUrl, double originLat, double originLon, double destLat, double destLon) {
    JSONObject json = fetchRouteOptionsJson(baseUrl, originLat, originLon, destLat, destLon);
    if (json == null) {
      return null;
    }
    JSONArray alternatives = json.optJSONArray("alternatives");
    if (alternatives == null || alternatives.length() == 0) {
      return null;
    }
    double shortest = Double.POSITIVE_INFINITY;
    double fastest = Double.POSITIVE_INFINITY;
    boolean toll = false;
    boolean ferry = false;
    for (int i = 0; i < alternatives.length(); i++) {
      JSONObject alt = alternatives.optJSONObject(i);
      if (alt == null) {
        continue;
      }
      double dist = alt.optDouble("distance_m", Double.NaN);
      double dur = alt.optDouble("duration_s", Double.NaN);
      if (Double.isFinite(dist) && dist < shortest) {
        shortest = dist;
      }
      if (Double.isFinite(dur) && dur > 0.0 && dur < fastest) {
        fastest = dur;
      }
      toll = toll || alt.optBoolean("has_toll_hint", false);
      ferry = ferry || alt.optBoolean("has_ferry_hint", false);
    }
    JSONObject hazards = json.optJSONObject("waze_hazards");
    String hazardStatus = hazards != null ? hazards.optString("status", "unknown") : "unknown";
    JSONObject wazeRoute = json.optJSONObject("waze_route");
    String wazeRouteMode = wazeRoute != null ? wazeRoute.optString("mode", "unknown") : "unknown";
    String wazeAppUrl = wazeRoute != null ? wazeRoute.optString("app_url", "") : "";
    if (!Double.isFinite(shortest)) {
      shortest = 0.0;
    }
    if (!Double.isFinite(fastest)) {
      fastest = 0.0;
    }
    return new RouteOptionsSummary(
        alternatives.length(),
        shortest,
        fastest,
        toll,
        ferry,
        hazardStatus,
        wazeRouteMode,
        wazeAppUrl);
  }

  List<RouteAlternative> fetchRouteAlternatives(
      String baseUrl, double originLat, double originLon, double destLat, double destLon) {
    JSONObject json = fetchRouteOptionsJson(baseUrl, originLat, originLon, destLat, destLon);
    JSONArray alternatives = json != null ? json.optJSONArray("alternatives") : null;
    List<RouteAlternative> out = new ArrayList<>();
    if (alternatives == null) {
      return out;
    }
    for (int i = 0; i < alternatives.length(); i++) {
      JSONObject alt = alternatives.optJSONObject(i);
      if (alt == null) {
        continue;
      }
      out.add(
          new RouteAlternative(
              alt.optInt("index", i),
              alt.optDouble("distance_m", 0.0),
              alt.optDouble("duration_s", 0.0),
              alt.optDouble("eta_speed_limit_s", 0.0),
              alt.optDouble("maxspeed_coverage", 0.0),
              alt.optBoolean("has_toll_hint", false),
              alt.optBoolean("has_ferry_hint", false)));
    }
    return out;
  }

  private JSONObject fetchRouteOptionsJson(
      String baseUrl, double originLat, double originLon, double destLat, double destLon) {
    String url =
        baseUrl
            + "/api/platform/route/options?origin_lat="
            + fmt(originLat)
            + "&origin_lon="
            + fmt(originLon)
            + "&dest_lat="
            + fmt(destLat)
            + "&dest_lon="
            + fmt(destLon);
    Request request = new Request.Builder().url(url).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return null;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONArray alternatives = json.optJSONArray("alternatives");
      if (alternatives == null || alternatives.length() == 0) {
        return null;
      }
      return json;
    } catch (Exception ignored) {
      return null;
    }
  }

  AddressCandidate fetchLatestGpsOrigin(String baseUrl) {
    if (!BuildConfig.ENABLE_DEV_CONTROLS) {
      return null;
    }
    Request request = new Request.Builder().url(baseUrl + "/api/gps/latest").build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return null;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONObject point = json.optJSONObject("point");
      if (point == null) {
        return null;
      }
      Double lat = asDouble(point.opt("lat"));
      Double lon = asDouble(point.opt("lon"));
      if (lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
        return null;
      }
      return new AddressCandidate(lat, lon, "backend_gps", true);
    } catch (Exception ignored) {
      return null;
    }
  }

  private List<AddressCandidate> fetchCatalogResolve(
      String baseUrl, String query, Double biasLat, Double biasLon) {
    String url =
        baseUrl
            + "/api/platform/address-catalog/resolve?q="
            + Uri.encode(query)
            + biasParams(biasLat, biasLon);
    Request request = new Request.Builder().url(url).build();
    List<AddressCandidate> out = new ArrayList<>();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return out;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONArray results = firstArray(json.optJSONArray("results"), json.optJSONArray("entries"));
      if (results != null) {
        for (int i = 0; i < results.length(); i++) {
          JSONObject item = results.optJSONObject(i);
          AddressCandidate candidate = parseCandidate(item, query, true);
          if (candidate != null) {
            out.add(candidate);
          }
        }
      }
      if (out.isEmpty()) {
        JSONObject candidate =
            firstObject(
                json.optJSONObject("entry"),
                json.optJSONObject("result"),
                json.optJSONObject("address"),
                firstArrayObject(json.optJSONArray("results")),
                firstArrayObject(json.optJSONArray("entries")));
        AddressCandidate parsed = parseCandidate(candidate, query, true);
        if (parsed != null) {
          out.add(parsed);
        }
      }
      return out;
    } catch (Exception ignored) {
      return out;
    }
  }

  private List<AddressCandidate> fetchGeocodeFallback(
      String baseUrl, String query, Double biasLat, Double biasLon, int maxResults) {
    String url =
        baseUrl
            + "/api/platform/geocode?q="
            + Uri.encode(query)
            + biasParams(biasLat, biasLon);
    Request request = new Request.Builder().url(url).build();
    List<AddressCandidate> out = new ArrayList<>();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return out;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONArray results = json.optJSONArray("results");
      if (results == null) {
        return out;
      }
      for (int i = 0; i < results.length() && out.size() < maxResults; i++) {
        JSONObject item = results.optJSONObject(i);
        AddressCandidate candidate = parseCandidate(item, query, false);
        if (candidate != null) {
          out.add(candidate);
        }
      }
    } catch (Exception ignored) {
      return out;
    }
    return out;
  }

  private AddressCandidate parseCandidate(
      JSONObject candidate, String fallbackDisplayName, boolean fromCatalog) {
    if (candidate == null) {
      return null;
    }
    Double lat = asDouble(candidate.opt("lat"));
    Double lon = asDouble(candidate.opt("lon"));
    if (lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
      return null;
    }
    String display = candidate.optString("display_name", "").trim();
    if (display.isEmpty()) {
      display = candidate.optString("name", "").trim();
    }
    if (display.isEmpty()) {
      display = fallbackDisplayName;
    }
    return new AddressCandidate(lat, lon, display, fromCatalog);
  }

  private String keyFor(AddressCandidate candidate) {
    return fmt(candidate.lat)
        + "|"
        + fmt(candidate.lon)
        + "|"
        + candidate.displayName.toLowerCase(Locale.ROOT);
  }

  private String biasParams(Double biasLat, Double biasLon) {
    if (biasLat == null || biasLon == null) {
      return "";
    }
    return "&lat=" + fmt(biasLat) + "&lon=" + fmt(biasLon);
  }

  private String fmt(double value) {
    return String.format(Locale.US, "%.6f", value);
  }

  private JSONObject firstObject(JSONObject... objects) {
    for (JSONObject object : objects) {
      if (object != null) {
        return object;
      }
    }
    return null;
  }

  private JSONObject firstArrayObject(JSONArray array) {
    if (array == null || array.length() == 0) {
      return null;
    }
    return array.optJSONObject(0);
  }

  private JSONArray firstArray(JSONArray... arrays) {
    for (JSONArray array : arrays) {
      if (array != null) {
        return array;
      }
    }
    return null;
  }

  private Double asDouble(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}