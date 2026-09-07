package dev.warp.stream;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class RouteOptionsActivity extends AppCompatActivity {
  public static final String EXTRA_BASE_URL = "extra_base_url";
  public static final String EXTRA_ORIGIN_LAT = "extra_origin_lat";
  public static final String EXTRA_ORIGIN_LON = "extra_origin_lon";
  public static final String EXTRA_DEST_LAT = "extra_dest_lat";
  public static final String EXTRA_DEST_LON = "extra_dest_lon";
  public static final String EXTRA_DEST_LABEL = "extra_dest_label";
  public static final String EXTRA_PRIVACY_REVISION = "extra_privacy_revision";
  private static final double DEFAULT_COUNTRY_SCENE_RADIUS_M = 1_250_000.0;
  private static final double MIN_SCENE_RADIUS_M = 120_000.0;

  private final PrivacyHttpClient client =
      new PrivacyHttpClient(this, new OkHttpClient.Builder().build());
  private TextView statusText;
  private TextView summaryText;
  private TextView hazardsText;
  private Map3dView routeMapView;
  private double originLat = Double.NaN;
  private double originLon = Double.NaN;
  private double destinationLat = Double.NaN;
  private double destinationLon = Double.NaN;
  private String destinationLabel = "Destination";
  private String wazeAppUrl = "";
  private String wazeRouteMode = "unknown";
  private String activeBaseUrl = "";
  private volatile boolean sceneFetchInFlight = false;
  private SharedPreferences.OnSharedPreferenceChangeListener privacyListener;
  private long originPrivacyRevision;

  private static final class RouteOverlayMeta {
    private final List<double[]> path;
    private final boolean hasTollHint;
    private final boolean hasFerryHint;

    private RouteOverlayMeta(List<double[]> path, boolean hasTollHint, boolean hasFerryHint) {
      this.path = path;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
    }
  }

  private static final class ClusterAlertItem {
    private final String ts;
    private final String alert;
    private final String transcript;

    private ClusterAlertItem(String ts, String alert, String transcript) {
      this.ts = ts;
      this.alert = alert;
      this.transcript = transcript;
    }
  }

  private static final class ClusterOverlayMeta {
    private final double lat;
    private final double lon;
    private final int index;
    private final int count;
    private final List<ClusterAlertItem> alerts;

    private ClusterOverlayMeta(double lat, double lon, int index, int count, List<ClusterAlertItem> alerts) {
      this.lat = lat;
      this.lon = lon;
      this.index = index;
      this.count = count;
      this.alerts = alerts;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    originPrivacyRevision = getIntent().getLongExtra(EXTRA_PRIVACY_REVISION, -1L);
    if (originPrivacyRevision != AppPrefs.privacyRevision(this)) {
      finish();
      return;
    }
    setContentView(R.layout.activity_route_options);
    WindowInsetsSupport.apply(this);
    statusText = findViewById(R.id.routeOptionsStatus);
    summaryText = findViewById(R.id.routeOptionsSummary);
    hazardsText = findViewById(R.id.routeOptionsHazards);
    routeMapView = findViewById(R.id.routeOptionsMapView);
    routeMapView.setRefetchListener((lat, lon, radiusM) -> fetchRouteScene(lat, lon, radiusM, false));
    Button closeButton = findViewById(R.id.routeOptionsCloseBtn);
    Button startButton = findViewById(R.id.routeOptionsStartBtn);
    Button shareButton = findViewById(R.id.routeOptionsShareBtn);
    closeButton.setOnClickListener(v -> finish());
    startButton.setOnClickListener(v -> startNavigation());
    shareButton.setOnClickListener(v -> createShareEtaLink());
    privacyListener = AppPrefs.registerPrivacyListener(this, () -> {
      client.close();
      originLat = Double.NaN;
      originLon = Double.NaN;
      activeBaseUrl = "";
      sceneFetchInFlight = false;
      routeMapView.clearPrivateLocation();
      finish();
    });
    fetchOptions();
  }

  @Override
  protected void onDestroy() {
    AppPrefs.unregisterPrivacyListener(this, privacyListener);
    client.close();
    super.onDestroy();
  }

  private void postPrivateUi(Runnable task) {
    runOnUiThread(client.guard(() -> {
      if (!isFinishing() && !isDestroyed()
          && originPrivacyRevision == AppPrefs.privacyRevision(this)) {
        task.run();
      }
    }));
  }

  private void fetchOptions() {
    String baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
    double originLat = readNumericExtra(EXTRA_ORIGIN_LAT);
    double originLon = readNumericExtra(EXTRA_ORIGIN_LON);
    double destLat = readNumericExtra(EXTRA_DEST_LAT);
    double destLon = readNumericExtra(EXTRA_DEST_LON);
    String destLabel = getIntent().getStringExtra(EXTRA_DEST_LABEL);
    this.originLat = originLat;
    this.originLon = originLon;
    destinationLat = destLat;
    destinationLon = destLon;
    destinationLabel = (destLabel == null || destLabel.isBlank()) ? "Destination" : destLabel;
    if (baseUrl == null || baseUrl.isEmpty() || !Double.isFinite(destLat) || !Double.isFinite(destLon)) {
      statusText.setText("Route options unavailable");
      summaryText.setText("Missing route context.");
      return;
    }
    activeBaseUrl = baseUrl;
    if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
      originLat = destLat;
      originLon = destLon;
      this.originLat = originLat;
      this.originLon = originLon;
    }
    String url =
        baseUrl
            + "/api/platform/route/options?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destLon);
    statusText.setText("Loading route options…");
    final String destinationTitle = destinationLabel;
    final double resolvedOriginLat = originLat;
    final double resolvedOriginLon = originLon;
    final double resolvedDestLat = destLat;
    final double resolvedDestLon = destLon;
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                postPrivateUi(
                    () -> {
                      statusText.setText("Route options unavailable");
                      summaryText.setText("Backend request failed: " + e.getMessage());
                      hazardsText.setText("Traffic/Hazard APIs: unavailable");
                      wazeAppUrl = "";
                      wazeRouteMode = "unknown";
                    });
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    postPrivateUi(
                        () -> {
                          statusText.setText("Route options unavailable");
                          summaryText.setText("HTTP " + response.code());
                          hazardsText.setText("Traffic/Hazard APIs: unavailable");
                          wazeAppUrl = "";
                          wazeRouteMode = "unknown";
                        });
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  JSONArray alternatives = payload.optJSONArray("alternatives");
                  JSONObject hazards = payload.optJSONObject("waze_hazards");
                  JSONObject wazeRoute = payload.optJSONObject("waze_route");
                  JSONObject alertClustersPayload = payload.optJSONObject("alert_clusters");
                  List<String> lines = new ArrayList<>();
                  List<RouteOverlayMeta> routeOverlays = new ArrayList<>();
                  List<ClusterOverlayMeta> alertClusterOverlays = new ArrayList<>();
                  int alertClusterCount = 0;
                  lines.add(destinationTitle);
                  lines.add("");
                  if (alternatives != null) {
                    for (int i = 0; i < alternatives.length(); i++) {
                      JSONObject route = alternatives.optJSONObject(i);
                      if (route == null) {
                        continue;
                      }
                      double distanceM = route.optDouble("distance_m", 0.0);
                      double durationS = route.optDouble("duration_s", 0.0);
                      boolean ferry = route.optBoolean("has_ferry_hint", false);
                      boolean toll = route.optBoolean("has_toll_hint", false);
                      lines.add(
                          String.format(
                              Locale.ROOT,
                              "Route %d  •  %.1f km  •  %.0f min  •  ferry %s  •  toll %s",
                              i + 1,
                              distanceM / 1000.0,
                              durationS / 60.0,
                              ferry ? "yes" : "no",
                              toll ? "yes" : "no"));
                      JSONArray points = route.optJSONArray("route_points");
                      List<double[]> path = new ArrayList<>();
                      if (points != null) {
                        for (int p = 0; p < points.length(); p++) {
                          JSONObject point = points.optJSONObject(p);
                          if (point == null) {
                            continue;
                          }
                          double lat = point.optDouble("lat", Double.NaN);
                          double lon = point.optDouble("lon", Double.NaN);
                          if (Double.isFinite(lat) && Double.isFinite(lon)) {
                            path.add(new double[] {lat, lon});
                          }
                        }
                      }
                      if (path.size() >= 2) {
                        routeOverlays.add(new RouteOverlayMeta(path, toll, ferry));
                      }
                    }
                  }
                  if (alertClustersPayload != null) {
                    JSONArray clusters = alertClustersPayload.optJSONArray("clusters");
                    if (clusters != null) {
                      alertClusterCount = clusters.length();
                      for (int i = 0; i < clusters.length(); i++) {
                        JSONObject cluster = clusters.optJSONObject(i);
                        if (cluster == null) {
                          continue;
                        }
                        double lat = cluster.optDouble("lat", Double.NaN);
                        double lon = cluster.optDouble("lon", Double.NaN);
                        if (Double.isFinite(lat) && Double.isFinite(lon)) {
                          List<ClusterAlertItem> alerts = new ArrayList<>();
                          JSONArray alertItems = cluster.optJSONArray("alerts");
                          if (alertItems != null) {
                            for (int a = 0; a < alertItems.length(); a++) {
                              JSONObject alertObj = alertItems.optJSONObject(a);
                              if (alertObj == null) {
                                continue;
                              }
                              alerts.add(
                                  new ClusterAlertItem(
                                      alertObj.optString("ts", ""),
                                      alertObj.optString("alert", ""),
                                      alertObj.optString("transcript", "")));
                            }
                          }
                          int count = cluster.optInt("count", alerts.size());
                          alertClusterOverlays.add(
                              new ClusterOverlayMeta(
                                  lat, lon, alertClusterOverlays.size() + 1, count, alerts));
                        }
                      }
                    }
                  }
                  String hazardLine = "Waze hazards API: unavailable";
                  int hazardCount = 0;
                  if (hazards != null) {
                    String hazardStatus = hazards.optString("status", "unknown");
                    String provider = hazards.optString("provider", "waze");
                    JSONArray hazardItems = hazards.optJSONArray("hazards");
                    if (hazardItems == null) {
                      JSONObject rawObject = hazards.optJSONObject("raw");
                      if (rawObject != null) {
                        hazardItems = rawObject.optJSONArray("hazards");
                        if (hazardItems == null) {
                          hazardItems = rawObject.optJSONArray("items");
                        }
                      }
                    }
                    if (hazardItems != null) {
                      hazardCount = hazardItems.length();
                    } else {
                      JSONArray rawArray = hazards.optJSONArray("raw");
                      if (rawArray != null) {
                        hazardCount = rawArray.length();
                      }
                    }
                    hazardLine =
                        "Waze hazards API: "
                            + hazardStatus
                            + " (provider="
                            + provider
                            + ", count="
                            + hazardCount
                            + ")";
                  }
                  String wazeLine = "Waze route API: unavailable";
                  String routeMode = "unknown";
                  String routeAppUrl = "";
                  if (wazeRoute != null) {
                    routeMode = wazeRoute.optString("mode", "unknown");
                    routeAppUrl = wazeRoute.optString("app_url", "");
                    wazeLine =
                        "Waze route API: "
                            + routeMode
                            + (routeAppUrl.isBlank() ? " (deeplink=unavailable)" : " (deeplink=ready)");
                  }
                  String clusterStatus =
                      alertClustersPayload == null
                          ? "unavailable"
                          : alertClustersPayload.optString("status", "unknown");
                  String clusterLine =
                      "Alert clusters API: " + clusterStatus + " (count=" + alertClusterCount + ")";
                  String text = String.join("\n", lines);
                  final String hazardDisplay = hazardLine;
                  final String wazeDisplay = wazeLine;
                  final String clusterDisplay = clusterLine;
                  final String finalWazeAppUrl = routeAppUrl;
                  final String finalWazeRouteMode = routeMode;
                  postPrivateUi(
                      () -> {
                        statusText.setText("Choose a route");
                        summaryText.setText(text);
                        hazardsText.setText(
                            "Traffic/Hazard APIs • "
                                + hazardDisplay
                                + "  •  "
                                + wazeDisplay
                                + "  •  "
                                + clusterDisplay);
                        wazeAppUrl = finalWazeAppUrl;
                        wazeRouteMode = finalWazeRouteMode;
                        renderRoutes(
                            routeOverlays,
                            alertClusterOverlays,
                            resolvedOriginLat,
                            resolvedOriginLon,
                            resolvedDestLat,
                            resolvedDestLon);
                      });
                } catch (Exception e) {
                  postPrivateUi(
                      () -> {
                        statusText.setText("Route options unavailable");
                        summaryText.setText("Parse error: " + e.getMessage());
                        hazardsText.setText("Traffic/Hazard APIs: unavailable");
                        wazeAppUrl = "";
                        wazeRouteMode = "unknown";
                      });
                }
              }
            });
  }

  private void createShareEtaLink() {
    if (activeBaseUrl == null || activeBaseUrl.isBlank()) {
      statusText.setText("Share link unavailable");
      return;
    }
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destinationLat)
        || !Double.isFinite(destinationLon)) {
      statusText.setText("Share link unavailable");
      return;
    }
    statusText.setText("Creating share link…");
    String url =
        activeBaseUrl
            + "/api/platform/share/eta/create?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destinationLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destinationLon)
            + "&destination_label="
            + Uri.encode(destinationLabel);
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                postPrivateUi(() -> statusText.setText("Share link failed: " + e.getMessage()));
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    postPrivateUi(
                        () -> statusText.setText("Share link failed (HTTP " + response.code() + ")"));
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  String shareUrl = payload.optString("share_url", "").trim();
                  if (shareUrl.isBlank()) {
                    postPrivateUi(() -> statusText.setText("Share link failed (missing URL)"));
                    return;
                  }
                  postPrivateUi(
                      () -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(
                            Intent.EXTRA_TEXT,
                            "Scout ETA share for " + destinationLabel + ":\n" + shareUrl);
                        startActivity(
                            Intent.createChooser(
                                intent, getString(R.string.route_options_share_chooser)));
                        statusText.setText("Share link ready");
                      });
                } catch (Exception e) {
                  postPrivateUi(() -> statusText.setText("Share link parse error: " + e.getMessage()));
                }
              }
            });
  }

  private double readNumericExtra(String key) {
    Bundle extras = getIntent().getExtras();
    if (extras == null || !extras.containsKey(key)) {
      return Double.NaN;
    }
    Object raw = extras.get(key);
    if (raw instanceof Number) {
      return ((Number) raw).doubleValue();
    }
    if (raw instanceof String) {
      try {
        return Double.parseDouble((String) raw);
      } catch (NumberFormatException ignored) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }

  private void renderRoutes(
      List<RouteOverlayMeta> routeOverlays,
      List<ClusterOverlayMeta> alertClusterOverlays,
      double originLat,
      double originLon,
      double destLat,
      double destLon) {
    List<double[]> selectedRoute = new ArrayList<>();
    if (!routeOverlays.isEmpty() && !routeOverlays.get(0).path.isEmpty()) {
      selectedRoute.addAll(routeOverlays.get(0).path);
    }
    if (selectedRoute.size() < 2) {
      selectedRoute.add(new double[] {originLat, originLon});
      selectedRoute.add(new double[] {destLat, destLon});
    }
    double north = Math.max(originLat, destLat);
    double south = Math.min(originLat, destLat);
    double east = Math.max(originLon, destLon);
    double west = Math.min(originLon, destLon);
    for (RouteOverlayMeta meta : routeOverlays) {
      for (double[] point : meta.path) {
        if (point == null || point.length < 2) {
          continue;
        }
        north = Math.max(north, point[0]);
        south = Math.min(south, point[0]);
        east = Math.max(east, point[1]);
        west = Math.min(west, point[1]);
      }
    }
    for (ClusterOverlayMeta meta : alertClusterOverlays) {
      north = Math.max(north, meta.lat);
      south = Math.min(south, meta.lat);
      east = Math.max(east, meta.lon);
      west = Math.min(west, meta.lon);
    }
    double centerLat = (north + south) / 2.0;
    double centerLon = (east + west) / 2.0;
    double radiusM =
        Math.max(
            DEFAULT_COUNTRY_SCENE_RADIUS_M,
            Math.max(geoSpanMeters(north - south), geoSpanMeters(east - west)) * 0.7);
    routeMapView.setRoute(selectedRoute);
    routeMapView.setDestination(destLat, destLon);
    routeMapView.updateDevice(originLat, originLon, null, null);
    fetchRouteScene(centerLat, centerLon, radiusM, true);
  }

  private double geoSpanMeters(double degrees) {
    return Math.abs(degrees) * 111320.0;
  }

  private void showClusterAlertsDialog(ClusterOverlayMeta cluster) {
    List<String> items = new ArrayList<>();
    for (ClusterAlertItem item : cluster.alerts) {
      String headline = item.alert == null ? "" : item.alert.trim();
      if (headline.isEmpty()) {
        headline = item.transcript == null ? "" : item.transcript.trim();
      }
      if (headline.isEmpty()) {
        headline = "Alert";
      }
      if (headline.length() > 140) {
        headline = headline.substring(0, 137) + "…";
      }
      String ts = item.ts == null ? "" : item.ts.trim();
      items.add(ts.isEmpty() ? headline : (ts + "  •  " + headline));
    }
    if (items.isEmpty()) {
      items.add("No alert details available for this cluster yet.");
    }
    new AlertDialog.Builder(this)
        .setTitle("Alert cluster " + cluster.index + " (" + cluster.count + ")")
        .setItems(items.toArray(new String[0]), (dialog, which) -> {})
        .setPositiveButton("Close", null)
        .show();
  }

  private void startNavigation() {
    if (!Double.isFinite(destinationLat) || !Double.isFinite(destinationLon)) {
      statusText.setText("Destination unavailable");
      return;
    }
    AppPrefs.saveDestination(this, destinationLat, destinationLon);
    AppPrefs.saveDestinationLabel(this, destinationLabel);
    AppPrefs.setRouteSessionActive(this, true);
    statusText.setText("In-app navigation started");
    Intent intent = new Intent(this, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    intent.putExtra("focus_route", true);
    startActivity(intent);
    finish();
  }

  private void fetchRouteScene(double lat, double lon, double radiusM, boolean force) {
    if (isFinishing() || originPrivacyRevision != AppPrefs.privacyRevision(this)
        || activeBaseUrl == null || activeBaseUrl.isBlank()) {
      return;
    }
    if (sceneFetchInFlight && !force) {
      return;
    }
    double adjustedRadiusM = Math.max(MIN_SCENE_RADIUS_M, radiusM);
    sceneFetchInFlight = true;
    String url =
        activeBaseUrl
            + "/api/map/scene?lat="
            + String.format(Locale.ROOT, "%.6f", lat)
            + "&lon="
            + String.format(Locale.ROOT, "%.6f", lon)
            + "&radius_m="
            + Math.round(adjustedRadiusM);
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                sceneFetchInFlight = false;
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  String body = response.body().string();
                  postPrivateUi(() -> routeMapView.setSceneJson(body));
                } finally {
                  sceneFetchInFlight = false;
                }
              }
            });
  }
}
