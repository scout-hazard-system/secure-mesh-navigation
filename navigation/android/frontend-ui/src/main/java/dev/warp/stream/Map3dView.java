package dev.warp.stream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Proprietary on-device 3D vector map renderer.
 *
 * Consumes compact vector scenes from the backend map engine
 * (/api/map/scene) and renders a navigation-style view with extruded
 * buildings, class-styled roads, live route, and camera follow with
 * heading-up rotation and tilt. Pure android.graphics - no GL, no
 * external map SDK. Map data (c) OpenStreetMap contributors (ODbL).
 */
public class Map3dView extends View {
  private static final String TAG = "Map3dView";

  public interface RefetchListener {
    void onRefetchNeeded(double lat, double lon, double radiusM);
  }

  // Road class indices (order = draw order, minor first).
  private static final int CLASS_PATH = 0;
  private static final int CLASS_RAIL = 1;
  private static final int CLASS_SERVICE = 2;
  private static final int CLASS_RESIDENTIAL = 3;
  private static final int CLASS_TERTIARY = 4;
  private static final int CLASS_SECONDARY = 5;
  private static final int CLASS_PRIMARY = 6;
  private static final int CLASS_MOTORWAY = 7;
  private static final int CLASS_COUNT = 8;
  private static final float[] CLASS_WIDTH_M = {2f, 2.5f, 4f, 7f, 9f, 10f, 12f, 16f};
  private static final int[] CLASS_FILL = {
    0xFF3B4252, 0xFF4A5160, 0xFF454D5E, 0xFF5A6478,
    0xFF66718A, 0xFF6E7A96, 0xFF8A7A50, 0xFFB08948
  };

  private static final int AREA_PARK = 0;
  private static final int AREA_WATER = 1;
  private static final int AREA_SAND = 2;
  private static final int AREA_LOT = 3;
  private static final int AREA_CIVIC = 4;
  private static final int AREA_COUNT = 5;
  private static final int[] AREA_FILL = {0xFF20301F, 0xFF1E3A5F, 0xFF3A3626, 0xFF23272F, 0xFF2B2733};

  private static final int COLOR_BG = 0xFF171A21;
  private static final int COLOR_CASING = 0xFF20242E;
  private static final int COLOR_WALL = 0xFF262C39;
  private static final int COLOR_ROOF = 0xFF333B4D;
  private static final int COLOR_ROOF_LINE = 0xFF414B61;
  private static final int COLOR_ROUTE = 0xFF2B6BE6;
  private static final int COLOR_ROUTE_CASING = 0xCCFFFFFF;
  private static final int COLOR_LABEL = 0xFFC7CEDB;
  private static final int COLOR_LABEL_HALO = 0xC0171A21;
  private static final int COLOR_DEVICE = 0xFF2B6BE6;
  private static final int COLOR_DEST = 0xFFD84040;

  private static final long FOLLOW_RESUME_AFTER_TOUCH_MS = 8000L;
  private static final long REFETCH_MIN_INTERVAL_MS = 6500L;
  private static final long REFETCH_AFTER_SCALE_COOLDOWN_MS = 900L;
  private static final double REFETCH_EDGE_FRACTION = 0.55;
  private static final double REFETCH_ZOOM_OUT_FACTOR = 1.45;
  private static final double REFETCH_ZOOM_IN_FACTOR = 0.18;
  private static final double REFETCH_MIN_RADIUS_M = 120000.0;
  private static final float ZOOM_SETTLE_EPSILON = 0.0015f;
  private static final float ZOOM_SMOOTH_FACTOR = 0.28f;
  private static final float ZOOM_VELOCITY_BLEND = 0.34f;
  private static final float MIN_MPP = 1.0f;
  // Global scale: at ~60000 m/dp a phone screen spans a continent and beyond.
  private static final float MAX_MPP = 60000f;
  private static final float BUILDING_SKIP_MPP = 9f;
  private static final float LABEL_SKIP_MPP = 5f;
  private static final float PLACE_LABEL_MIN_MPP = 6f;
  private static final float DEFAULT_COUNTRY_MPP = 14000f;
  private static final double MAX_FETCH_RADIUS_M = 7500000.0;
  private static final long NO_SCENE_LOG_INTERVAL_MS = 2500L;
  private static final long NO_SCENE_RETRY_DRAW_MS = 350L;
  private static final double WEB_MERCATOR_EARTH_RADIUS_M = 6378137.0;
  private static final double WEB_MERCATOR_MAX_LAT = 85.05112878;

  /** Immutable parsed scene in local meter space (east = +x, north = +y). */
  private static final class Scene {
    double lat0;
    double lon0;
    double mercatorX0;
    double mercatorY0;
    double radiusM;
    int zoom;
    // Roads
    int[] roadClass;
    String[] roadName;
    float[][] roadPts;
    float[] roadMinX;
    float[] roadMinY;
    float[] roadMaxX;
    float[] roadMaxY;
    // Buildings
    float[] bldHeight;
    float[][] bldPts;
    float[] bldCx;
    float[] bldCy;
    float[] bldRadius;
    // Areas
    int[] areaKind;
    float[][] areaPts;
    float[] areaMinX;
    float[] areaMinY;
    float[] areaMaxX;
    float[] areaMaxY;
    // POIs
    String[] poiName;
    float[] poiX;
    float[] poiY;

    float toX(double lon) {
      return (float) (lonToMercatorX(lon) - mercatorX0);
    }

    float toY(double lat) {
      return (float) (latToMercatorY(lat) - mercatorY0);
    }

    double toLat(double y) {
      return mercatorYToLat(mercatorY0 + y);
    }

    double toLon(double x) {
      return mercatorXToLon(mercatorX0 + x);
    }
  }

  private volatile Scene scene;
  private volatile String loadingHint = "";

  // Camera state (meters in scene space).
  private float camX;
  private float camY;
  private float headingDeg;
  private float tiltDeg = 52f;
  private float mpp = DEFAULT_COUNTRY_MPP; // meters per dp
  private float targetMpp = DEFAULT_COUNTRY_MPP;
  private float zoomVelocity = 0f;
  private boolean followDevice = true;
  private long lastTouchMs;

  // Device state
  private volatile boolean hasDevice;
  private volatile double deviceLat;
  private volatile double deviceLon;
  private volatile float deviceHeadingDeg;
  private volatile float deviceSpeedMps;

  // Destination + route (raw lat/lon, converted per frame)
  private volatile double[] routeLatLon; // interleaved
  private volatile Double destLat;
  private volatile Double destLon;
  private volatile double[] alertClusterLatLon;
  private volatile int[] alertClusterCounts;

  private RefetchListener refetchListener;
  private long lastRefetchMs;
  private long lastScaleGestureMs;
  private long lastNoSceneLogMs;
  private long lastZoomInputMs;
  private float zoomAnchorScreenX;
  private float zoomAnchorScreenY;
  private float zoomAnchorWorldX;
  private float zoomAnchorWorldY;
  private boolean zoomAnchorActive;

  // Reused draw objects (no allocations per frame where possible)
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path workPath = new Path();
  private final Path wallPath = new Path();
  private final DashPathEffect railDash = new DashPathEffect(new float[] {14f, 10f}, 0f);
  private long[] buildingSortKeys = new long[0];

  // Gesture state
  private ScaleGestureDetector scaleDetector;
  private float lastX;
  private float lastY;
  private float lastAngle;
  private boolean twoFinger;
  private long lastTapMs;

  // FPS accounting
  private int frameCount;
  private long fpsWindowStartMs;

  private float density = 1f;

  public Map3dView(Context context) {
    super(context);
    init(context);
  }

  public Map3dView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  private void init(Context context) {
    density = context.getResources().getDisplayMetrics().density;
    strokePaint.setStyle(Paint.Style.STROKE);
    fillPaint.setStyle(Paint.Style.FILL);
    textPaint.setColor(COLOR_LABEL);
    textPaint.setTextSize(11f * density);
    textHaloPaint.setColor(COLOR_LABEL_HALO);
    textHaloPaint.setTextSize(11f * density);
    textHaloPaint.setStyle(Paint.Style.STROKE);
    textHaloPaint.setStrokeWidth(3f * density);
    scaleDetector =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScaleBegin(ScaleGestureDetector detector) {
                followDevice = false;
                lastTouchMs = SystemClock.elapsedRealtime();
                return true;
              }
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                applyScaleAroundFocus(
                    detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                lastScaleGestureMs = SystemClock.elapsedRealtime();
                lastTouchMs = lastScaleGestureMs;
                return true;
              }

              @Override
              public void onScaleEnd(ScaleGestureDetector detector) {
                lastScaleGestureMs = SystemClock.elapsedRealtime();
              }
            });
  }

  public void setRefetchListener(RefetchListener listener) {
    refetchListener = listener;
  }

  /** True once a scene has been parsed and is renderable. */
  public boolean hasScene() {
    return scene != null;
  }

  /** Status line drawn under the loading text while no scene is available. */
  public void setLoadingHint(String hint) {
    loadingHint = hint == null ? "" : hint;
    postInvalidateOnAnimation();
  }
  /** Drop the fix and its derived viewport when consent or the selected server changes. */
  public void clearPrivateLocation() {
    hasDevice = false;
    deviceLat = 0;
    deviceLon = 0;
    deviceHeadingDeg = 0;
    deviceSpeedMps = 0;
    scene = null;
    routeLatLon = null;
    alertClusterLatLon = null;
    alertClusterCounts = null;
    camX = 0;
    camY = 0;
    headingDeg = 0;
    zoomAnchorActive = false;
    zoomVelocity = 0;
    postInvalidateOnAnimation();
  }

  /** Parse scene JSON (call from a background thread), then swap it in. */
  public void setSceneJson(String json) {
    try {
      JSONObject root = new JSONObject(json);
      JSONObject center = root.optJSONObject("center");
      if (center == null) {
        return;
      }
      Scene s = new Scene();
      s.lat0 = center.optDouble("lat", 0);
      s.lon0 = center.optDouble("lon", 0);
      s.mercatorX0 = lonToMercatorX(s.lon0);
      s.mercatorY0 = latToMercatorY(s.lat0);
      s.radiusM = root.optDouble("radius_m", 700);
      s.zoom = root.optInt("zoom", 15);

      JSONArray roads = root.optJSONArray("roads");
      int rn = roads == null ? 0 : roads.length();
      s.roadClass = new int[rn];
      s.roadName = new String[rn];
      s.roadPts = new float[rn][];
      s.roadMinX = new float[rn];
      s.roadMinY = new float[rn];
      s.roadMaxX = new float[rn];
      s.roadMaxY = new float[rn];
      for (int i = 0; i < rn; i++) {
        JSONObject r = roads.getJSONObject(i);
        s.roadClass[i] = roadClassIndex(r.optString("c", "residential"));
        s.roadName[i] = r.optString("n", "");
        s.roadPts[i] = parsePts(s, r.optJSONArray("p"));
        computeBounds(s.roadPts[i], s.roadMinX, s.roadMinY, s.roadMaxX, s.roadMaxY, i);
      }

      JSONArray buildings = root.optJSONArray("buildings");
      int bn = buildings == null ? 0 : buildings.length();
      s.bldHeight = new float[bn];
      s.bldPts = new float[bn][];
      s.bldCx = new float[bn];
      s.bldCy = new float[bn];
      s.bldRadius = new float[bn];
      for (int i = 0; i < bn; i++) {
        JSONObject b = buildings.getJSONObject(i);
        s.bldHeight[i] = (float) b.optDouble("h", 6.0);
        float[] pts = parsePts(s, b.optJSONArray("p"));
        s.bldPts[i] = pts;
        float cx = 0f;
        float cy = 0f;
        int n = pts.length / 2;
        for (int k = 0; k < n; k++) {
          cx += pts[k * 2];
          cy += pts[k * 2 + 1];
        }
        cx /= Math.max(1, n);
        cy /= Math.max(1, n);
        float radius = 0f;
        for (int k = 0; k < n; k++) {
          float dx = pts[k * 2] - cx;
          float dy = pts[k * 2 + 1] - cy;
          radius = Math.max(radius, dx * dx + dy * dy);
        }
        s.bldCx[i] = cx;
        s.bldCy[i] = cy;
        s.bldRadius[i] = (float) Math.sqrt(radius);
      }

      JSONArray areas = root.optJSONArray("areas");
      int an = areas == null ? 0 : areas.length();
      s.areaKind = new int[an];
      s.areaPts = new float[an][];
      s.areaMinX = new float[an];
      s.areaMinY = new float[an];
      s.areaMaxX = new float[an];
      s.areaMaxY = new float[an];
      for (int i = 0; i < an; i++) {
        JSONObject a = areas.getJSONObject(i);
        s.areaKind[i] = areaKindIndex(a.optString("k", ""));
        s.areaPts[i] = parsePts(s, a.optJSONArray("p"));
        computeBounds(s.areaPts[i], s.areaMinX, s.areaMinY, s.areaMaxX, s.areaMaxY, i);
      }

      JSONArray pois = root.optJSONArray("pois");
      int pn = pois == null ? 0 : pois.length();
      s.poiName = new String[pn];
      s.poiX = new float[pn];
      s.poiY = new float[pn];
      for (int i = 0; i < pn; i++) {
        JSONObject p = pois.getJSONObject(i);
        s.poiName[i] = p.optString("n", "");
        s.poiX[i] = s.toX(p.optDouble("lon", 0));
        s.poiY[i] = s.toY(p.optDouble("lat", 0));
      }
      JSONArray alertClusters = root.optJSONArray("alert_clusters");
      if (alertClusters == null || alertClusters.length() == 0) {
        alertClusterLatLon = null;
        alertClusterCounts = null;
      } else {
        double[] latLon = new double[alertClusters.length() * 2];
        int[] counts = new int[alertClusters.length()];
        int accepted = 0;
        for (int i = 0; i < alertClusters.length(); i++) {
          JSONObject c = alertClusters.optJSONObject(i);
          if (c == null) {
            continue;
          }
          double lat = c.optDouble("lat", Double.NaN);
          double lon = c.optDouble("lon", Double.NaN);
          if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            continue;
          }
          latLon[accepted * 2] = lat;
          latLon[accepted * 2 + 1] = lon;
          counts[accepted] = Math.max(1, c.optInt("count", 1));
          accepted++;
        }
        if (accepted == 0) {
          alertClusterLatLon = null;
          alertClusterCounts = null;
        } else {
          alertClusterLatLon = Arrays.copyOf(latLon, accepted * 2);
          alertClusterCounts = Arrays.copyOf(counts, accepted);
        }
      }

      Scene previous = scene;
      scene = s;
      loadingHint = "";
      if (previous != null && !followDevice) {
        // Keep the same world point centered when a refetch swaps scene origin/zoom.
        double centerLat = previous.toLat(camY);
        double centerLon = previous.toLon(camX);
        camX = s.toX(centerLon);
        camY = s.toY(centerLat);
        if (zoomAnchorActive) {
          double anchorLat = previous.toLat(zoomAnchorWorldY);
          double anchorLon = previous.toLon(zoomAnchorWorldX);
          zoomAnchorWorldX = s.toX(anchorLon);
          zoomAnchorWorldY = s.toY(anchorLat);
        }
      } else if (previous == null || !followDevice) {
        // First scene: snap camera to scene center unless device fix exists.
        if (!hasDevice) {
          camX = 0f;
          camY = 0f;
        }
      }
      if (buildingSortKeys.length < bn) {
        buildingSortKeys = new long[bn];
      }
      Log.i(
          TAG,
          String.format(
              Locale.ROOT,
              "scene loaded zoom=%d radius=%.1f roads=%d buildings=%d areas=%d pois=%d clusters=%d",
              s.zoom,
              s.radiusM,
              rn,
              bn,
              an,
              pn,
              alertClusterCounts == null ? 0 : alertClusterCounts.length));
      postInvalidateOnAnimation();
    } catch (Exception ex) {
      Log.w(TAG, "scene parse failed: " + ex.getMessage(), ex);
    }
  }

  private static float[] parsePts(Scene s, JSONArray p) {
    if (p == null || p.length() < 2) {
      return new float[0];
    }
    int n = p.length() / 2;
    float[] out = new float[n * 2];
    for (int i = 0; i < n; i++) {
      out[i * 2] = s.toX(p.optDouble(i * 2 + 1, 0)); // lon -> x
      out[i * 2 + 1] = s.toY(p.optDouble(i * 2, 0)); // lat -> y
    }
    return out;
  }

  private static void computeBounds(
      float[] pts, float[] minX, float[] minY, float[] maxX, float[] maxY, int idx) {
    float lo = Float.MAX_VALUE;
    float loY = Float.MAX_VALUE;
    float hi = -Float.MAX_VALUE;
    float hiY = -Float.MAX_VALUE;
    for (int i = 0; i + 1 < pts.length; i += 2) {
      lo = Math.min(lo, pts[i]);
      hi = Math.max(hi, pts[i]);
      loY = Math.min(loY, pts[i + 1]);
      hiY = Math.max(hiY, pts[i + 1]);
    }
    minX[idx] = lo;
    minY[idx] = loY;
    maxX[idx] = hi;
    maxY[idx] = hiY;
  }

  private static int roadClassIndex(String c) {
    switch (c) {
      case "motorway":
        return CLASS_MOTORWAY;
      case "primary":
        return CLASS_PRIMARY;
      case "secondary":
        return CLASS_SECONDARY;
      case "tertiary":
        return CLASS_TERTIARY;
      case "service":
        return CLASS_SERVICE;
      case "path":
        return CLASS_PATH;
      case "rail":
        return CLASS_RAIL;
      default:
        return CLASS_RESIDENTIAL;
    }
  }

  private static int areaKindIndex(String k) {
    switch (k) {
      case "park":
        return AREA_PARK;
      case "water":
        return AREA_WATER;
      case "sand":
        return AREA_SAND;
      case "lot":
        return AREA_LOT;
      case "civic":
        return AREA_CIVIC;
      default:
        return AREA_LOT;
    }
  }

  /** Feed latest device fix; camera follows when follow mode is active. */
  public void updateDevice(double lat, double lon, Float headingDeg, Float speedMps) {
    deviceLat = lat;
    deviceLon = lon;
    hasDevice = true;
    if (headingDeg != null) {
      deviceHeadingDeg = headingDeg;
    }
    if (speedMps != null) {
      deviceSpeedMps = speedMps;
    }
    postInvalidateOnAnimation();
  }

  public void setRoute(List<double[]> latLonPoints) {
    if (latLonPoints == null || latLonPoints.isEmpty()) {
      routeLatLon = null;
      return;
    }
    double[] flat = new double[latLonPoints.size() * 2];
    int i = 0;
    for (double[] p : latLonPoints) {
      if (p != null && p.length >= 2) {
        flat[i * 2] = p[0];
        flat[i * 2 + 1] = p[1];
        i++;
      }
    }
    routeLatLon = i == latLonPoints.size() ? flat : Arrays.copyOf(flat, i * 2);
    postInvalidateOnAnimation();
  }

  public void setDestination(Double lat, Double lon) {
    destLat = lat;
    destLon = lon;
    postInvalidateOnAnimation();
  }

  public void recenter() {
    followDevice = true;
    lastTouchMs = 0L;
    postInvalidateOnAnimation();
  }

  /** Multiplies meters-per-dp by factor (>1 zooms out, <1 zooms in). */
  public void zoomBy(float factor) {
    if (factor > 0f) {
      setTargetZoom(clamp(targetMpp * factor, MIN_MPP, MAX_MPP), getWidth() / 2f, getHeight() / 2f);
    }
  }

  // ---- Input ----

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    scaleDetector.onTouchEvent(event);
    lastTouchMs = SystemClock.elapsedRealtime();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        lastX = event.getX();
        lastY = event.getY();
        long now = SystemClock.elapsedRealtime();
        if (now - lastTapMs < 280L) {
          cycleTilt();
          lastTapMs = 0L;
        } else {
          lastTapMs = now;
        }
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        if (event.getPointerCount() == 2) {
          twoFinger = true;
          lastAngle = angleBetween(event);
        }
        return true;
      case MotionEvent.ACTION_MOVE:
        if (twoFinger && event.getPointerCount() >= 2) {
          float angle = angleBetween(event);
          float delta = angle - lastAngle;
          if (delta > 180f) {
            delta -= 360f;
          }
          if (delta < -180f) {
            delta += 360f;
          }
          headingDeg = normalizeDeg(headingDeg - delta);
          lastAngle = angle;
          followDevice = false;
        } else if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
          float dx = event.getX() - lastX;
          float dy = event.getY() - lastY;
          panBy(dx, dy);
          lastX = event.getX();
          lastY = event.getY();
          followDevice = false;
        }
        postInvalidateOnAnimation();
        return true;
      case MotionEvent.ACTION_POINTER_UP:
        if (event.getPointerCount() <= 2) {
          twoFinger = false;
        }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        twoFinger = false;
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  private void cycleTilt() {
    if (tiltDeg > 55f) {
      tiltDeg = 30f;
    } else if (tiltDeg > 25f) {
      tiltDeg = 0f;
    } else {
      tiltDeg = 60f;
    }
  }

  private static float angleBetween(MotionEvent event) {
    float dx = event.getX(1) - event.getX(0);
    float dy = event.getY(1) - event.getY(0);
    return (float) Math.toDegrees(Math.atan2(dy, dx));
  }

  private void panBy(float dxPx, float dyPx) {
    float mppPx = mpp / density;
    double rad = Math.toRadians(headingDeg);
    double cosH = Math.cos(rad);
    double sinH = Math.sin(rad);
    double cosTilt = Math.max(0.35, Math.cos(Math.toRadians(tiltDeg)));
    double rx = -dxPx * mppPx;
    double ry = (dyPx * mppPx) / cosTilt;
    camX += (float) (rx * cosH + ry * sinH);
    camY += (float) (-rx * sinH + ry * cosH);
  }

  private void applyScaleAroundFocus(float scaleFactor, float focusX, float focusY) {
    if (scaleFactor <= 0f) {
      return;
    }
    float nextMpp = clamp(targetMpp / scaleFactor, MIN_MPP, MAX_MPP);
    setTargetZoom(nextMpp, focusX, focusY);
  }

  private void setTargetZoom(float nextMpp, float focusX, float focusY) {
    float clamped = clamp(nextMpp, MIN_MPP, MAX_MPP);
    if (Math.abs(clamped - targetMpp) < 0.0001f && Math.abs(clamped - mpp) < 0.0001f) {
      return;
    }
    targetMpp = clamped;
    lastZoomInputMs = SystemClock.elapsedRealtime();
    if (scene != null && getWidth() > 0 && getHeight() > 0) {
      float[] anchor = screenToWorld(focusX, focusY, mpp);
      zoomAnchorWorldX = anchor[0];
      zoomAnchorWorldY = anchor[1];
      zoomAnchorScreenX = focusX;
      zoomAnchorScreenY = focusY;
      zoomAnchorActive = true;
    } else {
      mpp = clamped;
      targetMpp = clamped;
      zoomAnchorActive = false;
    }
    postInvalidateOnAnimation();
  }

  private float[] screenToWorld(float screenX, float screenY, float mppForCalc) {
    float pxPerM = density / mppForCalc;
    double headingRad = Math.toRadians(headingDeg);
    float cosH = (float) Math.cos(headingRad);
    float sinH = (float) Math.sin(headingRad);
    float cosT = (float) Math.max(0.2, Math.cos(Math.toRadians(tiltDeg)));
    float sinT = (float) Math.sin(Math.toRadians(tiltDeg));
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f + (getHeight() * 0.22f * sinT);
    float rx = (screenX - cx) / pxPerM;
    float ry = (cy - screenY) / (cosT * pxPerM);
    float dx = rx * cosH + ry * sinH;
    float dy = -rx * sinH + ry * cosH;
    return new float[] {camX + dx, camY + dy};
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static double clampLat(double lat) {
    return Math.max(-WEB_MERCATOR_MAX_LAT, Math.min(WEB_MERCATOR_MAX_LAT, lat));
  }

  private static double lonToMercatorX(double lon) {
    return WEB_MERCATOR_EARTH_RADIUS_M * Math.toRadians(lon);
  }

  private static double latToMercatorY(double lat) {
    double clamped = clampLat(lat);
    double radians = Math.toRadians(clamped);
    return WEB_MERCATOR_EARTH_RADIUS_M * Math.log(Math.tan((Math.PI * 0.25) + (radians * 0.5)));
  }

  private static double mercatorXToLon(double x) {
    return Math.toDegrees(x / WEB_MERCATOR_EARTH_RADIUS_M);
  }

  private static double mercatorYToLat(double y) {
    return Math.toDegrees((2.0 * Math.atan(Math.exp(y / WEB_MERCATOR_EARTH_RADIUS_M))) - (Math.PI * 0.5));
  }

  private static float normalizeDeg(float deg) {
    float d = deg % 360f;
    if (d < 0f) {
      d += 360f;
    }
    return d;
  }

  // ---- Rendering ----

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long frameStart = SystemClock.elapsedRealtime();
    canvas.drawColor(COLOR_BG);
    Scene s = scene;
    int w = getWidth();
    int h = getHeight();
    if (s == null) {
      long now = SystemClock.elapsedRealtime();
      if (now - lastNoSceneLogMs >= NO_SCENE_LOG_INTERVAL_MS) {
        lastNoSceneLogMs = now;
        Log.d(TAG, "onDraw scene=null hint=" + loadingHint);
      }
      textPaint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("3D map loading\u2026", w / 2f, h / 2f, textPaint);
      String hint = loadingHint;
      if (!hint.isEmpty()) {
        canvas.drawText(hint, w / 2f, h / 2f + 18f * density, textPaint);
      }
      textPaint.setTextAlign(Paint.Align.LEFT);
      postInvalidateDelayed(NO_SCENE_RETRY_DRAW_MS);
      return;
    }

    stepCamera(s);

    float pxPerM = density / mpp;
    double rad = Math.toRadians(headingDeg);
    float cosH = (float) Math.cos(rad);
    float sinH = (float) Math.sin(rad);
    float cosT = (float) Math.cos(Math.toRadians(tiltDeg));
    float sinT = (float) Math.sin(Math.toRadians(tiltDeg));
    float cx = w / 2f;
    // Push the camera anchor toward the lower third when tilted (nav style).
    float cy = h / 2f + (h * 0.22f * sinT);
    float viewRadiusM = (float) (Math.hypot(w, h) * 0.62f) / pxPerM + 60f;

    // 1. Areas (batched per kind)
    for (int kind = 0; kind < AREA_COUNT; kind++) {
      workPath.rewind();
      boolean any = false;
      for (int i = 0; i < s.areaKind.length; i++) {
        if (s.areaKind[i] != kind
            || !boxVisible(s.areaMinX[i], s.areaMinY[i], s.areaMaxX[i], s.areaMaxY[i], viewRadiusM)) {
          continue;
        }
        appendPath(workPath, s.areaPts[i], 0f, cosH, sinH, cosT, sinT, pxPerM, cx, cy, true);
        any = true;
      }
      if (any) {
        fillPaint.setColor(AREA_FILL[kind]);
        canvas.drawPath(workPath, fillPaint);
      }
    }

    // 2. Roads (batched per class: one casing stroke + one fill stroke)
    boolean skipMinor = mpp > 8f;
    for (int clazz = 0; clazz < CLASS_COUNT; clazz++) {
      if (skipMinor && (clazz == CLASS_PATH || clazz == CLASS_SERVICE)) {
        continue;
      }
      workPath.rewind();
      boolean any = false;
      for (int i = 0; i < s.roadClass.length; i++) {
        if (s.roadClass[i] != clazz
            || !boxVisible(s.roadMinX[i], s.roadMinY[i], s.roadMaxX[i], s.roadMaxY[i], viewRadiusM)) {
          continue;
        }
        appendPath(workPath, s.roadPts[i], 0f, cosH, sinH, cosT, sinT, pxPerM, cx, cy, false);
        any = true;
      }
      if (!any) {
        continue;
      }
      float widthPx = Math.max(1.2f * density, CLASS_WIDTH_M[clazz] * pxPerM);
      boolean minor = clazz == CLASS_PATH || clazz == CLASS_RAIL;
      if (!minor) {
        strokePaint.setPathEffect(null);
        strokePaint.setColor(COLOR_CASING);
        strokePaint.setStrokeWidth(widthPx + Math.max(1.5f * density, widthPx * 0.28f));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(workPath, strokePaint);
      }
      strokePaint.setPathEffect(clazz == CLASS_RAIL ? railDash : null);
      strokePaint.setColor(CLASS_FILL[clazz]);
      strokePaint.setStrokeWidth(widthPx);
      canvas.drawPath(workPath, strokePaint);
      strokePaint.setPathEffect(null);
    }

    // 3. Buildings: cull, depth-sort (far first), extrude
    if (mpp <= BUILDING_SKIP_MPP && s.bldPts.length > 0) {
      int visible = 0;
      for (int i = 0; i < s.bldPts.length; i++) {
        float dx = s.bldCx[i] - camX;
        float dy = s.bldCy[i] - camY;
        if (Math.abs(dx) - s.bldRadius[i] > viewRadiusM
            || Math.abs(dy) - s.bldRadius[i] > viewRadiusM) {
          continue;
        }
        float depth = dx * sinH + dy * cosH; // forward distance
        int bits = Float.floatToIntBits(depth);
        bits ^= (bits >> 31) | 0x80000000; // make float bits sortable ascending
        buildingSortKeys[visible++] = ((long) bits << 32) | (i & 0xFFFFFFFFL);
      }
      Arrays.sort(buildingSortKeys, 0, visible);
      boolean drawWalls = sinT > 0.05f && mpp < 3.2f;
      float wallRadiusSq = Math.min(420f, viewRadiusM * 0.75f);
      wallRadiusSq *= wallRadiusSq;
      // Far first = descending depth = iterate sorted array backwards.
      for (int k = visible - 1; k >= 0; k--) {
        int i = (int) (buildingSortKeys[k] & 0xFFFFFFFFL);
        float[] pts = s.bldPts[i];
        float heightM = s.bldHeight[i];
        float ddx = s.bldCx[i] - camX;
        float ddy = s.bldCy[i] - camY;
        boolean wallsForThis = drawWalls && (ddx * ddx + ddy * ddy) < wallRadiusSq;
        if (wallsForThis) {
          int n = pts.length / 2;
          for (int e = 0; e < n; e++) {
            int j = (e + 1) % n;
            float x1 = sx(pts[e * 2], pts[e * 2 + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
            float y1 = sy(pts[e * 2], pts[e * 2 + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
            float x2 = sx(pts[j * 2], pts[j * 2 + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
            float y2 = sy(pts[j * 2], pts[j * 2 + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
            float lift = heightM * sinT * pxPerM;
            wallPath.rewind();
            wallPath.moveTo(x1, y1);
            wallPath.lineTo(x2, y2);
            wallPath.lineTo(x2, y2 - lift);
            wallPath.lineTo(x1, y1 - lift);
            wallPath.close();
            fillPaint.setColor(COLOR_WALL);
            canvas.drawPath(wallPath, fillPaint);
          }
        }
        workPath.rewind();
        appendPath(workPath, pts, wallsForThis ? heightM : 0f, cosH, sinH, cosT, sinT, pxPerM, cx, cy, true);
        fillPaint.setColor(COLOR_ROOF);
        canvas.drawPath(workPath, fillPaint);
        strokePaint.setColor(COLOR_ROOF_LINE);
        strokePaint.setStrokeWidth(1f);
        canvas.drawPath(workPath, strokePaint);
      }
    }

    // 4. Route
    double[] route = routeLatLon;
    if (route != null && route.length >= 4) {
      workPath.rewind();
      for (int i = 0; i + 1 < route.length; i += 2) {
        float x = s.toX(route[i + 1]);
        float y = s.toY(route[i]);
        float px = sx(x, y, 0.4f, cosH, sinH, cosT, sinT, pxPerM, cx);
        float py = sy(x, y, 0.4f, cosH, sinH, cosT, sinT, pxPerM, cy);
        if (i == 0) {
          workPath.moveTo(px, py);
        } else {
          workPath.lineTo(px, py);
        }
      }
      strokePaint.setColor(COLOR_ROUTE_CASING);
      strokePaint.setStrokeWidth(9f * density);
      canvas.drawPath(workPath, strokePaint);
      strokePaint.setColor(COLOR_ROUTE);
      strokePaint.setStrokeWidth(6f * density);
      canvas.drawPath(workPath, strokePaint);
    }

    // 5. Destination marker
    Double dLat = destLat;
    Double dLon = destLon;
    if (dLat != null && dLon != null) {
      float x = s.toX(dLon);
      float y = s.toY(dLat);
      float px = sx(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
      fillPaint.setColor(COLOR_DEST);
      canvas.drawCircle(px, py, 7f * density, fillPaint);
      strokePaint.setColor(Color.WHITE);
      strokePaint.setStrokeWidth(2f * density);
      canvas.drawCircle(px, py, 7f * density, strokePaint);
    }

    // 6. Device marker with heading wedge
    if (hasDevice) {
      float x = s.toX(deviceLon);
      float y = s.toY(deviceLat);
      float px = sx(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
      float r = 9f * density;
      double wedge = Math.toRadians(deviceHeadingDeg - headingDeg);
      float tipX = px + (float) Math.sin(wedge) * r * 2.0f;
      float tipY = py - (float) Math.cos(wedge) * r * 2.0f;
      wallPath.rewind();
      wallPath.moveTo(tipX, tipY);
      wallPath.lineTo(
          px + (float) Math.sin(wedge + 2.5) * r, py - (float) Math.cos(wedge + 2.5) * r);
      wallPath.lineTo(
          px + (float) Math.sin(wedge - 2.5) * r, py - (float) Math.cos(wedge - 2.5) * r);
      wallPath.close();
      fillPaint.setColor(0x662B6BE6);
      canvas.drawPath(wallPath, fillPaint);
      fillPaint.setColor(COLOR_DEVICE);
      canvas.drawCircle(px, py, 7f * density, fillPaint);
      strokePaint.setColor(Color.WHITE);
      strokePaint.setStrokeWidth(2.5f * density);
      canvas.drawCircle(px, py, 7f * density, strokePaint);
    }

    // 7. Labels: road names up close, place names (cities/towns) zoomed out
    if (mpp <= LABEL_SKIP_MPP) {
      drawLabels(canvas, s, cosH, sinH, cosT, sinT, pxPerM, cx, cy, w, h);
    } else if (mpp >= PLACE_LABEL_MIN_MPP) {
      drawPlaceLabels(canvas, s, cosH, sinH, cosT, sinT, pxPerM, cx, cy, w, h);
      drawAlertClusters(canvas, s, cosH, sinH, cosT, sinT, pxPerM, cx, cy, w, h);
    }

    // 8. Attribution
    textPaint.setTextSize(9f * density);
    String attribution = "\u00a9 OpenStreetMap contributors";
    float attrW = textPaint.measureText(attribution);
    canvas.drawText(attribution, w - attrW - 6f * density, h - 6f * density, textHaloPaint);
    canvas.drawText(attribution, w - attrW - 6f * density, h - 6f * density, textPaint);
    textPaint.setTextSize(11f * density);

    maybeRequestRefetch(s, viewRadiusM);
    logFps(frameStart);
    if (shouldAnimate()) {
      postInvalidateOnAnimation();
    }
  }

  private void drawPlaceLabels(
      Canvas canvas,
      Scene s,
      float cosH,
      float sinH,
      float cosT,
      float sinT,
      float pxPerM,
      float cx,
      float cy,
      int w,
      int h) {
    List<String> drawn = new ArrayList<>(16);
    for (int i = 0; i < s.poiName.length && drawn.size() < 16; i++) {
      String name = s.poiName[i];
      if (name.isEmpty() || drawn.contains(name)) {
        continue;
      }
      float px = sx(s.poiX[i], s.poiY[i], 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(s.poiX[i], s.poiY[i], 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
      if (px < 20 || px > w - 70 || py < 30 || py > h - 30) {
        continue;
      }
      drawn.add(name);
      canvas.drawText(name, px, py, textHaloPaint);
      canvas.drawText(name, px, py, textPaint);
    }
  }

  private void drawLabels(
      Canvas canvas,
      Scene s,
      float cosH,
      float sinH,
      float cosT,
      float sinT,
      float pxPerM,
      float cx,
      float cy,
      int w,
      int h) {
    List<String> drawn = new ArrayList<>(12);
    for (int i = 0; i < s.roadName.length && drawn.size() < 12; i++) {
      String name = s.roadName[i];
      if (name.isEmpty() || drawn.contains(name) || s.roadClass[i] <= CLASS_RAIL) {
        continue;
      }
      float[] pts = s.roadPts[i];
      if (pts.length < 4) {
        continue;
      }
      int mid = (pts.length / 4) * 2;
      float px = sx(pts[mid], pts[mid + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(pts[mid], pts[mid + 1], 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
      if (px < 30 || px > w - 90 || py < 40 || py > h - 40) {
        continue;
      }
      drawn.add(name);
      canvas.drawText(name, px, py, textHaloPaint);
      canvas.drawText(name, px, py, textPaint);
    }
  }

  private void drawAlertClusters(
      Canvas canvas,
      Scene s,
      float cosH,
      float sinH,
      float cosT,
      float sinT,
      float pxPerM,
      float cx,
      float cy,
      int w,
      int h) {
    double[] clusterLatLon = alertClusterLatLon;
    int[] clusterCounts = alertClusterCounts;
    if (clusterLatLon == null || clusterCounts == null || clusterCounts.length == 0) {
      return;
    }
    for (int i = 0; i < clusterCounts.length; i++) {
      float x = s.toX(clusterLatLon[i * 2 + 1]);
      float y = s.toY(clusterLatLon[i * 2]);
      float px = sx(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(x, y, 0f, cosH, sinH, cosT, sinT, pxPerM, cy);
      if (px < 12 || px > w - 12 || py < 12 || py > h - 12) {
        continue;
      }
      float r = Math.min(26f * density, (8f + (float) Math.sqrt(clusterCounts[i]) * 2.5f) * density);
      fillPaint.setColor(0xB0E67E22);
      canvas.drawCircle(px, py, r, fillPaint);
      strokePaint.setColor(0xFFE67E22);
      strokePaint.setStrokeWidth(2f * density);
      canvas.drawCircle(px, py, r, strokePaint);
      String label = String.valueOf(clusterCounts[i]);
      float textW = textPaint.measureText(label);
      canvas.drawText(label, px - textW / 2f, py + 4f * density, textHaloPaint);
      canvas.drawText(label, px - textW / 2f, py + 4f * density, textPaint);
    }
  }

  private void stepCamera(Scene s) {
    long now = SystemClock.elapsedRealtime();
    if (!followDevice && lastTouchMs > 0 && (now - lastTouchMs) > FOLLOW_RESUME_AFTER_TOUCH_MS) {
      followDevice = true;
    }
    if (followDevice && hasDevice) {
      float targetX = s.toX(deviceLon);
      float targetY = s.toY(deviceLat);
      camX += (targetX - camX) * 0.18f;
      camY += (targetY - camY) * 0.18f;
      if (deviceSpeedMps > 1.2f) {
        float diff = deviceHeadingDeg - headingDeg;
        while (diff > 180f) {
          diff -= 360f;
        }
        while (diff < -180f) {
          diff += 360f;
        }
        headingDeg = normalizeDeg(headingDeg + diff * 0.12f);
      }
    }
    stepZoomSmoothing();
  }

  private void stepZoomSmoothing() {
    float delta = targetMpp - mpp;
    if (Math.abs(delta) < ZOOM_SETTLE_EPSILON) {
      mpp = targetMpp;
      zoomVelocity = 0f;
      if (zoomAnchorActive && SystemClock.elapsedRealtime() - lastZoomInputMs > 350L) {
        zoomAnchorActive = false;
      }
      return;
    }
    float desiredStep = delta * ZOOM_SMOOTH_FACTOR;
    zoomVelocity = zoomVelocity * (1f - ZOOM_VELOCITY_BLEND) + desiredStep * ZOOM_VELOCITY_BLEND;
    float previousMpp = mpp;
    mpp = clamp(mpp + zoomVelocity, MIN_MPP, MAX_MPP);
    if (zoomAnchorActive && getWidth() > 0 && getHeight() > 0) {
      float[] projected = screenToWorld(zoomAnchorScreenX, zoomAnchorScreenY, mpp);
      camX += zoomAnchorWorldX - projected[0];
      camY += zoomAnchorWorldY - projected[1];
    }
    if (Math.abs(targetMpp - mpp) < ZOOM_SETTLE_EPSILON || Math.abs(previousMpp - mpp) < 0.00005f) {
      mpp = targetMpp;
      zoomVelocity = 0f;
      if (SystemClock.elapsedRealtime() - lastZoomInputMs > 200L) {
        zoomAnchorActive = false;
      }
    }
  }

  private boolean boxVisible(float minX, float minY, float maxX, float maxY, float viewRadiusM) {
    return maxX >= camX - viewRadiusM
        && minX <= camX + viewRadiusM
        && maxY >= camY - viewRadiusM
        && minY <= camY + viewRadiusM;
  }

  private float sx(
      float x, float y, float heightM,
      float cosH, float sinH, float cosT, float sinT, float pxPerM, float cx) {
    float dx = x - camX;
    float dy = y - camY;
    float rx = dx * cosH - dy * sinH;
    return cx + rx * pxPerM;
  }

  private float sy(
      float x, float y, float heightM,
      float cosH, float sinH, float cosT, float sinT, float pxPerM, float cy) {
    float dx = x - camX;
    float dy = y - camY;
    float ry = dx * sinH + dy * cosH;
    return cy - ry * cosT * pxPerM - heightM * sinT * pxPerM;
  }

  private void appendPath(
      Path path, float[] pts, float heightM,
      float cosH, float sinH, float cosT, float sinT,
      float pxPerM, float cx, float cy, boolean close) {
    int n = pts.length / 2;
    if (n < 2) {
      return;
    }
    for (int i = 0; i < n; i++) {
      float px = sx(pts[i * 2], pts[i * 2 + 1], heightM, cosH, sinH, cosT, sinT, pxPerM, cx);
      float py = sy(pts[i * 2], pts[i * 2 + 1], heightM, cosH, sinH, cosT, sinT, pxPerM, cy);
      if (i == 0) {
        path.moveTo(px, py);
      } else {
        path.lineTo(px, py);
      }
    }
    if (close) {
      path.close();
    }
  }

  private void maybeRequestRefetch(Scene s, float viewRadiusM) {
    RefetchListener listener = refetchListener;
    if (listener == null) {
      return;
    }
    if (isZoomAnimating()) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastRefetchMs < REFETCH_MIN_INTERVAL_MS) {
      return;
    }
    if (now - lastScaleGestureMs < REFETCH_AFTER_SCALE_COOLDOWN_MS) {
      return;
    }
    double dist = Math.hypot(camX, camY);
    boolean panned = dist > s.radiusM * REFETCH_EDGE_FRACTION;
    // Zoomed out beyond the scene: fetch a wider, lower-resolution layer.
    boolean zoomedOut = viewRadiusM > s.radiusM * REFETCH_ZOOM_OUT_FACTOR && s.radiusM < MAX_FETCH_RADIUS_M * 0.9;
    // Zoomed back in on a coarse scene: fetch a finer layer.
    boolean zoomedIn = viewRadiusM < s.radiusM * REFETCH_ZOOM_IN_FACTOR && s.zoom < 13;
    if (panned || zoomedOut || zoomedIn) {
      lastRefetchMs = now;
      double radius = Math.max(REFETCH_MIN_RADIUS_M, Math.min(MAX_FETCH_RADIUS_M, viewRadiusM * 1.5));
      String reason;
      if (panned) {
        reason = "panned";
      } else if (zoomedOut) {
        reason = "zoomed_out";
      } else {
        reason = "zoomed_in";
      }
      Log.i(
          TAG,
          String.format(
              Locale.ROOT,
              "refetch reason=%s centerLat=%.6f centerLon=%.6f radius=%.1f viewRadius=%.1f sceneRadius=%.1f zoom=%d",
              reason,
              s.toLat(camY),
              s.toLon(camX),
              radius,
              viewRadiusM,
              s.radiusM,
              s.zoom));
      listener.onRefetchNeeded(s.toLat(camY), s.toLon(camX), radius);
    }
  }

  private boolean isZoomAnimating() {
    return Math.abs(targetMpp - mpp) > ZOOM_SETTLE_EPSILON || zoomAnchorActive;
  }

  private boolean shouldAnimate() {
    if (isZoomAnimating()) {
      return true;
    }
    if (!followDevice) {
      return false;
    }
    return hasDevice;
  }

  private void logFps(long frameStart) {
    frameCount++;
    long now = SystemClock.elapsedRealtime();
    if (fpsWindowStartMs == 0L) {
      fpsWindowStartMs = now;
    }
    long window = now - fpsWindowStartMs;
    if (window >= 2000L) {
      float fps = frameCount * 1000f / window;
      Log.d(
          TAG,
          String.format(
              Locale.ROOT,
              "fps=%.1f frame_ms=%d mpp=%.2f tilt=%.0f follow=%b",
              fps,
              now - frameStart,
              mpp,
              tiltDeg,
              followDevice));
      frameCount = 0;
      fpsWindowStartMs = now;
    }
  }
}
