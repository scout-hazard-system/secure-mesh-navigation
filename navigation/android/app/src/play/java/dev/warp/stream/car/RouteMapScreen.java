package dev.warp.stream.car;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.location.LocationListenerCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import dev.warp.stream.AppPrefs;
import dev.warp.stream.BuildConfig;
import dev.warp.stream.PrivacyHttpClient;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android Auto navigation screen. Draws the backend map engine
 * (/api/map/render, which overlays the OSRM route when a destination is set)
 * onto the car's surface, forwards the vehicle GPS fix to the backend so
 * scanner channel selection follows the car, and polls /api/mobile/snapshot
 * for scanner alerts which are shown as car toasts.
 */
public final class RouteMapScreen extends Screen implements DefaultLifecycleObserver {
  private static final String TAG = "ScannerCar";
  private static final String CAR_NAVIGATE_ACTION = "androidx.car.app.action.NAVIGATE";
  private static final String ACTION_VOICE_COMMAND = Intent.ACTION_VOICE_COMMAND;
  private static final String ACTION_ASSIST = Intent.ACTION_ASSIST;
  private static final String ACTION_SEARCH = Intent.ACTION_SEARCH;
  private static final long RENDER_INTERVAL_MS = 1500L;
  private static final long GPS_POST_INTERVAL_MS = 3000L;
  private static final long ALERT_POLL_INTERVAL_MS = 5000L;
  private static final double DEFAULT_LAT = 48.4941;
  private static final double DEFAULT_LON = -122.6120;
  private static final double DEFAULT_COUNTRY_MPP = 9000.0;
  private static final double MIN_MPP = 1.5;
  private static final double MAX_MPP = 60000.0;
  private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

  private final CarContext carContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final PrivacyHttpClient httpClient;
  private final CarRoutingClient routingClient;

  private HandlerThread renderThread;
  private Handler renderHandler;
  private LocationManager locationManager;
  private LocationListenerCompat locationListener;
  private long locationRegistrationGeneration;
  private boolean destroyed;

  private final Object surfaceLock = new Object();
  private Surface surface;
  private int surfaceWidth;
  private int surfaceHeight;

  private volatile double metersPerPixel = DEFAULT_COUNTRY_MPP;
  private volatile boolean followVehicle = true;
  private volatile Location lastFix;
  private volatile long lastFixPrivacyRevision = -1L;
  private SharedPreferences.OnSharedPreferenceChangeListener privacyListener;
  private long lastGpsPostMs;
  private long lastAlertPollMs;
  private String lastAlertKey = "";

  private final Runnable renderTick =
      new Runnable() {
        @Override
        public void run() {
          try {
            renderOnce();
          } catch (Exception ex) {
            Log.w(TAG, "render tick failed: " + ex);
          }
          Handler handler = renderHandler;
          if (handler != null) {
            handler.postDelayed(this, RENDER_INTERVAL_MS);
          }
        }
      };

  private final SurfaceCallback surfaceCallback =
      new SurfaceCallback() {
        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
          synchronized (surfaceLock) {
            surface = container.getSurface();
            surfaceWidth = container.getWidth();
            surfaceHeight = container.getHeight();
          }
          requestRenderSoon();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
          synchronized (surfaceLock) {
            surface = null;
          }
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
          // Pan gestures disable follow mode until "Center" is pressed.
          followVehicle = false;
        }

        @Override
        public void onScale(float focusX, float focusY, float scaleFactor) {
          if (scaleFactor > 0f) {
            adjustZoom(1f / scaleFactor);
          }
        }
      };

  public RouteMapScreen(@NonNull CarContext context) {
    super(context);
    this.carContext = context;
    this.httpClient = new PrivacyHttpClient(context, new OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build());
    this.routingClient = new CarRoutingClient(httpClient);
    getLifecycle().addObserver(this);
  }

  private void openRouteAlternatives() {
    double[] dest = AppPrefs.destination(carContext);
    if (dest == null) {
      CarToast.makeText(carContext, "Set a destination first", CarToast.LENGTH_SHORT).show();
      return;
    }
    double originLat;
    double originLon;
    Location fix = consentedFix();
    if (fix != null) {
      originLat = fix.getLatitude();
      originLon = fix.getLongitude();
    } else {
      double[] backendFix = fetchBackendGps(AppPrefs.resolveReachableBaseUrl(carContext));
      if (backendFix != null) {
        originLat = backendFix[0];
        originLon = backendFix[1];
      } else {
        originLat = dest[0];
        originLon = dest[1];
      }
    }
    getScreenManager()
        .push(
            new RouteAlternativesScreen(
                carContext,
                routingClient,
                alternative -> {
                  requestRenderSoon();
                  String toast =
                      "Preferred route "
                          + (alternative.index + 1)
                          + " • "
                          + formatDistance(alternative.distanceMeters)
                          + " • "
                          + formatDuration(
                              alternative.etaSpeedLimitSeconds > 0.0
                                  ? alternative.etaSpeedLimitSeconds
                                  : alternative.durationSeconds);
                  CarToast.makeText(carContext, toast, CarToast.LENGTH_LONG).show();
                },
                originLat,
                originLon,
                dest[0],
                dest[1]));
  }

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {
    carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    renderThread = new HandlerThread("car-map-render");
    renderThread.start();
    renderHandler = new Handler(renderThread.getLooper());
    renderHandler.post(() -> AppPrefs.resolveReachableBaseUrl(carContext));
    renderHandler.postDelayed(renderTick, 300L);
    privacyListener = AppPrefs.registerPrivacyListener(carContext, () -> {
      stopLocationUpdates();
      httpClient.dispatcher().cancelAll();
      // Child screens retain route origins and suggestion biases from the previous privacy scope.
      getScreenManager().popToRoot();
      if (AppPrefs.isLocationSharingAllowed(carContext)) {
        startLocationUpdates();
      }
      requestRenderSoon();
    });
    startLocationUpdates();
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    destroyed = true;
    AppPrefs.unregisterPrivacyListener(carContext, privacyListener);
    httpClient.close();
    stopLocationUpdates();
    Handler handler = renderHandler;
    if (handler != null) {
      handler.removeCallbacksAndMessages(null);
    }
    renderHandler = null;
    if (renderThread != null) {
      renderThread.quitSafely();
      renderThread = null;
    }
    try {
      carContext.getCarService(AppManager.class).setSurfaceCallback(null);
    } catch (Exception ignored) {
    }
  }

  @NonNull
  @Override
  public Template onGetTemplate() {
    ActionStrip actionStrip =
        new ActionStrip.Builder()
            .addAction(
                new Action.Builder()
                    .setTitle("Zoom +")
                    .setOnClickListener(() -> adjustZoom(1.0 / 1.5))
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Zoom -")
                    .setOnClickListener(() -> adjustZoom(1.5))
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Center")
                    .setOnClickListener(
                        () -> {
                          followVehicle = true;
                          requestRenderSoon();
                        })
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Route")
                    .setOnClickListener(this::openRouteOverview)
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Assist")
                    .setOnClickListener(this::openVoiceAssistantSearch)
                    .build())
            .build();
    return new NavigationTemplate.Builder().setActionStrip(actionStrip).build();
  }

  private void openVoiceAssistantSearch() {
    openDestinationSearch();
    CarToast.makeText(carContext, "Voice assistant ready: speak destination", CarToast.LENGTH_SHORT)
        .show();
  }
  private void openRouteOverview() {
    getScreenManager()
        .push(
            new RouteOverviewScreen(
                carContext,
                routingClient,
                new RouteOverviewScreen.Controller() {
                  @Override
                  public void onSearchRequested() {
                    getScreenManager().pop();
                    openDestinationSearch();
                  }
                  @Override
                  public void onAlternativesRequested() {
                    getScreenManager().pop();
                    openRouteAlternatives();
                  }

                  @Override
                  public void onDestinationCleared() {
                    AppPrefs.saveDestination(carContext, null, null);
                    AppPrefs.saveDestinationLabel(carContext, "");
                    AppPrefs.savePreferredRouteAlternativeIndex(carContext, null);
                    requestRenderSoon();
                    CarToast.makeText(carContext, "Destination cleared", CarToast.LENGTH_SHORT)
                        .show();
                    getScreenManager().pop();
                  }
                }));
  }

  private void openDestinationSearch() {
    Double biasLat = null;
    Double biasLon = null;
    Location fix = consentedFix();
    if (fix != null) {
      biasLat = fix.getLatitude();
      biasLon = fix.getLongitude();
    } else {
      double[] backendFix = fetchBackendGps(AppPrefs.resolveReachableBaseUrl(carContext));
      if (backendFix != null) {
        biasLat = backendFix[0];
        biasLon = backendFix[1];
      }
    }
    getScreenManager()
        .push(
            new DestinationSearchScreen(
                carContext,
                routingClient,
                new DestinationSearchScreen.Listener() {
                  @Override
                  public void onDestinationSelected(CarRoutingClient.AddressCandidate candidate) {
                    AppPrefs.saveDestination(carContext, candidate.lat, candidate.lon);
                    AppPrefs.saveDestinationLabel(carContext, candidate.displayName);
                    AppPrefs.savePreferredRouteAlternativeIndex(carContext, null);
                    followVehicle = true;
                    requestRenderSoon();
                    CarToast.makeText(
                            carContext,
                            "Destination set: " + truncate(candidate.displayName, 60),
                            CarToast.LENGTH_SHORT)
                        .show();
                    fetchRouteOptionsPreview(candidate.lat, candidate.lon);
                  }

                  @Override
                  public void onDestinationCleared() {
                    AppPrefs.saveDestination(carContext, null, null);
                    AppPrefs.saveDestinationLabel(carContext, "");
                    AppPrefs.savePreferredRouteAlternativeIndex(carContext, null);
                    requestRenderSoon();
                    CarToast.makeText(carContext, "Destination cleared", CarToast.LENGTH_SHORT)
                        .show();
                  }
                },
                biasLat,
                biasLon));
  }

  private void adjustZoom(double factor) {
    double next = metersPerPixel * factor;
    if (next < MIN_MPP) {
      next = MIN_MPP;
    }
    if (next > MAX_MPP) {
      next = MAX_MPP;
    }
    metersPerPixel = next;
    requestRenderSoon();
  }

  private void requestRenderSoon() {
    Handler handler = renderHandler;
    if (handler != null) {
      handler.removeCallbacks(renderTick);
      handler.post(renderTick);
    }
  }

  @SuppressLint("MissingPermission")
  private void startLocationUpdates() {
    if (destroyed || !AppPrefs.isLocationSharingAllowed(carContext)) {
      return;
    }
    if (carContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "location permission missing; device tracking unavailable");
      return;
    }
    try {
      stopLocationUpdates();
      locationManager = (LocationManager) carContext.getSystemService(CarContext.LOCATION_SERVICE);
      long registrationRevision = AppPrefs.privacyRevision(carContext);
      long registrationGeneration = locationRegistrationGeneration;
      locationListener =
          location -> {
            if (!destroyed && registrationGeneration == locationRegistrationGeneration
                && AppPrefs.isLocationSharingAllowed(carContext)
                && registrationRevision == AppPrefs.privacyRevision(carContext)) {
              lastFixPrivacyRevision = registrationRevision;
              lastFix = new Location(location);
            }
          };
      if (locationManager != null) {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
      }
    } catch (Exception ex) {
      Log.w(TAG, "location updates unavailable: " + ex);
    }
  }

  private void stopLocationUpdates() {
    locationRegistrationGeneration++;
    lastFix = null;
    lastFixPrivacyRevision = -1L;
    if (locationManager != null && locationListener != null) {
      try {
        locationManager.removeUpdates(locationListener);
      } catch (Exception ignored) {
      }
    }
    locationManager = null;
    locationListener = null;
  }

  private Location consentedFix() {
    return AppPrefs.isLocationSharingAllowed(carContext)
        && lastFixPrivacyRevision == AppPrefs.privacyRevision(carContext) ? lastFix : null;
  }

  /** One render pass: resolve center, fetch map PNG from backend, blit to surface. */
  private void renderOnce() {
    try (PrivacyHttpClient.Scope ignored = httpClient.scope()) {
      renderScoped();
    }
  }

  private void renderScoped() {
    long privacyRevision = AppPrefs.privacyRevision(carContext);
    Surface target;
    int width;
    int height;
    synchronized (surfaceLock) {
      target = surface;
      width = surfaceWidth;
      height = surfaceHeight;
    }
    if (target == null || !target.isValid() || width <= 0 || height <= 0) {
      return;
    }

    String baseUrl = AppPrefs.resolveReachableBaseUrl(carContext);
    if (!BuildConfig.ALLOW_CLEARTEXT && AppPrefs.DEFAULT_BASE_URL.equals(baseUrl)) {
      return;
    }
    Location fix = consentedFix();
    double lat;
    double lon;
    double heading = 0.0;
    if (fix != null) {
      lat = fix.getLatitude();
      lon = fix.getLongitude();
      if (fix.hasBearing()) {
        heading = fix.getBearing();
      }
      maybePostGps(baseUrl, fix);
    } else {
      double[] backendFix = fetchBackendGps(baseUrl);
      if (backendFix != null) {
        lat = backendFix[0];
        lon = backendFix[1];
      } else {
        double[] dest = AppPrefs.destination(carContext);
        lat = dest != null ? dest[0] : DEFAULT_LAT;
        lon = dest != null ? dest[1] : DEFAULT_LON;
      }
    }

    StringBuilder url =
        new StringBuilder(baseUrl)
            .append("/api/map/render?lat=")
            .append(String.format(Locale.US, "%.6f", lat))
            .append("&lon=")
            .append(String.format(Locale.US, "%.6f", lon))
            .append("&mpp=")
            .append(String.format(Locale.US, "%.2f", metersPerPixel))
            .append("&heading=")
            .append(String.format(Locale.US, "%.1f", followVehicle ? heading : 0.0))
            .append("&w=")
            .append(width)
            .append("&h=")
            .append(height);
    double[] dest = AppPrefs.destination(carContext);
    if (dest != null) {
      url.append("&dest_lat=")
          .append(String.format(Locale.US, "%.6f", dest[0]))
          .append("&dest_lon=")
          .append(String.format(Locale.US, "%.6f", dest[1]));
    }

    Bitmap bitmap = null;
    if (privacyRevision != AppPrefs.privacyRevision(carContext)) {
      return;
    }
    try (Response response =
        httpClient.newCall(new Request.Builder().url(url.toString()).build()).execute()) {
      if (response.isSuccessful() && response.body() != null) {
        byte[] bytes = response.body().bytes();
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      }
    } catch (Exception ex) {
      Log.w(TAG, "map fetch failed: " + ex);
    }

    if (privacyRevision == AppPrefs.privacyRevision(carContext)) {
      drawFrame(target, bitmap);
      maybePollAlerts(baseUrl);
    }
  }

  private void drawFrame(Surface target, Bitmap bitmap) {
    Canvas canvas = null;
    try {
      canvas = target.lockCanvas(null);
      canvas.drawColor(Color.BLACK);
      if (bitmap != null) {
        Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
        canvas.drawBitmap(bitmap, null, dst, null);
      }
    } catch (Exception ex) {
      Log.w(TAG, "surface draw failed: " + ex);
    } finally {
      if (canvas != null) {
        try {
          target.unlockCanvasAndPost(canvas);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Forward the car's GPS fix to the backend so scanning follows the vehicle. */
  private void maybePostGps(String baseUrl, Location fix) {
    if (!AppPrefs.isLocationSharingAllowed(carContext) || fix != consentedFix()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastGpsPostMs < GPS_POST_INTERVAL_MS) {
      return;
    }
    lastGpsPostMs = now;
    try {
      JSONObject body = new JSONObject();
      body.put("user_id", "android-auto-" + Build.MODEL.replace(' ', '_'));
      body.put("source", "android_auto");
      body.put("lat", fix.getLatitude());
      body.put("lon", fix.getLongitude());
      if (fix.hasAccuracy()) {
        body.put("accuracy", (double) fix.getAccuracy());
      }
      if (fix.hasSpeed()) {
        body.put("speed", (double) fix.getSpeed());
      }
      if (fix.hasBearing()) {
        body.put("heading", (double) fix.getBearing());
      }
      Request request =
          new Request.Builder()
              .url(baseUrl + "/api/gps/update")
              .post(RequestBody.create(body.toString(), JSON_MEDIA))
              .build();
      try (Response response = httpClient.newCall(request).execute()) {
        // best effort
      }
    } catch (Exception ex) {
      Log.w(TAG, "gps post failed: " + ex);
    }
  }

  /** Fallback center: latest fix known to the backend (e.g. from the phone app). */
  private double[] fetchBackendGps(String baseUrl) {
    if (!BuildConfig.ENABLE_DEV_CONTROLS || !AppPrefs.isLocationSharingAllowed(carContext)) {
      return null;
    }
    try (Response response =
        httpClient
            .newCall(new Request.Builder().url(baseUrl + "/api/gps/latest").build())
            .execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return null;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONObject point = json.optJSONObject("point");
      if (point == null) {
        return null;
      }
      return new double[] {point.getDouble("lat"), point.getDouble("lon")};
    } catch (Exception ex) {
      return null;
    }
  }

  /** Poll scanner alerts and surface new ones as car toasts. */
  private void maybePollAlerts(String baseUrl) {
    if (!BuildConfig.ENABLE_DEV_CONTROLS) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastAlertPollMs < ALERT_POLL_INTERVAL_MS) {
      return;
    }
    lastAlertPollMs = now;
    try (Response response =
        httpClient
            .newCall(new Request.Builder().url(baseUrl + "/api/mobile/snapshot").build())
            .execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONArray events = json.optJSONArray("events");
      if (events == null || events.length() == 0) {
        return;
      }
      for (int i = events.length() - 1; i >= 0; i--) {
        JSONObject event = events.optJSONObject(i);
        if (event == null) {
          continue;
        }
        String type = event.optString("event_type", "");
        if (!"alert_triggered".equals(type)) {
          continue;
        }
        String key = event.optString("ts", "") + "|" + event.optString("alert", "");
        if (key.equals(lastAlertKey)) {
          return; // newest alert already shown
        }
        lastAlertKey = key;
        String alertText = event.optString("alert", "");
        if (alertText.isEmpty()) {
          alertText = event.optString("transcript", "Scanner alert");
        }
        JSONObject intel = event.optJSONObject("llm_intel");
        if (intel != null) {
          String priority = intel.optString("priority", "").trim();
          if (!priority.isEmpty() && !"unknown".equalsIgnoreCase(priority)) {
            alertText = "[" + priority.toUpperCase(Locale.ROOT) + "] " + alertText;
          }
        }
        final String toastText =
            alertText.length() > 120 ? alertText.substring(0, 117) + "..." : alertText;
        mainHandler.post(() -> CarToast.makeText(carContext, toastText, CarToast.LENGTH_LONG).show());
        return;
      }
    } catch (Exception ex) {
      Log.w(TAG, "alert poll failed: " + ex);
    }
  }

  private void fetchRouteOptionsPreview(double destLat, double destLon) {
    Handler handler = renderHandler;
    if (handler == null) {
      return;
    }
    handler.post(
        () -> {
          String baseUrl = AppPrefs.resolveReachableBaseUrl(carContext);
          double[] origin = null;
          Location fix = consentedFix();
          if (fix != null) {
            origin = new double[] {fix.getLatitude(), fix.getLongitude()};
          } else {
            origin = fetchBackendGps(baseUrl);
          }
          if (origin == null) {
            return;
          }
          CarRoutingClient.RouteOptionsSummary summary =
              routingClient.fetchRouteOptions(baseUrl, origin[0], origin[1], destLat, destLon);
          if (summary == null) {
            return;
          }
          String details =
              "alts "
                  + summary.alternatives
                  + " • "
                  + formatDistance(summary.shortestMeters)
                  + " • "
                  + formatDuration(summary.fastestSeconds)
                  + (summary.hasTollHint ? " • toll hint" : "")
                  + (summary.hasFerryHint ? " • ferry hint" : "")
                  + ("ok".equalsIgnoreCase(summary.hazardStatus) ? " • hazards" : "");
          mainHandler.post(() -> CarToast.makeText(carContext, details, CarToast.LENGTH_LONG).show());
        });
  }

  private String formatDistance(double meters) {
    if (!Double.isFinite(meters) || meters <= 0) {
      return "distance n/a";
    }
    double km = meters / 1000.0;
    if (km >= 10.0) {
      return String.format(Locale.US, "%.0f km", km);
    }
    return String.format(Locale.US, "%.1f km", km);
  }

  private String formatDuration(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0) {
      return "duration n/a";
    }
    long minutes = Math.max(1L, Math.round(seconds / 60.0));
    if (minutes >= 60L) {
      long hours = minutes / 60L;
      long rem = minutes % 60L;
      return rem == 0 ? (hours + "h") : (hours + "h " + rem + "m");
    }
    return minutes + "m";
  }

  private String truncate(String value, int maxLen) {
    if (value == null) {
      return "";
    }
    if (value.length() <= maxLen) {
      return value;
    }
    if (maxLen <= 1) {
      return value.substring(0, Math.max(0, maxLen));
    }
    return value.substring(0, maxLen - 1) + "…";
  }

  void handleNavigationIntent(Intent intent) {
    if (intent == null) {
      return;
    }
    String action = intent.getAction();
    Uri data = intent.getData();
    String query = extractNavigationQuery(intent, data);
    double[] coordinates = extractNavigationCoordinates(data);
    boolean assistantAction =
        ACTION_VOICE_COMMAND.equals(action) || ACTION_ASSIST.equals(action) || ACTION_SEARCH.equals(action);
    if (TextUtils.isEmpty(query) && coordinates == null && !CAR_NAVIGATE_ACTION.equals(action) && !assistantAction) {
      return;
    }
    if (assistantAction && TextUtils.isEmpty(query) && coordinates == null) {
      mainHandler.post(this::openVoiceAssistantSearch);
      return;
    }
    Handler handler = renderHandler;
    if (handler == null) {
      return;
    }
    handler.post(
        () -> {
          if (coordinates != null) {
            applyResolvedDestination(
                coordinates[0],
                coordinates[1],
                TextUtils.isEmpty(query) ? "Voice destination" : query.trim());
            return;
          }
          if (TextUtils.isEmpty(query)) {
            return;
          }
          resolveDestinationFromVoiceQuery(query.trim());
        });
  }

  private void resolveDestinationFromVoiceQuery(String query) {
    String baseUrl = AppPrefs.resolveReachableBaseUrl(carContext);
    Double biasLat = null;
    Double biasLon = null;
    Location fix = lastFix;
    if (fix != null) {
      biasLat = fix.getLatitude();
      biasLon = fix.getLongitude();
    } else {
      double[] backendFix = fetchBackendGps(baseUrl);
      if (backendFix != null) {
        biasLat = backendFix[0];
        biasLon = backendFix[1];
      }
    }
    try {
      java.util.List<CarRoutingClient.AddressCandidate> matches =
          routingClient.searchDestinations(baseUrl, query, biasLat, biasLon);
      if (matches == null || matches.isEmpty()) {
        mainHandler.post(
            () ->
                CarToast.makeText(
                        carContext, "Voice destination not found: " + truncate(query, 40), CarToast.LENGTH_LONG)
                    .show());
        return;
      }
      CarRoutingClient.AddressCandidate candidate = matches.get(0);
      if (!candidate.fromCatalog) {
        routingClient.upsertCatalog(baseUrl, query, candidate, biasLat, biasLon);
      }
      applyResolvedDestination(candidate.lat, candidate.lon, candidate.displayName);
    } catch (Exception ex) {
      Log.w(TAG, "voice destination resolve failed: " + ex);
      mainHandler.post(
          () ->
              CarToast.makeText(
                      carContext, "Voice destination lookup failed", CarToast.LENGTH_SHORT)
                  .show());
    }
  }

  private void applyResolvedDestination(double lat, double lon, String label) {
    AppPrefs.saveDestination(carContext, lat, lon);
    AppPrefs.saveDestinationLabel(carContext, label);
    AppPrefs.savePreferredRouteAlternativeIndex(carContext, null);
    followVehicle = true;
    requestRenderSoon();
    fetchRouteOptionsPreview(lat, lon);
    mainHandler.post(
        () ->
            CarToast.makeText(
                    carContext, "Voice route set: " + truncate(label, 60), CarToast.LENGTH_SHORT)
                .show());
  }

  private String extractNavigationQuery(Intent intent, Uri data) {
    if (intent != null) {
      String qExtra = intent.getStringExtra("q");
      if (!TextUtils.isEmpty(qExtra)) {
        return qExtra;
      }
      String queryExtra = intent.getStringExtra("query");
      if (!TextUtils.isEmpty(queryExtra)) {
        return queryExtra;
      }
      String destinationExtra = intent.getStringExtra("destination");
      if (!TextUtils.isEmpty(destinationExtra)) {
        return destinationExtra;
      }
    }
    if (data == null) {
      return "";
    }
    String q = data.getQueryParameter("q");
    if (!TextUtils.isEmpty(q)) {
      return q;
    }
    String daddr = data.getQueryParameter("daddr");
    if (!TextUtils.isEmpty(daddr)) {
      return daddr;
    }
    return "";
  }

  private double[] extractNavigationCoordinates(Uri data) {
    if (data == null) {
      return null;
    }
    String latLon = data.getQueryParameter("ll");
    if (TextUtils.isEmpty(latLon)) {
      latLon = data.getQueryParameter("q");
    }
    if (TextUtils.isEmpty(latLon)) {
      latLon = data.getSchemeSpecificPart();
    }
    if (TextUtils.isEmpty(latLon)) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern
            .compile("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)")
            .matcher(latLon);
    if (!matcher.find()) {
      return null;
    }
    try {
      double lat = Double.parseDouble(matcher.group(1));
      double lon = Double.parseDouble(matcher.group(2));
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        return null;
      }
      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        return null;
      }
      return new double[] {lat, lon};
    } catch (Exception ignored) {
      return null;
    }
  }
}
