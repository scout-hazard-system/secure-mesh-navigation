package dev.warp.stream;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared preferences bridge between the phone UI (MainActivity) and the
 * Android Auto car app. The activity persists the backend base URL and the
 * current routing destination here; the car session reads them so both
 * surfaces stay in sync without binding to each other.
 */
public final class AppPrefs {
/** Product Scout Mesh hub (WireGuard in-APK). */
  public static final String MESH_BASE_URL = "http://10.66.0.1:18080";
  /** @deprecated lab Tailscale; kept as optional fallback probe. */
  public static final String TAILSCALE_BASE_URL = "http://100.78.191.61:18080";
  public static final String DEFAULT_BASE_URL =
      BuildConfig.ALLOW_CLEARTEXT ? MESH_BASE_URL : "https://mesh.invalid";
  public static final String FALLBACK_BASE_URL = "http://192.168.1.154:18080";

  private static final String PREFS_NAME = "scanner_stream_prefs";
  private static final String KEY_BASE_URL = "base_url";
  private static final String KEY_PREFER_TAILSCALE = "prefer_tailscale";
  private static final String KEY_PREFER_MESH = "prefer_mesh";
  private static final String KEY_DEST_LAT = "dest_lat";
  private static final String KEY_DEST_LON = "dest_lon";
  private static final String KEY_DEST_LABEL = "dest_label";
  private static final String KEY_PREFERRED_ROUTE_ALT_INDEX = "preferred_route_alt_index";
  private static final String KEY_ROUTE_SESSION_ACTIVE = "route_session_active";
  private static final String KEY_ACTIVE_TRACKING_ENABLED = "active_tracking_enabled";
  private static final String KEY_TRACKING_CONSENT_RESOLVED = "tracking_consent_resolved";
  private static final String KEY_ANALYTICS_ENABLED = "analytics_enabled";
  private static final String KEY_PRIVACY_REVISION = "privacy_revision";
  private static final String KEY_SCOUT_PROFILE_SKETCH = "scout_profile_sketch";
  private static final long BASE_URL_PROBE_CACHE_MS = 15000L;
  private static final int BASE_URL_PROBE_TIMEOUT_MS = 900;
  private static volatile String cachedReachableBaseUrl = null;
  private static volatile long cachedReachableAtMs = 0L;
  private static final Object BASE_URL_PROBE_LOCK = new Object();

  private AppPrefs() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static String normalizeBaseUrl(String raw) {
    String value = EndpointPolicy.normalize(raw, BuildConfig.ALLOW_CLEARTEXT);
    return value == null ? DEFAULT_BASE_URL : value;
  }

  public static void saveBaseUrl(Context context, String baseUrl) {
    String normalized = normalizeBaseUrl(baseUrl);
    if (normalized == null || normalized.isEmpty()) {
      return;
    }
    synchronized (BASE_URL_PROBE_LOCK) {
      SharedPreferences preferences = prefs(context);
      if (!normalized.equals(normalizeBaseUrl(preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL)))) {
        preferences.edit()
            .putString(KEY_BASE_URL, normalized)
            .putBoolean(KEY_ACTIVE_TRACKING_ENABLED, false)
            .putBoolean(KEY_TRACKING_CONSENT_RESOLVED, false)
            .putBoolean(KEY_ANALYTICS_ENABLED, false)
            .putLong(KEY_PRIVACY_REVISION, privacyRevision(context) + 1)
            .apply();
      }
      cachedReachableBaseUrl = null;
      cachedReachableAtMs = 0L;
    }
  }

public static boolean preferMesh(Context context) {
    // Default on so phone clients prefer the in-APK Scout Mesh hub.
    return prefs(context).getBoolean(KEY_PREFER_MESH, true);
  }

  public static void setPreferMesh(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_PREFER_MESH, enabled).apply();
    synchronized (BASE_URL_PROBE_LOCK) {
      cachedReachableBaseUrl = null;
      cachedReachableAtMs = 0L;
    }
  }

  /** @deprecated use {@link #preferMesh(Context)} */
  public static boolean preferTailscale(Context context) {
    return prefs(context).getBoolean(KEY_PREFER_TAILSCALE, false);
  }

  /** @deprecated use {@link #setPreferMesh(Context, boolean)} */
  public static void setPreferTailscale(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_PREFER_TAILSCALE, enabled).apply();
    synchronized (BASE_URL_PROBE_LOCK) {
      cachedReachableBaseUrl = null;
      cachedReachableAtMs = 0L;
    }
  }

  public static String baseUrl(Context context) {
    String stored = prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    String normalized = normalizeBaseUrl(stored);
    if (normalized == null || normalized.isEmpty()) {
      normalized = DEFAULT_BASE_URL;
    }
    String cached = cachedReachableBaseUrl;
    long age = System.currentTimeMillis() - cachedReachableAtMs;
    if (cached != null && age >= 0 && age < BASE_URL_PROBE_CACHE_MS) {
      return cached;
    }
    return normalized;
  }

  public static String resolveReachableBaseUrl(Context context) {
    // Consumer builds use only the selected server; no automatic LAN/Tailscale probes.
    if (!BuildConfig.ENABLE_DEV_CONTROLS) {
      return normalizeBaseUrl(prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL));
    }
    long now = System.currentTimeMillis();
    String cached = cachedReachableBaseUrl;
    long age = now - cachedReachableAtMs;
    if (cached != null && age >= 0 && age < BASE_URL_PROBE_CACHE_MS) {
      return cached;
    }
    synchronized (BASE_URL_PROBE_LOCK) {
      long innerAge = System.currentTimeMillis() - cachedReachableAtMs;
      if (cachedReachableBaseUrl != null && innerAge >= 0 && innerAge < BASE_URL_PROBE_CACHE_MS) {
        return cachedReachableBaseUrl;
      }
      String stored = normalizeBaseUrl(prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL));
List<String> chain = buildProbeChain(stored, preferMesh(context), preferTailscale(context));
      String selected = chain.get(0);
      for (String candidate : chain) {
        if (isBackendHealthy(candidate)) {
          selected = candidate;
          break;
        }
      }
      cachedReachableBaseUrl = selected;
      cachedReachableAtMs = System.currentTimeMillis();
      if (!selected.equals(stored)) {
        prefs(context).edit().putString(KEY_BASE_URL, selected).apply();
      }
      return selected;
    }
  }

private static List<String> buildProbeChain(
      String preferred, boolean preferMesh, boolean preferTailscale) {
    Set<String> ordered = new LinkedHashSet<>();
    if (preferMesh) {
      if (isMeshCandidate(preferred)) {
        ordered.add(preferred);
      }
      ordered.add(MESH_BASE_URL);
      if (preferred != null && !preferred.isEmpty()) {
        ordered.add(preferred);
      }
      ordered.add(FALLBACK_BASE_URL);
      if (preferTailscale) {
        ordered.add(TAILSCALE_BASE_URL);
      }
    } else if (preferTailscale) {
      if (isTailscaleCandidate(preferred)) {
        ordered.add(preferred);
      }
      ordered.add(TAILSCALE_BASE_URL);
      if (preferred != null && !preferred.isEmpty()) {
        ordered.add(preferred);
      }
      ordered.add(FALLBACK_BASE_URL);
      ordered.add(MESH_BASE_URL);
    } else {
      if (preferred != null && !preferred.isEmpty()) {
        ordered.add(preferred);
      }
      ordered.add(FALLBACK_BASE_URL);
      ordered.add(MESH_BASE_URL);
      ordered.add(TAILSCALE_BASE_URL);
    }
    ordered.add(DEFAULT_BASE_URL);
    return new ArrayList<>(ordered);
  }

  private static boolean isMeshCandidate(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(baseUrl.trim());
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return false;
      }
      return host.startsWith("10.66.");
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean isTailscaleCandidate(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(baseUrl.trim());
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return false;
      }
      if (host.endsWith(".ts.net")) {
        return true;
      }
      return host.startsWith("100.");
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean isBackendHealthy(String baseUrl) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(baseUrl + "/api/health");
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(BASE_URL_PROBE_TIMEOUT_MS);
      connection.setReadTimeout(BASE_URL_PROBE_TIMEOUT_MS);
      connection.setRequestMethod("GET");
      int code = connection.getResponseCode();
      return code >= 200 && code < 300;
    } catch (Exception ignored) {
      return false;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  public static void saveDestination(Context context, Double lat, Double lon) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (lat == null || lon == null) {
      editor.remove(KEY_DEST_LAT).remove(KEY_DEST_LON).remove(KEY_DEST_LABEL);
    } else {
      editor.putString(KEY_DEST_LAT, String.valueOf(lat));
      editor.putString(KEY_DEST_LON, String.valueOf(lon));
    }
    editor.apply();
  }

  public static void saveDestinationLabel(Context context, String label) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (label == null || label.trim().isEmpty()) {
      editor.remove(KEY_DEST_LABEL);
    } else {
      editor.putString(KEY_DEST_LABEL, label.trim());
    }
    editor.apply();
  }

  /** Returns {lat, lon} or null when no destination is set. */
  public static double[] destination(Context context) {
    SharedPreferences p = prefs(context);
    String lat = p.getString(KEY_DEST_LAT, null);
    String lon = p.getString(KEY_DEST_LON, null);
    if (lat == null || lon == null) {
      return null;
    }
    try {
      return new double[] {Double.parseDouble(lat), Double.parseDouble(lon)};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  public static String destinationLabel(Context context) {
    return prefs(context).getString(KEY_DEST_LABEL, "");
  }

  public static void savePreferredRouteAlternativeIndex(Context context, Integer index) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (index == null || index < 0) {
      editor.remove(KEY_PREFERRED_ROUTE_ALT_INDEX);
    } else {
      editor.putInt(KEY_PREFERRED_ROUTE_ALT_INDEX, index);
    }
    editor.apply();
  }

  public static Integer preferredRouteAlternativeIndex(Context context) {
    SharedPreferences p = prefs(context);
    if (!p.contains(KEY_PREFERRED_ROUTE_ALT_INDEX)) {
      return null;
    }
    int value = p.getInt(KEY_PREFERRED_ROUTE_ALT_INDEX, -1);
    return value >= 0 ? value : null;
  }

  public static void setRouteSessionActive(Context context, boolean active) {
    prefs(context).edit().putBoolean(KEY_ROUTE_SESSION_ACTIVE, active).apply();
  }

  public static boolean isRouteSessionActive(Context context) {
    return prefs(context).getBoolean(KEY_ROUTE_SESSION_ACTIVE, false);
  }

  public static void clearRouteSelectionCache(Context context) {
    prefs(context)
        .edit()
        .remove(KEY_DEST_LAT)
        .remove(KEY_DEST_LON)
        .remove(KEY_DEST_LABEL)
        .remove(KEY_PREFERRED_ROUTE_ALT_INDEX)
        .putBoolean(KEY_ROUTE_SESSION_ACTIVE, false)
        .apply();
  }

  public static boolean isActiveTrackingEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ACTIVE_TRACKING_ENABLED, false);
  }

  public static boolean isTrackingConsentResolved(Context context) {
    return prefs(context).getBoolean(KEY_TRACKING_CONSENT_RESOLVED, false);
  }
  public static boolean isLocationSharingAllowed(Context context) {
    return isTrackingConsentResolved(context) && isActiveTrackingEnabled(context);
  }

  public static long privacyRevision(Context context) {
    return prefs(context).getLong(KEY_PRIVACY_REVISION, 0L);
  }

  public static SharedPreferences.OnSharedPreferenceChangeListener registerPrivacyListener(
      Context context, Runnable onChange) {
    SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
      if (KEY_PRIVACY_REVISION.equals(key)) {
        onChange.run();
      }
    };
    prefs(context).registerOnSharedPreferenceChangeListener(listener);
    return listener;
  }

  public static void unregisterPrivacyListener(
      Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
    if (listener != null) {
      prefs(context).unregisterOnSharedPreferenceChangeListener(listener);
    }
  }

  public static void setTrackingConsent(
      Context context, boolean activeTrackingEnabled, boolean markResolved) {
    synchronized (BASE_URL_PROBE_LOCK) {
      if (isActiveTrackingEnabled(context) == activeTrackingEnabled
          && isTrackingConsentResolved(context) == markResolved) {
        return;
      }
      prefs(context)
          .edit()
          .putBoolean(KEY_ACTIVE_TRACKING_ENABLED, activeTrackingEnabled)
          .putBoolean(KEY_TRACKING_CONSENT_RESOLVED, markResolved)
          .putLong(KEY_PRIVACY_REVISION, privacyRevision(context) + 1)
          .apply();
    }
  }

  public static boolean isAnalyticsEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ANALYTICS_ENABLED, false);
  }

  public static void setAnalyticsEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ANALYTICS_ENABLED, enabled).apply();
  }

  public static String scoutProfileSketch(Context context) {
    return prefs(context).getString(KEY_SCOUT_PROFILE_SKETCH, "");
  }

  public static void saveScoutProfileSketch(Context context, String sketchJson) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (sketchJson == null || sketchJson.trim().isEmpty()) {
      editor.remove(KEY_SCOUT_PROFILE_SKETCH);
    } else {
      editor.putString(KEY_SCOUT_PROFILE_SKETCH, sketchJson);
    }
    editor.apply();
  }
}
