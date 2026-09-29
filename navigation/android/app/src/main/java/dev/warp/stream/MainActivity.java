package dev.warp.stream;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationListenerCompat;
import dev.warp.stream.mesh.MeshEnroller;
import dev.warp.stream.mesh.MeshPrefs;
import dev.warp.stream.mesh.MeshProfile;
import dev.warp.stream.mesh.ScoutMeshVpnService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";
  private static final float MOTION_FORCE_THRESHOLD_MS2 = 1.8f;
  private static final float MOTION_IDLE_THRESHOLD_MS2 = 0.35f;
  private static final long MOTION_FORCE_HOLD_MS = 4000L;
  private static final long MOTION_IDLE_RELEASE_MS = 12000L;
  private static final int LOCATION_PERMISSION_REQUEST_CODE = 4102;
  private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 4103;
  private static final int SCOUT_MICROPHONE_CAPTURE_REQUEST_CODE = 4104;
private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 4105;
  private static final int MESH_VPN_PERMISSION_REQUEST_CODE = 4106;
  private static final long LOCATION_UPDATE_INTERVAL_MS = 2000L;
  private static final float LOCATION_MIN_DISTANCE_M = 3f;
  private static final long DEVICE_GPS_POST_INTERVAL_MS = 3000L;
  private static final long SERVER_ROUTE_REFRESH_MS = 5000L;
  private static final long ERROR_REPORT_POLL_INTERVAL_MS = 12000L;
  private static final int ERROR_REPORT_SEEN_MAX_IDS = 180;
  private static final long POPUP_REPEAT_SUPPRESS_MS = 30000L;
  private static final long POPUP_AUTO_HIDE_MS = 12000L;
  private static final long PIPELINE_ALERT_SPEAK_COOLDOWN_MS = 9000L;
  private static final long ALERT_CLUSTER_CACHE_MS = 20000L;
  private static final int PIPELINE_ALERT_SPEAK_MIN_RATING = 4;
  private static final String ROUTE_ALERT_NOTIFICATION_CHANNEL_ID = "scout_route_alerts";
  private static final int ROUTE_ALERT_NOTIFICATION_CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_HIGH;
  private static final long ROUTE_ALERT_NOTIFICATION_COOLDOWN_MS = 7000L;
  private static final int MAX_LOCAL_CALL_CONTEXT_ITEMS = 20;
  private static final int MAX_BROADCASTIFY_CONTEXT_ITEMS = 48;
  private static final int MAX_HAZARD_CONTEXT_ITEMS = 24;
  private static final int MAX_CONTEXT_PAYLOAD_CHARS = 9000;
  private static final double DEFAULT_MAP_LAT = 37.7749;
  private static final double DEFAULT_MAP_LON = -122.4194;
  private static final double DEFAULT_COUNTRY_SCENE_RADIUS_M = 1_250_000.0;
  private static final double MIN_SCENE_RADIUS_M = 120_000.0;
  private static final String ASSISTANT_MODEL_PREFERENCE = "scout-core1.0.7";
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
  private static final String STATE_MAP3D_ENABLED = "state_map3d_enabled";
  private static final String STATE_MAP_LAT = "state_map_lat";
  private static final String STATE_MAP_LON = "state_map_lon";
  private static final String EXTRA_FOCUS_ROUTE = "focus_route";
  private static final String ROUTE_ACTION_CHANGE_ROUTE = "Change route";
  private static final String ROUTE_ACTION_ADD_STOP = "Add stop";
  private static final Pattern COORDINATE_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");
  private static final Pattern DESTINATION_INTENT_PATTERN =
      Pattern.compile(
          "(?i)\\b(?:navigate|route|take me|drive)\\s+(?:to|towards)\\s+(.+)$|\\bset\\s+destination\\s+(?:to\\s+)?(.+)$");
  private static final Pattern DESTINATION_RESPONSE_PATTERN =
      Pattern.compile("(?i)\\bdestination\\s*[:\\-]\\s*(.+)$");
  private static final Pattern INTERNET_QUERY_PATTERN =
      Pattern.compile(
          "(?i)\\b(?:search( the)? web|look up|lookup|internet|online|latest news|what is|who is|when is|where is)\\b");
  private static final Pattern SCOUT_SELF_QUERY_PATTERN =
      Pattern.compile("(?i)\\b(?:ask|query|prompt)\\b.*\\b(?:yourself|itself|self)\\b");
  private static final Pattern WEB_FALLBACK_HTML_STRIP = Pattern.compile("<[^>]+>");
  /** Mention-extractor tokens that are useless as geocode queries (directions, road furniture). */
  private static final Set<String> NON_ROUTABLE_MENTIONS =
      new HashSet<>(
          Arrays.asList(
              "northbound",
              "southbound",
              "eastbound",
              "westbound",
              "shoulder",
              "on-ramp",
              "off-ramp",
              "interchange"));
  private static final Pattern DIRECTIONAL_TOKEN_PATTERN =
      Pattern.compile("(?i)\\b(northbound|southbound|eastbound|westbound)\\b");
  private static final boolean ENABLE_DEV_CONTROLS = BuildConfig.ENABLE_DEV_CONTROLS;

  private EditText baseUrlInput;
  private AutoCompleteTextView destinationInput;
  private EditText assistantPromptInput;
  private AutoCompleteTextView routeActionInput;
  private TextView statusText;
  private TextView drivingModeText;
  private TextView mapTargetText;
  private TextView outputText;
  private Button menuBtn;
  private Button errorReportBtn;
  private TextView stackManageStatusText;
  private View controlPanel;
  private View routeActivePanel;
  private View alertManagementSubmenu;
  private Button alertManagementToggleBtn;
  private Map3dView map3dView;
private Button mapModeBtn;
  private Button meshModeBtn;
  private Button tailscaleModeBtn;
  private View zoomControls;
  private boolean map3dEnabled = true;
  private volatile boolean sceneFetchInFlight = false;
  private volatile boolean sceneRetryScheduled = false;
  private volatile long lastSceneFetchMs = 0L;
  private volatile double lastSceneLat = Double.NaN;
  private volatile double lastSceneLon = Double.NaN;
  private volatile double lastSceneRadiusM = 700.0;
  private LinearLayout locationPopup;
  private TextView popupTitle;
  private TextView popupLocationText;
  private TextView popupIntelText;
  private TextView popupTranscriptText;
  private TextView errorReportStatusText;
  private AudioVisualizerView popupVisualizer;
  private View scoutSpeakingOverlay;
  private TextView scoutSpeakingTitle;
  private TextView scoutSpeakingBody;
  private AudioVisualizerView scoutSpeakingVisualizer;
  private Button scoutSpeakingCloseBtn;
  private Button popupRouteBtn;
  private volatile String pendingPopupQuery = null;
  private String lastPopupMentionKey = "";
  private long lastPopupShownMs = 0L;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable popupAutoHideRunnable = this::hideLocationPopup;
  private final PrivacyHttpClient client =
      new PrivacyHttpClient(this, new OkHttpClient.Builder().build());
  // SSE stream can be quiet for long stretches (pipeline emits ~every 12s or
  // slower); the default 10s read timeout was killing the connection, so the
  // stream client reads forever and streamSse() reconnects on failure.
  private final PrivacyHttpClient sseClient =
      new PrivacyHttpClient(this, new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build());
  private final AddressCatalogRouter addressCatalogRouter = new AddressCatalogRouter(client);
  private volatile boolean running = false;
  private Call streamCall;
  private SensorManager sensorManager;
  private Sensor accelerometer;
  private LocationManager locationManager;
  private boolean locationTrackingActive;
  private long locationRegistrationGeneration;
  private final float[] gravity = new float[] {0f, 0f, 0f};
  private long motionAboveSinceMs = 0L;
  private long motionBelowSinceMs = 0L;
  private boolean forceDrivingMode = false;
  private long lastMotionUiUpdateMs = 0L;
  private float lastMotionMagnitude = 0f;
  private long lastDeviceGpsPostMs = 0L;
  private Double lastMapLat = null;
  private Double lastMapLon = null;
  private Double lastDeviceLat = null;
  private Double lastDeviceLon = null;
  private Float lastDeviceAccuracyM = null;
  private Float lastDeviceSpeedMps = null;
  private Float lastDeviceHeadingDeg = null;
  private long lastDevicePrivacyRevision = -1L;
  private SharedPreferences.OnSharedPreferenceChangeListener privacyListener;
  private boolean serverRouteRequestInFlight = false;
  private long lastServerRouteFetchMs = 0L;
  private String lastServerRouteFingerprint = "";
  private String cachedClientId = null;
  private volatile String clientPullToken = "";
  private final String streamSessionId = Long.toHexString(System.currentTimeMillis());
  private final List<double[]> currentRoutePoints = new ArrayList<>();
  private final List<AddressCatalogRouter.AddressCandidate> destinationSuggestionCandidates =
      new ArrayList<>();
  private final List<String> destinationSuggestionLabels = new ArrayList<>();
  private ArrayAdapter<String> destinationSuggestionAdapter;
  private final Runnable destinationSuggestRunnable = this::requestDestinationSuggestions;
  private final Runnable errorReportPollRunnable = this::pollErrorReportsLoop;
  private long destinationSuggestGeneration = 0L;
  private String pendingSuggestQuery = "";
  private AddressCatalogRouter.AddressCandidate selectedDestinationSuggestion = null;
  private long lastErrorReportPollMs = 0L;
  private long lastSeenErrorReportCreatedAtMs = 0L;
  private final Set<String> seenErrorReportIdSet = new HashSet<>();
  private final Deque<String> seenErrorReportIds = new ArrayDeque<>();
  private boolean trackingConsentDialogShowing = false;
  private boolean trackingDisabledNoticeLogged = false;
  private TextToSpeech scoutTts;
  private volatile boolean scoutTtsReady = false;
  private volatile boolean scoutQueryInFlight = false;
  private String pendingScoutExpansionPrompt = null;
  private boolean pendingMicCaptureAfterPermission = false;
  private volatile boolean scoutTtsInitInProgress = false;
  private String pendingScoutSpokenText = null;
  private boolean autoAudioNotificationPermissionWarned = false;
  private volatile String cachedAssistantEndpointPath = "/api/platform/assistant/chat";
  private String lastSpokenPipelineAlertKey = "";
  private long lastSpokenPipelineAlertAtMs = 0L;
  private String lastRouteAlertNotificationKey = "";
  private long lastRouteAlertNotificationAtMs = 0L;
  private volatile String cachedAlertClusterSummary = "";
  private volatile long cachedAlertClusterAtMs = 0L;
  private final Deque<String> recentLocalCallContexts = new ArrayDeque<>();
  private final Deque<String> recentBroadcastifyContexts = new ArrayDeque<>();
  private final Deque<String> recentHazardApiContexts = new ArrayDeque<>();
  private ArrayAdapter<String> routeActionAdapter;
  private RecyclerView routeStopsRecycler;
  private TextView routeStopsEmptyText;
  private RouteStopAdapter routeStopAdapter;
  private final List<RouteStop> routeStops = new ArrayList<>();
  private boolean alertManagementExpanded = false;
  private boolean routeSessionActive = false;
  private final OnBackPressedCallback menuBackCallback = new OnBackPressedCallback(false) {
    @Override
    public void handleOnBackPressed() {
      setControlPanelVisible(false);
    }
  };
  private final BroadcastReceiver meshStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!ScoutMeshVpnService.ACTION_STATE.equals(intent.getAction())) {
        return;
      }
      updateMeshModeButton();
      String detail = intent.getStringExtra(ScoutMeshVpnService.EXTRA_DETAIL);
      if (!TextUtils.isEmpty(detail)) {
        appendLine("MESH", detail);
      }
      if (ScoutMeshVpnService.STATE_ERROR.equals(intent.getStringExtra(ScoutMeshVpnService.EXTRA_STATE))) {
        setStatus("Mesh connection failed");
      }
    }
  };

  private interface AlertClusterSummaryCallback {
    void onReady(String summary);
  }

  private static final class RouteStop {
    private final String label;
    private final double lat;
    private final double lon;

    private RouteStop(String label, double lat, double lon) {
      this.label = label;
      this.lat = lat;
      this.lon = lon;
    }
  }
  private boolean hasUsableDeviceFix() {
    return AppPrefs.isLocationSharingAllowed(this)
        && lastDevicePrivacyRevision == AppPrefs.privacyRevision(this)
        && lastDeviceLat != null && lastDeviceLon != null;
  }

  private void clearPrivateLocationState() {
    unregisterLocationTracking();
    stopStreaming("privacy settings changed");
    client.dispatcher().cancelAll();
    sseClient.dispatcher().cancelAll();
    clientPullToken = "";
    lastDeviceLat = null;
    lastDeviceLon = null;
    lastDeviceAccuracyM = null;
    lastDeviceSpeedMps = null;
    lastDeviceHeadingDeg = null;
    lastDevicePrivacyRevision = -1L;
    lastSceneLat = Double.NaN;
    lastSceneLon = Double.NaN;
    sceneFetchInFlight = false;
    serverRouteRequestInFlight = false;
    scoutQueryInFlight = false;
    pendingScoutExpansionPrompt = null;
    sceneRetryScheduled = false;
    lastServerRouteFingerprint = "";
    cachedAlertClusterSummary = "";
    cachedAlertClusterAtMs = 0L;
    synchronized (currentRoutePoints) {
      currentRoutePoints.clear();
    }
    synchronized (recentLocalCallContexts) {
      recentLocalCallContexts.clear();
    }
    synchronized (recentBroadcastifyContexts) {
      recentBroadcastifyContexts.clear();
    }
    synchronized (recentHazardApiContexts) {
      recentHazardApiContexts.clear();
    }
    destinationSuggestGeneration++;
    uiHandler.removeCallbacks(destinationSuggestRunnable);
    updateDestinationSuggestions(new ArrayList<>(), 0L);
    map3dView.clearPrivateLocation();
    updateMapTargetUi();
  }

  private final class RouteStopViewHolder extends RecyclerView.ViewHolder {
    private final TextView titleText;
    private final TextView subtitleText;

    private RouteStopViewHolder(View itemView) {
      super(itemView);
      titleText = itemView.findViewById(android.R.id.text1);
      subtitleText = itemView.findViewById(android.R.id.text2);
    }
  }

  private final class RouteStopAdapter extends RecyclerView.Adapter<RouteStopViewHolder> {
    @Override
    public RouteStopViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
      View rowView =
          getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
      return new RouteStopViewHolder(rowView);
    }

    @Override
    public void onBindViewHolder(RouteStopViewHolder holder, int position) {
      RouteStop stop = routeStops.get(position);
      holder.titleText.setText((position + 1) + ". " + stop.label);
      holder.subtitleText.setText(
          String.format(Locale.ROOT, "lat %.5f  lon %.5f", stop.lat, stop.lon));
      holder.itemView.setOnClickListener(
          v -> {
            if (position == 0) {
              return;
            }
            Collections.swap(routeStops, position, 0);
            notifyItemMoved(position, 0);
            updateRouteStopsUi();
            applyPrimaryStopWithoutRouteOptions("ROUTE", "set active stop: " + stop.label);
          });
    }

    @Override
    public int getItemCount() {
      return routeStops.size();
    }
  }

  private final SensorEventListener accelListener =
      new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
          processAccelerometerSample(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
          // no-op
        }
      };

  private LocationListenerCompat locationListener;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    WindowInsetsSupport.apply(this);
    getOnBackPressedDispatcher().addCallback(this, menuBackCallback);
    controlPanel = findViewById(R.id.controlPanel);
    baseUrlInput = controlPanel.findViewById(R.id.baseUrlInput);
    destinationInput = findViewById(R.id.destinationInput);
    assistantPromptInput = findViewById(R.id.assistantPromptInput);
    destinationSuggestionAdapter =
        new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, destinationSuggestionLabels);
    destinationInput.setAdapter(destinationSuggestionAdapter);
    destinationInput.setThreshold(1);
    destinationInput.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (position >= 0 && position < destinationSuggestionCandidates.size()) {
            selectedDestinationSuggestion = destinationSuggestionCandidates.get(position);
          }
        });
    destinationInput.setOnEditorActionListener(
        (v, actionId, event) -> {
          if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
            searchDestination();
            return true;
          }
          return false;
        });
    destinationInput.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            String text = s == null ? "" : s.toString().trim();
            if (selectedDestinationSuggestion != null
                && !text.equalsIgnoreCase(selectedDestinationSuggestion.displayName)) {
              selectedDestinationSuggestion = null;
            }
            scheduleDestinationSuggestions(text);
          }

          @Override
          public void afterTextChanged(Editable s) {}
        });
    statusText = findViewById(R.id.statusText);
    drivingModeText = controlPanel.findViewById(R.id.drivingModeText);
    mapTargetText = controlPanel.findViewById(R.id.mapTargetText);
    outputText = controlPanel.findViewById(R.id.outputText);
    TextView osmOdbNoticeText = controlPanel.findViewById(R.id.osmOdbNoticeText);
    map3dView = findViewById(R.id.map3dView);
mapModeBtn = findViewById(R.id.mapModeBtn);
    meshModeBtn = controlPanel.findViewById(R.id.meshModeBtn);
    tailscaleModeBtn = controlPanel.findViewById(R.id.tailscaleModeBtn);
    zoomControls = findViewById(R.id.zoomControls);
    Button zoomInBtn = findViewById(R.id.zoomInBtn);
    Button zoomOutBtn = findViewById(R.id.zoomOutBtn);
    zoomInBtn.setOnClickListener(v -> map3dView.zoomBy(0.5f));
    zoomOutBtn.setOnClickListener(v -> map3dView.zoomBy(2.0f));
    menuBtn = findViewById(R.id.menuBtn);
    routeActivePanel = controlPanel.findViewById(R.id.routeActivePanel);
    alertManagementSubmenu = controlPanel.findViewById(R.id.alertManagementSubmenu);
    alertManagementToggleBtn = controlPanel.findViewById(R.id.alertManagementToggleBtn);
    routeActionInput = controlPanel.findViewById(R.id.routeActionInput);
    routeStopsRecycler = controlPanel.findViewById(R.id.routeStopsRecycler);
    routeStopsEmptyText = controlPanel.findViewById(R.id.routeStopsEmptyText);
    if (routeStopsRecycler != null) {
      routeStopsRecycler.setLayoutManager(new LinearLayoutManager(this));
      routeStopAdapter = new RouteStopAdapter();
      routeStopsRecycler.setAdapter(routeStopAdapter);
      routeStopsRecycler.setHasFixedSize(false);
      ItemTouchHelper itemTouchHelper =
          new ItemTouchHelper(
              new ItemTouchHelper.SimpleCallback(
                  ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(
                    RecyclerView recyclerView,
                    RecyclerView.ViewHolder viewHolder,
                    RecyclerView.ViewHolder target) {
                  int from = viewHolder.getBindingAdapterPosition();
                  int to = target.getBindingAdapterPosition();
                  if (from < 0 || to < 0 || from >= routeStops.size() || to >= routeStops.size()) {
                    return false;
                  }
                  Collections.swap(routeStops, from, to);
                  if (routeStopAdapter != null) {
                    routeStopAdapter.notifyItemMoved(from, to);
                  }
                  return true;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {}

                @Override
                public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                  super.clearView(recyclerView, viewHolder);
                  updateRouteStopsUi();
                  applyPrimaryStopWithoutRouteOptions("ROUTE", "stops reordered");
                }
              });
      itemTouchHelper.attachToRecyclerView(routeStopsRecycler);
    }
    updateRouteStopsUi();
    locationPopup = findViewById(R.id.locationPopup);
    popupTitle = findViewById(R.id.popupTitle);
    popupLocationText = findViewById(R.id.popupLocationText);
    popupIntelText = findViewById(R.id.popupIntelText);
    popupTranscriptText = findViewById(R.id.popupTranscriptText);
    popupVisualizer = findViewById(R.id.popupVisualizer);
    scoutSpeakingOverlay = findViewById(R.id.scoutSpeakingOverlay);
    scoutSpeakingTitle = findViewById(R.id.scoutSpeakingTitle);
    scoutSpeakingBody = findViewById(R.id.scoutSpeakingBody);
    scoutSpeakingVisualizer = findViewById(R.id.scoutSpeakingVisualizer);
    scoutSpeakingCloseBtn = findViewById(R.id.scoutSpeakingCloseBtn);
    Button connectBtn = controlPanel.findViewById(R.id.connectBtn);
    Button disconnectBtn = controlPanel.findViewById(R.id.disconnectBtn);
    Button clearLogBtn = controlPanel.findViewById(R.id.clearLogBtn);
    Button openMapsBtn = controlPanel.findViewById(R.id.openMapsBtn);
    errorReportBtn = controlPanel.findViewById(R.id.errorReportBtn);
    errorReportStatusText = controlPanel.findViewById(R.id.errorReportStatusText);
    Button autoNotificationPermissionBtn = controlPanel.findViewById(R.id.autoNotificationPermissionBtn);
    Button routeActionApplyBtn = controlPanel.findViewById(R.id.routeActionApplyBtn);
    Button routeClearBtn = controlPanel.findViewById(R.id.routeClearBtn);
    stackManageStatusText = controlPanel.findViewById(R.id.stackManageStatusText);
    Button stackStatusBtn = controlPanel.findViewById(R.id.stackStatusBtn);
    Button stackHealthBtn = controlPanel.findViewById(R.id.stackHealthBtn);
    Button stackStartBtn = controlPanel.findViewById(R.id.stackStartBtn);
    Button stackRestartBtn = controlPanel.findViewById(R.id.stackRestartBtn);
    Button stackStopBtn = controlPanel.findViewById(R.id.stackStopBtn);
    Button drawRouteBtn = findViewById(R.id.drawRouteBtn);
    Button searchBtn = findViewById(R.id.searchBtn);
    Button assistantAskBtn = findViewById(R.id.assistantAskBtn);
    popupRouteBtn = findViewById(R.id.popupRouteBtn);
    View popupDismissBtn = findViewById(R.id.popupDismissBtn);
    if (osmOdbNoticeText != null) {
      osmOdbNoticeText.setMovementMethod(LinkMovementMethod.getInstance());
    }


    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    baseUrlInput.setText(AppPrefs.baseUrl(this));
    new Thread(
            () -> {
              String resolved = AppPrefs.resolveReachableBaseUrl(this);
              AppPrefs.saveBaseUrl(this, resolved);
              postPrivateUi(() -> baseUrlInput.setText(resolved));
            })
        .start();

mapModeBtn.setOnClickListener(v -> toggleMapMode());
    if (meshModeBtn != null) {
      updateMeshModeButton();
      meshModeBtn.setOnClickListener(v -> onMeshModeClicked());
    }
    if (tailscaleModeBtn != null) {
      updateTailscaleModeButton();
      tailscaleModeBtn.setOnClickListener(v -> setTailscaleModeEnabled(!AppPrefs.preferTailscale(this)));
    }
    map3dView.setRefetchListener((lat, lon, radiusM) -> fetchMapScene(lat, lon, radiusM, false));
    connectBtn.setOnClickListener(v -> startStreaming());
    disconnectBtn.setOnClickListener(v -> stopStreaming("disconnected"));
    clearLogBtn.setOnClickListener(v -> outputText.setText(getString(R.string.stream_placeholder)));
    openMapsBtn.setOnClickListener(v -> openLatestMapTarget());
    drawRouteBtn.setOnClickListener(v -> renderRouteOnMap(true));
    menuBtn.setOnClickListener(v -> setControlPanelVisible(controlPanel.getVisibility() != View.VISIBLE));
    searchBtn.setOnClickListener(v -> searchDestination());
    assistantAskBtn.setOnClickListener(v -> {
      if (assistantPromptInput != null && !TextUtils.isEmpty(assistantPromptInput.getText())) {
        submitAssistantChatFromInput();
      } else {
        startScoutMicrophoneCapture();
      }
    });
    if (assistantPromptInput != null) {
      assistantPromptInput.setOnEditorActionListener(
          (v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
              submitAssistantChatFromInput();
              return true;
            }
            return false;
          });
    }
    if (errorReportBtn != null) {
      errorReportBtn.setText(getString(R.string.error_report_button));
      errorReportBtn.setOnClickListener(v -> showErrorReportDialog());
    }
    if (autoNotificationPermissionBtn != null) {
      autoNotificationPermissionBtn.setOnClickListener(v -> runAndroidAutoNotificationPermissionCheck());
    }
    if (routeActionInput != null) {
      routeActionAdapter =
          new ArrayAdapter<>(
              this,
              android.R.layout.simple_dropdown_item_1line,
              Arrays.asList(
                  ROUTE_ACTION_CHANGE_ROUTE,
                  ROUTE_ACTION_CHANGE_ROUTE + ": ",
                  ROUTE_ACTION_ADD_STOP,
                  ROUTE_ACTION_ADD_STOP + ": "));
      routeActionInput.setAdapter(routeActionAdapter);
      routeActionInput.setThreshold(0);
      routeActionInput.setOnClickListener(v -> routeActionInput.showDropDown());
      routeActionInput.setOnFocusChangeListener(
          (v, hasFocus) -> {
            if (hasFocus) {
              routeActionInput.showDropDown();
            }
          });
    }
    if (routeActionApplyBtn != null) {
      routeActionApplyBtn.setOnClickListener(v -> applyRouteActionFromPanel());
    }
    if (routeClearBtn != null) {
      routeClearBtn.setOnClickListener(v -> clearActiveRouteSelection(true));
    }
    if (alertManagementToggleBtn != null) {
      alertManagementToggleBtn.setOnClickListener(v -> toggleAlertManagementSubmenu());
    }
    updateErrorReportStatus(getString(R.string.error_report_status_idle));
    updateStackManageStatus(getString(R.string.stack_status_idle));
    if (stackStatusBtn != null) {
      stackStatusBtn.setOnClickListener(v -> runStackManageAction("status"));
    }
    if (stackHealthBtn != null) {
      stackHealthBtn.setOnClickListener(v -> runStackManageAction("health"));
    }
    if (stackStartBtn != null) {
      stackStartBtn.setOnClickListener(v -> runStackManageAction("start"));
    }
    if (stackRestartBtn != null) {
      stackRestartBtn.setOnClickListener(v -> runStackManageAction("restart"));
    }
    if (stackStopBtn != null) {
      stackStopBtn.setOnClickListener(v -> runStackManageAction("stop"));
    }
    popupRouteBtn.setOnClickListener(v -> routeToPopupLocation());
    popupDismissBtn.setOnClickListener(v -> hideLocationPopup());
    if (scoutSpeakingCloseBtn != null) {
      scoutSpeakingCloseBtn.setOnClickListener(
          v -> {
            stopScoutSpeech();
            hideScoutSpeakingOverlay();
          });
    }
    if (!ENABLE_DEV_CONTROLS) {
      statusText.setOnLongClickListener(
          v -> {
            showEndpointOverrideDialog();
            return true;
          });
    }
    controlPanel.findViewById(R.id.serverSettingsBtn).setOnClickListener(v -> showEndpointOverrideDialog());
    controlPanel.findViewById(R.id.privacySettingsBtn).setOnClickListener(v -> {
      AppPrefs.setTrackingConsent(this, AppPrefs.isActiveTrackingEnabled(this), false);
      maybeShowTrackingConsentDialog();
    });
    TextView buildStatus = findViewById(R.id.buildStatusText);
    buildStatus.setText(getString(R.string.preview_status));
    privacyListener = AppPrefs.registerPrivacyListener(this, this::clearPrivateLocationState);
    appendLine("MAP", "vector map engine active (OSM road geometry as line data)");
    applyAppModeUi();
    ensureRouteAlertNotificationChannel();
    initScoutTts();

    restoreUiState(savedInstanceState);
    applyMapMode(true);
    restoreRouteSelectionState(getIntent());

    setStatus("idle");
    updateDrivingModeUi(0f);
    updateMapTargetUi();
    renderRouteOnMap(true);
  }

  private void executeAssistantChatRequest(
      String base, String payload, String queryText, boolean allowDiscoveryRetry) {
    String endpointPath = normalizeEndpointPath(cachedAssistantEndpointPath);
    Request request =
        new Request.Builder()
            .url(base + endpointPath)
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("SCOUT", "assistant request failed: " + e.getMessage());
                finishScoutQueryCycle();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (response.code() == 404 && allowDiscoveryRetry) {
                    discoverAssistantEndpointAndRetry(base, payload, queryText);
                    return;
                  }
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("SCOUT", "assistant unavailable (HTTP " + response.code() + ")");
                    hideScoutSpeakingOverlay();
                    finishScoutQueryCycle();
                    return;
                  }
                  JSONObject payloadJson = new JSONObject(response.body().string());
                  JSONObject llmResult = payloadJson.optJSONObject("llm_result");
                  JSONObject chat = llmResult != null ? llmResult.optJSONObject("chat") : null;
                  String assistantText = "";
                  if (chat != null) {
                    assistantText = chat.optString("response", "").trim();
                  }
                  if (assistantText.isEmpty() && llmResult != null) {
                    assistantText = llmResult.optString("response", "").trim();
                  }
                  if (assistantText.isEmpty()) {
                    appendLine("SCOUT", "assistant returned no response; checking local-area web results");
                    requestInternetLookup(queryText);
                    hideScoutSpeakingOverlay();
                    return;
                  }
                  updateScoutProfileFromAssistantResponse(assistantText);
                  appendLine("SCOUT", assistantText);
                  maybeApplyDestinationIntent(assistantText, "SCOUT");
                  speakScoutResponse(assistantText);
                } catch (Exception e) {
                  appendLine("SCOUT", "assistant parse error: " + e.getMessage());
                  hideScoutSpeakingOverlay();
                } finally {
                  finishScoutQueryCycle();
                }
              }
            });
  }

  private void fetchDirectAlertClusterSummary(
      String base, double lat, double lon, AlertClusterSummaryCallback callback) {
    String directUrl =
        base
            + "/api/platform/alerts/clusters?lat="
            + String.format(Locale.ROOT, "%.6f", lat)
            + "&lon="
            + String.format(Locale.ROOT, "%.6f", lon)
            + "&radius_m=6000";
    Request request = new Request.Builder().url(directUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                pushSharedContextEntry("hazards_api unavailable");
                callback.onReady(cachedAlertClusterSummary);
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    pushSharedContextEntry("hazards_api unavailable");
                    callback.onReady(cachedAlertClusterSummary);
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  JSONArray clusters = payload.optJSONArray("clusters");
                  updateTrafficHazardContextFromClusterPayload(payload, clusters);
                  String summary = buildMapRankedAlertClusterSummary(clusters);
                  if (!TextUtils.isEmpty(summary)) {
                    cachedAlertClusterSummary = summary;
                    cachedAlertClusterAtMs = SystemClock.elapsedRealtime();
                  }
                  callback.onReady(summary);
                } catch (Exception e) {
                  callback.onReady(cachedAlertClusterSummary);
                }
              }
            });
  }

  private String buildLocalizedInternetQuery(String queryText) {
    String baseQuery = queryText == null ? "" : queryText.trim();
    if (baseQuery.isEmpty()) {
      return "";
    }
    String destinationLabel =
        destinationInput != null ? destinationInput.getText().toString().trim() : "";
    Double areaLat = hasUsableDeviceFix() ? lastDeviceLat : lastMapLat;
    Double areaLon = hasUsableDeviceFix() ? lastDeviceLon : lastMapLon;
    StringBuilder builder = new StringBuilder(baseQuery);
    if (!destinationLabel.isEmpty()) {
      builder.append(" near ").append(destinationLabel);
    }
    if (areaLat != null && areaLon != null) {
      builder
          .append(" local area ")
          .append(String.format(Locale.ROOT, "%.4f", areaLat))
          .append(",")
          .append(String.format(Locale.ROOT, "%.4f", areaLon));
    } else {
      builder.append(" local area near me");
    }
    return builder.toString();
  }

  private void discoverAssistantEndpointAndRetry(String base, String payload, String queryText) {
    Request bootstrapRequest = new Request.Builder().url(base + "/api/mobile/bootstrap").build();
    client.newCall(bootstrapRequest)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("SCOUT", "assistant path discovery failed: " + e.getMessage());
                finishScoutQueryCycle();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("SCOUT", "assistant path discovery unavailable (HTTP " + response.code() + ")");
                    finishScoutQueryCycle();
                    return;
                  }
                  JSONObject bootstrap = new JSONObject(response.body().string());
                  JSONObject endpoints = bootstrap.optJSONObject("endpoints");
                  String discovered =
                      endpoints != null ? endpoints.optString("assistant_chat", "").trim() : "";
                  if (discovered.isEmpty()) {
                    appendLine("SCOUT", "assistant endpoint missing in bootstrap");
                    finishScoutQueryCycle();
                    return;
                  }
                  cachedAssistantEndpointPath = normalizeEndpointPath(discovered);
                  appendLine("SCOUT", "using endpoint " + cachedAssistantEndpointPath);
                  executeAssistantChatRequest(base, payload, queryText, false);
                } catch (Exception e) {
                  appendLine("SCOUT", "assistant path parse error: " + e.getMessage());
                  finishScoutQueryCycle();
                }
              }
            });
  }

  private String normalizeEndpointPath(String rawPath) {
    if (TextUtils.isEmpty(rawPath)) {
      return "/api/platform/assistant/chat";
    }
    String path = rawPath.trim();
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    return path;
  }

  private void finishScoutQueryCycle() {
    scoutQueryInFlight = false;
    String queued = consumeScoutExpansionPrompt();
    if (!TextUtils.isEmpty(queued)) {
      postPrivateUi(() -> requestAssistantChat(queued, queued));
    }
  }

  private void submitAssistantChatFromInput() {
    if (assistantPromptInput == null) {
      return;
    }
    String prompt = assistantPromptInput.getText() == null ? "" : assistantPromptInput.getText().toString().trim();
    if (prompt.isEmpty()) {
      appendLine("SCOUT", "enter a question first");
      return;
    }
    if (isScoutSelfQuery(prompt)) {
      appendLine("SCOUT", "self-query blocked");
      return;
    }
    if (scoutQueryInFlight) {
      queueScoutExpansionPrompt(prompt);
      appendLine("SCOUT", "queued expansion prompt");
      return;
    }
    maybeApplyDestinationIntent(prompt, "SCOUT");
    if (shouldUseInternetLookup(prompt)) {
      requestInternetLookup(prompt);
    }
    requestAssistantChat(prompt, prompt);
  }

  private void requestAssistantChat(String queryText, String spoofedTranscript) {
    long privacyRevision = AppPrefs.privacyRevision(this);
    if (scoutQueryInFlight) {
      appendLine("SCOUT", "query already in progress");
      return;
    }
    scoutQueryInFlight = true;
    showScoutSpeakingOverlay(getString(R.string.scout_speaking_placeholder));
    String base = normalizedBaseUrl();
    if (base == null) {
      scoutQueryInFlight = false;
      hideScoutSpeakingOverlay();
      setStatus("invalid URL");
      return;
    }
    String destinationLabel = destinationInput != null ? destinationInput.getText().toString().trim() : "";
    double originLat = hasUsableDeviceFix() ? lastDeviceLat : (lastMapLat != null ? lastMapLat : DEFAULT_MAP_LAT);
    double originLon = hasUsableDeviceFix() ? lastDeviceLon : (lastMapLon != null ? lastMapLon : DEFAULT_MAP_LON);
    double destLat = lastMapLat != null ? lastMapLat : originLat;
    double destLon = lastMapLon != null ? lastMapLon : originLon;
    String routeSummary =
        "origin="
            + String.format(Locale.ROOT, "%.6f,%.6f", originLat, originLon)
            + " destination="
            + String.format(Locale.ROOT, "%.6f,%.6f", destLat, destLon)
            + (destinationLabel.isEmpty() ? "" : (" label=" + destinationLabel));
    fetchAlertClusterSummary(
        base,
        originLat,
        originLon,
        destLat,
        destLon,
        alertClusterSummary -> {
          if (privacyRevision != AppPrefs.privacyRevision(this) || isDestroyed()) {
            finishScoutQueryCycle();
            return;
          }
          String unifiedLocalContext = buildLocalCallContextSummary();
          String broadcastifyStreamContext = buildBroadcastifyStreamContextSummary();
          String hazardApiStreamContext = buildHazardApiStreamContextSummary();
          JSONObject payloadJson =
              buildAssistantPayload(
                  queryText,
                  spoofedTranscript,
                  routeSummary,
                  destinationLabel,
                  originLat,
                  originLon,
                  destLat,
                  destLon,
                  alertClusterSummary,
                  unifiedLocalContext,
                  broadcastifyStreamContext,
                  hazardApiStreamContext);
          String payload = payloadJson.toString();
          appendLine("SCOUT", "querying local assistant…");
          appendLine("SCOUT", "target " + base + normalizeEndpointPath(cachedAssistantEndpointPath));
          executeAssistantChatRequest(base, payload, queryText, true);
        });
  }

  private JSONObject buildAssistantPayload(
      String queryText,
      String spoofedTranscript,
      String routeSummary,
      String destinationLabel,
      double originLat,
      double originLon,
      double destLat,
      double destLon,
      String alertClusterSummary,
      String localCallContext,
      String broadcastifyStreamContext,
      String hazardApiStreamContext) {
    JSONObject payload = new JSONObject();
    try {
      payload.put("query", queryText == null ? "" : queryText);
      payload.put("transcript", spoofedTranscript == null ? "" : spoofedTranscript);
      payload.put("route_summary", routeSummary == null ? "" : routeSummary);
      payload.put("destination_label", destinationLabel == null ? "" : destinationLabel);
      payload.put("origin_lat", originLat);
      payload.put("origin_lon", originLon);
      payload.put("dest_lat", destLat);
      payload.put("dest_lon", destLon);
      payload.put("model_preference", ASSISTANT_MODEL_PREFERENCE);
      payload.put(
          "alert_clusters_summary",
          TextUtils.isEmpty(alertClusterSummary)
              ? "no_alert_clusters_available"
              : alertClusterSummary);
      payload.put(
          "local_call_context",
          TextUtils.isEmpty(localCallContext)
              ? "no_recent_local_calls_observed"
              : localCallContext);
      payload.put(
          "broadcastify_stream_context",
          TextUtils.isEmpty(broadcastifyStreamContext)
              ? "no_recent_broadcastify_stream_context"
              : broadcastifyStreamContext);
      payload.put(
          "hazard_api_stream_context",
          TextUtils.isEmpty(hazardApiStreamContext)
              ? "no_recent_hazard_api_stream_context"
              : hazardApiStreamContext);
      payload.put(
          "memory_hint",
          buildAndPersistScoutMemoryHint(
              queryText, localCallContext, broadcastifyStreamContext, hazardApiStreamContext));
      Log.i(
          TAG,
          "assistant context clusters="
              + (TextUtils.isEmpty(alertClusterSummary) ? 0 : 1)
              + " local_calls="
              + (TextUtils.isEmpty(localCallContext) ? 0 : 1)
              + " broadcastify_context="
              + (TextUtils.isEmpty(broadcastifyStreamContext) ? 0 : 1)
              + " hazard_context="
              + (TextUtils.isEmpty(hazardApiStreamContext) ? 0 : 1));
    } catch (Exception e) {
      appendLine("SCOUT", "payload build warning: " + e.getMessage());
    }
    return payload;
  }

  private void fetchAlertClusterSummary(
      String base,
      double originLat,
      double originLon,
      double destLat,
      double destLon,
      AlertClusterSummaryCallback callback) {
    long now = SystemClock.elapsedRealtime();
    String cached = cachedAlertClusterSummary;
    if (!TextUtils.isEmpty(cached) && (now - cachedAlertClusterAtMs) < ALERT_CLUSTER_CACHE_MS) {
      callback.onReady(cached);
      return;
    }
    String url =
        base
            + "/api/platform/route/options?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destLon);
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                pushSharedContextEntry("hazards_api unavailable");
                fetchDirectAlertClusterSummary(base, originLat, originLon, callback);
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    fetchDirectAlertClusterSummary(base, originLat, originLon, callback);
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  updateTrafficHazardContextFromRouteOptions(payload);
                  JSONObject alertClusters = payload.optJSONObject("alert_clusters");
                  JSONArray clusters = alertClusters != null ? alertClusters.optJSONArray("clusters") : null;
                  String summary = buildMapRankedAlertClusterSummary(clusters);
                  if (TextUtils.isEmpty(summary)) {
                    fetchDirectAlertClusterSummary(base, originLat, originLon, callback);
                    return;
                  }
                  cachedAlertClusterSummary = summary;
                  cachedAlertClusterAtMs = SystemClock.elapsedRealtime();
                  callback.onReady(summary);
                } catch (Exception e) {
                  fetchDirectAlertClusterSummary(base, originLat, originLon, callback);
                }
              }
            });
  }

  private String buildMapRankedAlertClusterSummary(JSONArray clusters) {
    if (clusters == null || clusters.length() == 0) {
      return "";
    }
    List<String> ranked = new ArrayList<>();
    int limit = Math.min(6, clusters.length());
    for (int i = 0; i < limit; i++) {
      JSONObject cluster = clusters.optJSONObject(i);
      if (cluster == null) {
        continue;
      }
      int rank = i + 1;
      int count = cluster.optInt("count", 0);
      JSONArray alerts = cluster.optJSONArray("alerts");
      String headline = "";
      if (alerts != null && alerts.length() > 0) {
        JSONObject first = alerts.optJSONObject(0);
        if (first != null) {
          headline = first.optString("alert", "").trim();
          if (headline.isEmpty()) {
            headline = first.optString("transcript", "").trim();
          }
        }
      }
      if (headline.length() > 64) {
        headline = headline.substring(0, 61) + "...";
      }
      String line = "rank " + rank + " count=" + count;
      if (!headline.isEmpty()) {
        line += " " + headline;
      }
      ranked.add(line);
    }
    return TextUtils.join(" | ", ranked);
  }

  private synchronized String buildLocalCallContextSummary() {
    if (recentLocalCallContexts.isEmpty()) {
      return "";
    }
    return summarizeContextDeque(recentLocalCallContexts);
  }

  private synchronized String buildBroadcastifyStreamContextSummary() {
    if (recentBroadcastifyContexts.isEmpty()) {
      return "";
    }
    return summarizeContextDeque(recentBroadcastifyContexts);
  }

  private synchronized String buildHazardApiStreamContextSummary() {
    if (recentHazardApiContexts.isEmpty()) {
      return "";
    }
    return summarizeContextDeque(recentHazardApiContexts);
  }

  private synchronized String summarizeContextDeque(Deque<String> source) {
    String joined = TextUtils.join(" | ", source);
    if (joined.length() <= MAX_CONTEXT_PAYLOAD_CHARS) {
      return joined;
    }
    return joined.substring(0, MAX_CONTEXT_PAYLOAD_CHARS);
  }

  private void updateTrafficHazardContextFromRouteOptions(JSONObject payload) {
    if (payload == null) {
      return;
    }
    JSONObject alertClusters = payload.optJSONObject("alert_clusters");
    JSONArray clusters = alertClusters != null ? alertClusters.optJSONArray("clusters") : null;
    int clusterCount = clusters != null ? clusters.length() : 0;
    String routeStatus = payload.optString("status", "").trim();
    String clusterStatus = alertClusters != null ? alertClusters.optString("status", "").trim() : "";
    JSONArray alternatives = payload.optJSONArray("alternatives");
    int routeAlternatives = alternatives != null ? alternatives.length() : 0;
    JSONObject hazards = payload.optJSONObject("waze_hazards");
    JSONObject wazeRoute = payload.optJSONObject("waze_route");
    String hazardStatus = hazards != null ? hazards.optString("status", "").trim() : "";
    String provider = hazards != null ? hazards.optString("provider", "").trim() : "";
    String routeMode = wazeRoute != null ? wazeRoute.optString("mode", "").trim() : "";
    JSONArray hazardItems =
        hazards != null ? hazards.optJSONArray("hazards") : null;
    if (hazardItems == null && hazards != null) {
      hazardItems = hazards.optJSONArray("items");
    }
    int hazardCount = hazardItems != null ? hazardItems.length() : 0;
    StringBuilder entry = new StringBuilder("traffic_api");
    if (!TextUtils.isEmpty(routeStatus)) {
      entry.append(" status=").append(routeStatus);
    }
    if (!TextUtils.isEmpty(clusterStatus)) {
      entry.append(" clusters_status=").append(clusterStatus);
    }
    entry.append(" clusters=").append(clusterCount);
    entry.append(" routes=").append(routeAlternatives);
    if (!TextUtils.isEmpty(provider)) {
      entry.append(" ").append(provider);
    }
    if (!TextUtils.isEmpty(hazardStatus)) {
      entry.append(" hazards_status=").append(hazardStatus);
    }
    if (hazardCount > 0) {
      entry.append(" hazards=").append(hazardCount);
    }
    if (!TextUtils.isEmpty(routeMode)) {
      entry.append(" route=").append(routeMode);
    }
    pushSharedContextEntry(entry.toString());
    pushHazardApiContextEntry(entry.toString());
  }

  private void updateTrafficHazardContextFromClusterPayload(JSONObject payload, JSONArray clusters) {
    int clusterCount = clusters != null ? clusters.length() : 0;
    String status = payload != null ? payload.optString("status", "").trim() : "";
    String summary =
        "hazards_api clusters="
            + clusterCount
            + (TextUtils.isEmpty(status) ? "" : (" status=" + status));
    pushSharedContextEntry(summary);
    pushHazardApiContextEntry(summary);
  }

  private synchronized void pushSharedContextEntry(String entry) {
    if (TextUtils.isEmpty(entry)) {
      return;
    }
    String normalized = entry.trim();
    if (normalized.isEmpty()) {
      return;
    }
    recentLocalCallContexts.remove(normalized);
    recentLocalCallContexts.addFirst(normalized);
    while (recentLocalCallContexts.size() > MAX_LOCAL_CALL_CONTEXT_ITEMS) {
      recentLocalCallContexts.removeLast();
    }
  }

  private synchronized void pushBroadcastifyContextEntry(String entry) {
    if (TextUtils.isEmpty(entry)) {
      return;
    }
    String normalized = entry.trim();
    if (normalized.isEmpty()) {
      return;
    }
    recentBroadcastifyContexts.remove(normalized);
    recentBroadcastifyContexts.addFirst(normalized);
    while (recentBroadcastifyContexts.size() > MAX_BROADCASTIFY_CONTEXT_ITEMS) {
      recentBroadcastifyContexts.removeLast();
    }
  }

  private synchronized void pushHazardApiContextEntry(String entry) {
    if (TextUtils.isEmpty(entry)) {
      return;
    }
    String normalized = entry.trim();
    if (normalized.isEmpty()) {
      return;
    }
    recentHazardApiContexts.remove(normalized);
    recentHazardApiContexts.addFirst(normalized);
    while (recentHazardApiContexts.size() > MAX_HAZARD_CONTEXT_ITEMS) {
      recentHazardApiContexts.removeLast();
    }
  }

  private void captureBroadcastifyStreamContext(JSONObject eventJson, String eventType, String kind, String text) {
    if (eventJson == null) {
      return;
    }
    String lowerType = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
    if (!(lowerType.startsWith("chunk_")
        || "alert_triggered".equals(lowerType)
        || lowerType.contains("broadcast")
        || lowerType.contains("channel_switch")
        || lowerType.contains("capture_failed"))) {
      return;
    }
    String ts = eventJson.optString("ts", "").trim();
    String transcript = eventJson.optString("transcript", "").trim();
    String alert = eventJson.optString("alert", "").trim();
    double rms = eventJson.optDouble("rms", 0.0);
    int rating = extractLegacyCallRating(eventJson);
    StringBuilder entry = new StringBuilder();
    if (!TextUtils.isEmpty(ts)) {
      entry.append(ts).append(" ");
    }
    entry.append(lowerType);
    if (!TextUtils.isEmpty(kind)) {
      entry.append("/").append(kind.toLowerCase(Locale.ROOT));
    }
    if (rating > 0) {
      entry.append(" r").append(rating);
    }
    entry.append(" rms=").append(String.format(Locale.ROOT, "%.3f", rms));
    String headline = !TextUtils.isEmpty(alert) ? alert : (!TextUtils.isEmpty(transcript) ? transcript : text);
    if (!TextUtils.isEmpty(headline)) {
      if (headline.length() > 180) {
        headline = headline.substring(0, 180);
      }
      entry.append(" ").append(headline);
    }
    pushBroadcastifyContextEntry(entry.toString());
  }

  private synchronized void updateLocalCallContext(JSONObject intel, JSONObject eventJson) {
    if (intel == null && eventJson == null) {
      return;
    }
    List<String> callTypes = new ArrayList<>();
    if (intel != null) {
      collectMentions(intel.optJSONArray("call_types"), callTypes);
    }
    if (callTypes.isEmpty() && eventJson != null) {
      collectMentions(eventJson.optJSONArray("call_types"), callTypes);
    }
    String calls = callTypes.isEmpty() ? "unknown_call" : TextUtils.join("/", callTypes);
    int rating = extractLegacyCallRating(eventJson != null ? eventJson : intel);
    String priority = intel != null ? intel.optString("priority", "").trim() : "";
    String summary =
        calls
            + (rating > 0 ? (" r" + rating) : "")
            + (TextUtils.isEmpty(priority) ? "" : (" p=" + priority));
    if (summary.equals("unknown_call")) {
      return;
    }
    pushSharedContextEntry(summary);
  }

  private JSONObject buildAndPersistScoutMemoryHint(
      String queryText, String localCallContext, String broadcastifyStreamContext, String hazardApiStreamContext) {
    ScoutProfileSketch sketch = ScoutProfileSketch.fromJson(AppPrefs.scoutProfileSketch(this));
    sketch.observeUserPrompt(queryText);
    String destinationLabel = destinationInput != null ? destinationInput.getText().toString().trim() : "";
    if (!TextUtils.isEmpty(destinationLabel)) {
      sketch.observeUserPrompt("destination " + destinationLabel);
    }
    sketch.observePlatformContext(localCallContext, broadcastifyStreamContext, hazardApiStreamContext);
    AppPrefs.saveScoutProfileSketch(this, sketch.toJson());
    JSONObject memoryHint = sketch.toMemoryHintJson();
    JSONArray tags = memoryHint.optJSONArray("tags");
    int tagCount = tags != null ? tags.length() : 0;
    appendLine("SCOUT", "model " + ASSISTANT_MODEL_PREFERENCE + " memory tags=" + tagCount);
    Log.i(TAG, "assistant payload model=" + ASSISTANT_MODEL_PREFERENCE + " memory_tags=" + tagCount);
    return memoryHint;
  }

  private void updateScoutProfileFromAssistantResponse(String assistantText) {
    try {
      ScoutProfileSketch sketch = ScoutProfileSketch.fromJson(AppPrefs.scoutProfileSketch(this));
      sketch.observeAssistantResponse(assistantText);
      AppPrefs.saveScoutProfileSketch(this, sketch.toJson());
    } catch (Exception ignored) {
      // best effort personalization update
    }
  }

  private synchronized void queueScoutExpansionPrompt(String prompt) {
    pendingScoutExpansionPrompt = prompt;
  }

  private synchronized String consumeScoutExpansionPrompt() {
    String queued = pendingScoutExpansionPrompt;
    pendingScoutExpansionPrompt = null;
    return queued;
  }

  private synchronized void queuePendingScoutSpokenText(String responseText) {
    pendingScoutSpokenText = responseText;
  }

  private synchronized String consumePendingScoutSpokenText() {
    String queued = pendingScoutSpokenText;
    pendingScoutSpokenText = null;
    return queued;
  }

  private void initScoutTts() {
    if (scoutTtsInitInProgress) {
      return;
    }
    scoutTtsInitInProgress = true;
    scoutTts =
        new TextToSpeech(
            getApplicationContext(),
            status -> {
              scoutTtsInitInProgress = false;
              if (status != TextToSpeech.SUCCESS || scoutTts == null) {
                scoutTtsReady = false;
                appendLine("SCOUT", "voice output unavailable");
                hideScoutSpeakingOverlay();
                return;
              }
              int languageStatus = scoutTts.setLanguage(Locale.US);
              scoutTtsReady =
                  languageStatus != TextToSpeech.LANG_MISSING_DATA
                      && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
              if (scoutTtsReady) {
                scoutTts.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
              } else {
                appendLine("SCOUT", "voice language not supported");
                hideScoutSpeakingOverlay();
              }
              scoutTts.setOnUtteranceProgressListener(
                  new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                      postPrivateUi(() -> showScoutSpeakingOverlay(null));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                      postPrivateUi(MainActivity.this::hideScoutSpeakingOverlay);
                    }

                    @Override
                    public void onError(String utteranceId) {
                      postPrivateUi(
                          () -> {
                            appendLine("SCOUT", "voice playback error");
                            hideScoutSpeakingOverlay();
                          });
                    }
                  });
              if (scoutTtsReady) {
                String queuedText = consumePendingScoutSpokenText();
                if (!TextUtils.isEmpty(queuedText)) {
                  postPrivateUi(() -> speakScoutResponse(queuedText));
                }
              }
            });
  }

  private void showScoutSpeakingOverlay(String responseText) {
    if (scoutSpeakingOverlay == null || scoutSpeakingVisualizer == null) {
      return;
    }
    scoutSpeakingOverlay.bringToFront();
    if (scoutSpeakingTitle != null) {
      scoutSpeakingTitle.setText(getString(R.string.scout_speaking_title));
    }
    if (scoutSpeakingBody != null) {
      scoutSpeakingBody.setText(
          TextUtils.isEmpty(responseText)
              ? getString(R.string.scout_speaking_placeholder)
              : responseText);
    }
    scoutSpeakingVisualizer.setAmplitude(0.9f);
    scoutSpeakingVisualizer.start();
    scoutSpeakingOverlay.setVisibility(View.VISIBLE);
  }

  private void hideScoutSpeakingOverlay() {
    if (scoutSpeakingOverlay == null || scoutSpeakingVisualizer == null) {
      return;
    }
    scoutSpeakingVisualizer.stop();
    scoutSpeakingOverlay.setVisibility(View.GONE);
  }

  private void stopScoutSpeech() {
    synchronized (this) {
      pendingScoutSpokenText = null;
    }
    if (scoutTts != null) {
      scoutTts.stop();
    }
  }

  private void speakScoutResponse(String responseText) {
    if (TextUtils.isEmpty(responseText)) {
      return;
    }
    showScoutSpeakingOverlay(responseText);
    if (!scoutTtsReady || scoutTts == null || scoutTtsInitInProgress) {
      queuePendingScoutSpokenText(responseText);
      if (!scoutTtsInitInProgress) {
        initScoutTts();
      }
      appendLine("SCOUT", "vocalizer initializing…");
      return;
    }
    noteAutoAudioNotificationPermissionState();
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    if (audioManager != null) {
      audioManager.requestAudioFocus(
          null,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
    }
    String utteranceId = "scout-" + SystemClock.elapsedRealtime();
    int result = scoutTts.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    if (result != TextToSpeech.SUCCESS) {
      appendLine("SCOUT", "voice playback unavailable");
      hideScoutSpeakingOverlay();
    }
  }

  private boolean shouldUseInternetLookup(String queryText) {
    if (TextUtils.isEmpty(queryText)) {
      return false;
    }
    return INTERNET_QUERY_PATTERN.matcher(queryText).find();
  }

  private void requestInternetLookup(String queryText) {
    String localizedQuery = buildLocalizedInternetQuery(queryText);
    String lookupUrl =
        "https://api.duckduckgo.com/?format=json&no_html=1&no_redirect=1&q="
            + Uri.encode(localizedQuery);
    appendLine("SCOUT-WEB", getString(R.string.scout_web_lookup));
    appendLine("SCOUT-WEB", "query " + localizedQuery);
    Request request = new Request.Builder().url(lookupUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("SCOUT-WEB", "lookup failed: " + e.getMessage());
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("SCOUT-WEB", "lookup unavailable (HTTP " + response.code() + ")");
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  String summary = payload.optString("AbstractText", "").trim();
                  if (summary.isEmpty()) {
                    JSONArray relatedTopics = payload.optJSONArray("RelatedTopics");
                    if (relatedTopics != null && relatedTopics.length() > 0) {
                      JSONObject first = relatedTopics.optJSONObject(0);
                      summary = first != null ? first.optString("Text", "").trim() : "";
                    }
                  }
                  if (summary.isEmpty()) {
                    summary = getString(R.string.scout_web_no_result);
                  }
                  summary = WEB_FALLBACK_HTML_STRIP.matcher(summary).replaceAll("").trim();
                  appendLine("SCOUT-WEB", summary);
                  speakScoutResponse(summary);
                } catch (Exception e) {
                  appendLine("SCOUT-WEB", "lookup parse error: " + e.getMessage());
                }
              }
            });
  }

  private void maybeApplyDestinationIntent(String text, String label) {
    String destinationQuery = extractDestinationCandidate(text);
    if (TextUtils.isEmpty(destinationQuery)) {
      return;
    }
    geocodeAndRoute(destinationQuery, label);
  }

  private String extractDestinationCandidate(String text) {
    if (TextUtils.isEmpty(text)) {
      return null;
    }
    Matcher promptMatcher = DESTINATION_INTENT_PATTERN.matcher(text.trim());
    if (promptMatcher.find()) {
      String direct = promptMatcher.group(1);
      String configured = promptMatcher.group(2);
      String candidate = !TextUtils.isEmpty(direct) ? direct : configured;
      if (!TextUtils.isEmpty(candidate)) {
        return candidate.replaceAll("[.?!]+$", "").trim();
      }
    }
    Matcher responseMatcher = DESTINATION_RESPONSE_PATTERN.matcher(text.trim());
    if (responseMatcher.find()) {
      String candidate = responseMatcher.group(1);
      if (!TextUtils.isEmpty(candidate)) {
        return candidate.replaceAll("[.?!]+$", "").trim();
      }
    }
    return null;
  }

  private boolean isScoutSelfQuery(String prompt) {
    if (TextUtils.isEmpty(prompt)) {
      return false;
    }
    return SCOUT_SELF_QUERY_PATTERN.matcher(prompt).find()
        || prompt.toLowerCase(Locale.ROOT).contains("self query");
  }

  private boolean requiresRuntimeNotificationPermission() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
  }

  private boolean hasNotificationPermission() {
    if (!requiresRuntimeNotificationPermission()) {
      return true;
    }
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void ensureAndroidAutoAudioNotificationPermission() {
    if (!requiresRuntimeNotificationPermission() || hasNotificationPermission()) {
      autoAudioNotificationPermissionWarned = false;
      return;
    }
    Log.i(TAG, "requesting POST_NOTIFICATIONS for Android Auto audio alerts");
    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
      appendLine("AUDIO", "notification permission required for Android Auto audio alerts");
    }
    ActivityCompat.requestPermissions(
        this,
        new String[] {Manifest.permission.POST_NOTIFICATIONS},
        NOTIFICATION_PERMISSION_REQUEST_CODE);
  }

  private void runAndroidAutoNotificationPermissionCheck() {
    if (!requiresRuntimeNotificationPermission()) {
      appendLine("AUDIO", "notification runtime permission not required on this Android version");
      return;
    }
    if (hasNotificationPermission()) {
      autoAudioNotificationPermissionWarned = false;
      appendLine("AUDIO", "notification permission already granted for Android Auto alerts");
      return;
    }
    appendLine("AUDIO", "checking Android Auto notification permission…");
    ensureAndroidAutoAudioNotificationPermission();
  }

  private void noteAutoAudioNotificationPermissionState() {
    if (hasNotificationPermission()) {
      autoAudioNotificationPermissionWarned = false;
      return;
    }
    if (!autoAudioNotificationPermissionWarned) {
      autoAudioNotificationPermissionWarned = true;
      appendLine("AUDIO", "notification permission missing; Android Auto audio notifications may be limited");
      Log.i(TAG, "POST_NOTIFICATIONS missing; Android Auto alert-audio fallback mode active");
    }
  }

  private void ensureMicrophonePermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
      appendLine("AUDIO", "microphone permission required for voice chat");
    }
    ActivityCompat.requestPermissions(
        this,
        new String[] {Manifest.permission.RECORD_AUDIO},
        MICROPHONE_PERMISSION_REQUEST_CODE);
  }

  private void startScoutMicrophoneCapture() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      pendingMicCaptureAfterPermission = true;
      ensureMicrophonePermission();
      return;
    }
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Scout");
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    try {
      startActivityForResult(intent, SCOUT_MICROPHONE_CAPTURE_REQUEST_CODE);
      appendLine("AUDIO", "listening for microphone input…");
    } catch (ActivityNotFoundException e) {
      appendLine("AUDIO", "speech recognition unavailable on device");
    }
  }

  private String relayUserId() {
    return "android-" + Build.MODEL.replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
  }

  private String clientId() {
    if (!TextUtils.isEmpty(cachedClientId)) {
      return cachedClientId;
    }
    String androidId = "";
    try {
      androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    } catch (Exception ignored) {
      androidId = "";
    }
    if (TextUtils.isEmpty(androidId)) {
      androidId = "unknown";
    }
    cachedClientId =
        ("android-stream-" + Build.MODEL + "-" + androidId)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "_");
    return cachedClientId;
  }

  private void registerClientRoute(String base) {
    if (TextUtils.isEmpty(base)) {
      return;
    }
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
    String payload =
        "{"
            + "\"client_id\":\""
            + clientId()
            + "\","
            + "\"user_id\":\""
            + relayUserId()
            + "\","
            + "\"source\":\"android_stream_client\","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
            + ","
            + "\"session_id\":\""
            + streamSessionId
            + "\"}";
    Request request =
        new Request.Builder()
            .url(base + "/api/mobile/client/register")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                // best effort registration
              }

              @Override
              public void onResponse(Call call, Response response) {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  String token = json.optString("pull_token", "").trim();
                  if (!token.isEmpty()) {
                    clientPullToken = token;
                  }
                } catch (Exception ignored) {
                  // best effort registration
                }
              }
            });
  }

  private void showEndpointOverrideDialog() {
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(AppPrefs.baseUrl(this));
    input.setSelection(input.getText().length());
    CheckBox tailscaleBox = new CheckBox(this);
    tailscaleBox.setText(getString(R.string.tailscale_mode_checkbox));
    tailscaleBox.setChecked(AppPrefs.preferTailscale(this));
    container.addView(input);
    if (ENABLE_DEV_CONTROLS) {
      container.addView(tailscaleBox);
    }
    new AlertDialog.Builder(this)
        .setTitle("Server URL")
        .setMessage(BuildConfig.ALLOW_CLEARTEXT
            ? "Set your backend origin. HTTP is allowed only in this debug build."
            : "Set your HTTPS backend origin. Secure enrollment is not yet available in this preview.")
        .setView(container)
        .setPositiveButton(
            "Save",
            (dialog, which) -> {
              String normalized = normalizeBaseUrlCandidate(input.getText().toString());
              if (normalized == null) {
                appendLine("NET", "invalid server origin; use HTTPS without credentials, a path, or a query");
                android.widget.Toast.makeText(this, "Invalid server origin", android.widget.Toast.LENGTH_LONG).show();
                return;
              }
              AppPrefs.saveBaseUrl(this, normalized);
              if (ENABLE_DEV_CONTROLS) {
                setTailscaleModeEnabled(tailscaleBox.isChecked());
              }
              if (baseUrlInput != null) {
                baseUrlInput.setText(normalized);
              }
              appendLine("NET", "server URL updated to " + normalized);
              renderRouteOnMap(true);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

private void setTailscaleModeEnabled(boolean enabled) {
    AppPrefs.setPreferTailscale(this, enabled);
    updateTailscaleModeButton();
    appendLine("NET", enabled ? "lab tailscale preferred (100.x)" : "lab tailscale disabled");
    new Thread(
            () -> {
              String resolved = AppPrefs.resolveReachableBaseUrl(this);
              AppPrefs.saveBaseUrl(this, resolved);
              postPrivateUi(
                  () -> {
                    if (baseUrlInput != null) {
                      baseUrlInput.setText(resolved);
                    }
                  });
            })
        .start();
  }

  private void onMeshModeClicked() {
    if (ScoutMeshVpnService.isStopping()) {
      return;
    }
    if (ScoutMeshVpnService.isRunning()
        || ScoutMeshVpnService.STATE_CONNECTING.equals(ScoutMeshVpnService.currentState())) {
      stopScoutMesh();
      return;
    }
    if (!BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT) {
      new AlertDialog.Builder(this)
          .setTitle(R.string.mesh_enroll_title)
          .setMessage(R.string.mesh_secure_enrollment_pending)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }
    if (MeshPrefs.hasProfile(this)) {
      requestVpnAndStartMesh();
      return;
    }
    showMeshEnrollDialog();
  }

  private void showMeshEnrollDialog() {
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    container.setPadding(pad, pad / 2, pad, 0);
    EditText hostInput = new EditText(this);
    hostInput.setHint(getString(R.string.mesh_enroll_host_hint));
    hostInput.setSingleLine(true);
    String savedHost = MeshPrefs.enrollHost(this);
    if (TextUtils.isEmpty(savedHost) && baseUrlInput != null && baseUrlInput.getText() != null) {
      savedHost = baseUrlInput.getText().toString().trim();
    }
    if (!TextUtils.isEmpty(savedHost)) {
      hostInput.setText(savedHost);
    }
    EditText tokenInput = new EditText(this);
    tokenInput.setHint(getString(R.string.mesh_entry_token_hint));
    tokenInput.setSingleLine(true);
    tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    tokenInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    tokenInput.setSaveEnabled(false);
    String savedToken = MeshPrefs.entryToken(this);
    if (!TextUtils.isEmpty(savedToken)) {
      tokenInput.setText(savedToken);
    }
    container.addView(hostInput);
    container.addView(tokenInput);
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.mesh_enroll_title))
        .setMessage(getString(R.string.mesh_enroll_message))
        .setView(container)
        .setPositiveButton(
            getString(R.string.mesh_enroll_button),
            (d, w) -> {
              String host = hostInput.getText() == null ? "" : hostInput.getText().toString().trim();
              String token =
                  tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
              enrollAndConnectMesh(host, token);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void enrollAndConnectMesh(String enrollHost, String entryToken) {
    if (meshModeBtn != null) {
      meshModeBtn.setText(getString(R.string.mesh_mode_connecting));
    }
    appendLine("MESH", "enrolling…");
    MeshEnroller.enroll(
        this,
        enrollHost,
        entryToken,
        new MeshEnroller.Listener() {
          @Override
          public void onSuccess(MeshProfile profile) {
            appendLine("MESH", "enrolled " + profile.summary());
            AppPrefs.setPreferMesh(MainActivity.this, true);
            AppPrefs.saveBaseUrl(MainActivity.this, profile.backendBaseUrl);
            if (baseUrlInput != null) {
              baseUrlInput.setText(profile.backendBaseUrl);
            }
            requestVpnAndStartMesh();
          }

          @Override
          public void onError(String message) {
            appendLine("MESH", message);
            updateMeshModeButton();
          }
        });
  }

  private void requestVpnAndStartMesh() {
    if (!MeshPrefs.hasVpnConsent(this)) {
      new AlertDialog.Builder(this)
          .setTitle(R.string.mesh_disclosure_title)
          .setMessage(R.string.mesh_disclosure_message)
          .setPositiveButton(R.string.mesh_disclosure_accept, (dialog, which) -> {
            MeshPrefs.setVpnConsent(this, true);
            requestVpnAndStartMesh();
          })
          .setNegativeButton(R.string.mesh_disclosure_decline, (dialog, which) -> {
            MeshPrefs.setVpnConsent(this, false);
            updateMeshModeButton();
          })
          .show();
      return;
    }
    Intent prepare = VpnService.prepare(this);
    if (prepare != null) {
      try {
        startActivityForResult(prepare, MESH_VPN_PERMISSION_REQUEST_CODE);
      } catch (ActivityNotFoundException ex) {
        appendLine("MESH", getString(R.string.mesh_vpn_permission_required));
        updateMeshModeButton();
      }
      return;
    }
    startScoutMeshService();
  }

  private void startScoutMeshService() {
    try {
      Intent intent = ScoutMeshVpnService.connectIntent(this);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent);
      } else {
        startService(intent);
      }
      AppPrefs.setPreferMesh(this, true);
      MeshProfile profile = MeshPrefs.loadProfile(this);
      if (profile != null) {
        AppPrefs.saveBaseUrl(this, profile.backendBaseUrl);
        if (baseUrlInput != null) {
          baseUrlInput.setText(profile.backendBaseUrl);
        }
      }
      appendLine("MESH", "vpn starting");
      updateMeshModeButton();
    } catch (Exception ex) {
      appendLine("MESH", "vpn start failed: " + ex.getMessage());
      updateMeshModeButton();
    }
  }

  private void stopScoutMesh() {
    try {
      startService(ScoutMeshVpnService.disconnectIntent(this));
    } catch (Exception ignored) {
      // ignore
    }
    appendLine("MESH", "disconnecting…");
    updateMeshModeButton();
  }

  private void updateMeshModeButton() {
    if (meshModeBtn == null) {
      return;
    }
    meshModeBtn.setEnabled(!ScoutMeshVpnService.isStopping());
    if (ScoutMeshVpnService.isStopping()) {
      meshModeBtn.setText(R.string.mesh_mode_disconnecting);
      return;
    }
    if (ScoutMeshVpnService.STATE_CONNECTING.equals(ScoutMeshVpnService.currentState())) {
      meshModeBtn.setText(R.string.mesh_mode_connecting);
      return;
    }
    boolean on = ScoutMeshVpnService.isRunning();
    meshModeBtn.setText(on ? getString(R.string.mesh_mode_on) : getString(R.string.mesh_mode_off));
  }

  private void showErrorReportDialog() {
    EditText input = new EditText(this);
    input.setHint(getString(R.string.error_report_dialog_hint));
    input.setMinLines(3);
    input.setMaxLines(6);
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.error_report_dialog_title))
        .setView(input)
        .setPositiveButton(
            getString(R.string.error_report_dialog_submit),
            (dialog, which) -> {
              String message = input.getText() == null ? "" : input.getText().toString().trim();
              if (message.isEmpty()) {
                appendLine("REPORT", "report not sent: message is empty");
                return;
              }
              submitErrorReport(message);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void submitErrorReport(String message) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
    updateErrorReportStatus(getString(R.string.error_report_status_polling));
    String details =
        "running="
            + running
            + ", forceDrivingMode="
            + forceDrivingMode
            + ", mapTarget="
            + (lastMapLat != null && lastMapLon != null
                ? String.format(Locale.ROOT, "%.6f,%.6f", lastMapLat, lastMapLon)
                : "none");
    String payload =
        "{"
            + "\"message\":\""
            + jsonEscapeLocal(message)
            + "\","
            + "\"details\":\""
            + jsonEscapeLocal(details)
            + "\","
            + "\"severity\":\"error\","
            + "\"source\":\"android_stream_client\","
            + "\"user_id\":\""
            + jsonEscapeLocal(relayUserId())
            + "\","
            + "\"client_id\":\""
            + jsonEscapeLocal(clientId())
            + "\","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
            + "}";
    Request request =
        new Request.Builder()
            .url(base + "/api/platform/error-reports/submit")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("REPORT", "submit failed: " + e.getMessage());
                updateErrorReportStatus(getString(R.string.error_report_status_idle));
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("REPORT", "submit failed (HTTP " + response.code() + ")");
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONObject entry = json.optJSONObject("entry");
                  String id = entry != null ? entry.optString("id", "") : "";
                  String ack = id.isEmpty() ? "submitted" : ("submitted: " + id);
                  appendLine("REPORT", ack);
                  pollRecentErrorReports(true);
                } catch (Exception e) {
                  appendLine("REPORT", "submit parse error: " + e.getMessage());
                } finally {
                  updateErrorReportStatus(getString(R.string.error_report_status_idle));
                }
              }
            });
  }

  private void pollErrorReportsLoop() {
    pollRecentErrorReports(false);
    uiHandler.removeCallbacks(errorReportPollRunnable);
    uiHandler.postDelayed(errorReportPollRunnable, ERROR_REPORT_POLL_INTERVAL_MS);
  }

  private void pollRecentErrorReports(boolean forceNow) {
    if (!ENABLE_DEV_CONTROLS) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (!forceNow && (now - lastErrorReportPollMs) < ERROR_REPORT_POLL_INTERVAL_MS) {
      return;
    }
    lastErrorReportPollMs = now;
    String pollUrl =
        base
            + "/api/platform/error-reports/recent?limit=20&since_ms="
            + Math.max(0L, lastSeenErrorReportCreatedAtMs);
    Request request = new Request.Builder().url(pollUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                updateErrorReportStatus(getString(R.string.error_report_status_idle));
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONArray results = json.optJSONArray("results");
                  int recentCount = json.optInt("count", 0);
                  long newestMs = lastSeenErrorReportCreatedAtMs;
                  int newCount = 0;
                  String latestMessage = "";
                  if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                      JSONObject entry = results.optJSONObject(i);
                      if (entry == null) {
                        continue;
                      }
                      String id = entry.optString("id", "").trim();
                      long createdAt = entry.optLong("created_at_ms", 0L);
                      if (createdAt > newestMs) {
                        newestMs = createdAt;
                      }
                      if (id.isEmpty() || seenErrorReportIdSet.contains(id)) {
                        continue;
                      }
                      rememberSeenErrorReportId(id);
                      newCount++;
                      if (latestMessage.isEmpty()) {
                        String severity = entry.optString("severity", "error");
                        String message = entry.optString("message", "");
                        latestMessage = "[" + severity + "] " + message;
                      }
                    }
                  }
                  lastSeenErrorReportCreatedAtMs = Math.max(lastSeenErrorReportCreatedAtMs, newestMs);
                  String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
                  updateErrorReportStatus(
                      getString(R.string.error_report_status_recent, recentCount, stamp));
                  if (newCount > 0 && !latestMessage.isEmpty()) {
                    appendLine("REPORTS", "new=" + newCount + " • " + latestMessage);
                  }
                } catch (Exception ignored) {
                  // keep polling quietly
                }
              }
            });
  }

  private void rememberSeenErrorReportId(String id) {
    if (id == null || id.isEmpty() || seenErrorReportIdSet.contains(id)) {
      return;
    }
    seenErrorReportIdSet.add(id);
    seenErrorReportIds.addLast(id);
    while (seenErrorReportIds.size() > ERROR_REPORT_SEEN_MAX_IDS) {
      String dropped = seenErrorReportIds.removeFirst();
      seenErrorReportIdSet.remove(dropped);
    }
  }

  private void maybeShowTrackingConsentDialog() {
    if (trackingConsentDialogShowing || AppPrefs.isTrackingConsentResolved(this) || isFinishing()) {
      return;
    }
    trackingConsentDialogShowing = true;
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    container.setPadding(pad, pad, pad, 0);

    TextView message = new TextView(this);
    message.setText(getString(R.string.tracking_consent_message));
    message.setLineSpacing(0f, 1.1f);
    container.addView(message);

    CheckBox doNotPromptAgain = new CheckBox(this);
    doNotPromptAgain.setText(getString(R.string.tracking_consent_dont_prompt_again));
    doNotPromptAgain.setChecked(true);
    container.addView(doNotPromptAgain);
    CheckBox analyticsOptOut = new CheckBox(this);
    analyticsOptOut.setText(getString(R.string.tracking_consent_analytics_opt_out));
    analyticsOptOut.setChecked(!AppPrefs.isAnalyticsEnabled(this));
    container.addView(analyticsOptOut);

    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.tracking_consent_title))
        .setView(container)
        .setCancelable(false)
        .setPositiveButton(
            getString(R.string.tracking_consent_enable),
            (dialog, which) -> {
              AppPrefs.setTrackingConsent(this, true, true);
              AppPrefs.setAnalyticsEnabled(this, !analyticsOptOut.isChecked());
              trackingConsentDialogShowing = false;
              appendLine("GPS", "active tracking enabled");
              if (analyticsOptOut.isChecked()) {
                appendLine("PRIVACY", "performance/data analytics opted out");
              }
              registerLocationTracking();
            })
        .setNegativeButton(
            getString(R.string.tracking_consent_skip),
            (dialog, which) -> {
              boolean suppressFuturePrompts = doNotPromptAgain.isChecked();
              AppPrefs.setTrackingConsent(this, false, suppressFuturePrompts);
              AppPrefs.setAnalyticsEnabled(this, !analyticsOptOut.isChecked());
              trackingConsentDialogShowing = false;
              unregisterLocationTracking();
              if (analyticsOptOut.isChecked()) {
                appendLine("PRIVACY", "performance/data analytics opted out");
              }
              if (suppressFuturePrompts) {
                appendLine("GPS", "active tracking disabled and future tracking prompts turned off");
              } else {
                appendLine("GPS", "active tracking skipped for now; prompt will appear again");
              }
            })
        .setOnDismissListener(dialog -> trackingConsentDialogShowing = false)
        .show();
  }

  private void updateErrorReportStatus(String status) {
    postPrivateUi(
        () -> {
          if (errorReportStatusText != null) {
            errorReportStatusText.setText(status);
          }
        });
  }

  private void updateStackManageStatus(String status) {
    postPrivateUi(
        () -> {
          if (stackManageStatusText != null) {
            stackManageStatusText.setText(status);
          }
        });
  }

  private void runStackManageAction(String action) {
    if (!ENABLE_DEV_CONTROLS) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    updateStackManageStatus("Stack: " + action + "…");
    String payload = "{\"action\":\"" + jsonEscapeLocal(action) + "\"}";
    Request request =
        new Request.Builder()
            .url(base + "/api/platform/dev/stack/manage")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("STACK", action + " failed: " + e.getMessage());
                updateStackManageStatus("Stack: " + action + " failed");
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (response.body() == null) {
                    appendLine("STACK", action + " failed (empty response)");
                    updateStackManageStatus("Stack: " + action + " failed");
                    return;
                  }
                  String raw = response.body().string();
                  JSONObject json = new JSONObject(raw);
                  int exitCode = json.optInt("exit_code", -1);
                  String status = json.optString("status", "");
                  String output = json.optString("output", "").trim();
                  if (!response.isSuccessful() || "error".equalsIgnoreCase(status)) {
                    appendLine("STACK", action + " failed (HTTP " + response.code() + ")");
                    if (!output.isEmpty()) {
                      appendLine("STACK", output.split("\n")[0]);
                    }
                    updateStackManageStatus("Stack: " + action + " failed");
                    return;
                  }
                  appendLine("STACK", action + " exit=" + exitCode);
                  if (!output.isEmpty()) {
                    appendLine("STACK", output.split("\n")[0]);
                  }
                  updateStackManageStatus("Stack: " + action + " done");
                } catch (Exception e) {
                  appendLine("STACK", action + " parse error: " + e.getMessage());
                  updateStackManageStatus("Stack: " + action + " failed");
                }
              }
            });
  }

  private String jsonEscapeLocal(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private void updateTailscaleModeButton() {
    if (tailscaleModeBtn == null) {
      return;
    }
    boolean enabled = AppPrefs.preferTailscale(this);
    tailscaleModeBtn.setText(
        enabled ? getString(R.string.tailscale_mode_on) : getString(R.string.tailscale_mode_off));
  }

  private String normalizeBaseUrlCandidate(String rawValue) {
    return EndpointPolicy.normalize(rawValue, BuildConfig.ALLOW_CLEARTEXT);
  }

  /** Restores map mode and coordinates after a configuration change (e.g. rotation). */
  private void restoreUiState(Bundle saved) {
    if (saved == null) {
      return;
    }
    if (saved.containsKey(STATE_MAP_LAT) && saved.containsKey(STATE_MAP_LON)) {
      lastMapLat = saved.getDouble(STATE_MAP_LAT);
      lastMapLon = saved.getDouble(STATE_MAP_LON);
    }
    if (saved.getBoolean(STATE_MAP3D_ENABLED, false)) {
      applyMapMode(true);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(STATE_MAP3D_ENABLED, map3dEnabled);
    if (lastMapLat != null && lastMapLon != null) {
      outState.putDouble(STATE_MAP_LAT, lastMapLat);
      outState.putDouble(STATE_MAP_LON, lastMapLon);
    }
  }


  private void updateRouteStopsUi() {
    if (routeStopAdapter != null) {
      routeStopAdapter.notifyDataSetChanged();
    }
    if (routeStopsEmptyText != null) {
      routeStopsEmptyText.setVisibility(View.VISIBLE);
      if (!routeStops.isEmpty()) {
        routeStopsEmptyText.setText(
            "Next stop: " + routeStops.get(0).label + "  •  tap to prioritize, drag to reorder");
      } else {
        routeStopsEmptyText.setText("No stops yet. Use Add stop: <destination>.");
      }
    }
  }

  private void rebuildStopsFromCurrentDestination(String fallbackLabel) {
    routeStops.clear();
    if (lastMapLat != null && lastMapLon != null) {
      String label = destinationInput != null ? destinationInput.getText().toString().trim() : "";
      if (label.isBlank()) {
        label = fallbackLabel == null || fallbackLabel.isBlank() ? "Destination" : fallbackLabel;
      }
      routeStops.add(new RouteStop(label, lastMapLat, lastMapLon));
    }
    updateRouteStopsUi();
  }

  private void applyPrimaryStopWithoutRouteOptions(String tag, String detailMessage) {
    if (routeStops.isEmpty()) {
      return;
    }
    RouteStop primary = routeStops.get(0);
    lastMapLat = primary.lat;
    lastMapLon = primary.lon;
    routeSessionActive = true;
    AppPrefs.setRouteSessionActive(this, true);
    AppPrefs.saveDestination(this, primary.lat, primary.lon);
    AppPrefs.saveDestinationLabel(this, primary.label);
    if (destinationInput != null) {
      destinationInput.setText(primary.label);
      destinationInput.setSelection(destinationInput.getText().length());
    }
    updateRouteActiveVariantUi();
    updateMapTargetUi();
    renderRouteOnMap(true);
    if (!TextUtils.isEmpty(detailMessage)) {
      appendLine(tag, detailMessage);
    }
  }

  private void applyResolvedStop(
      AddressCatalogRouter.AddressCandidate candidate,
      String label,
      String sourceTag,
      boolean addAsStop,
      boolean openRouteOptions) {
    if (candidate == null) {
      return;
    }
    RouteStop newStop = new RouteStop(candidate.displayName, candidate.lat, candidate.lon);
    if (addAsStop) {
      routeStops.add(newStop);
      updateRouteStopsUi();
      if (routeStops.size() == 1) {
        applyPrimaryStopWithoutRouteOptions("STOP", "first stop set: " + newStop.label);
        if (openRouteOptions) {
          openRouteOptionsScreen(newStop.lat, newStop.lon, newStop.label);
        }
        return;
      }
      appendLine("STOP", "added stop #" + routeStops.size() + ": " + newStop.label);
      return;
    }

    routeStops.clear();
    routeStops.add(newStop);
    updateRouteStopsUi();
    lastMapLat = newStop.lat;
    lastMapLon = newStop.lon;
    selectedDestinationSuggestion = candidate;
    updateMapTargetUi();
    appendLine(label, "destination (" + sourceTag + "): " + candidate.displayName);
    routeSessionActive = true;
    AppPrefs.setRouteSessionActive(this, true);
    updateRouteActiveVariantUi();
    postPrivateUi(
        () -> {
          destinationInput.setText(candidate.displayName);
          destinationInput.setSelection(destinationInput.getText().length());
          destinationInput.clearFocus();
          destinationInput.dismissDropDown();
        });
    renderRouteOnMap(true);
    if (openRouteOptions) {
      openRouteOptionsScreen(candidate.lat, candidate.lon, candidate.displayName);
    }
  }
  private void searchDestination() {
    String query = destinationInput.getText().toString().trim();
    if (query.isEmpty()) {
      appendLine("SEARCH", "enter a destination address first");
      return;
    }
    AddressCatalogRouter.AddressCandidate selected = selectedDestinationSuggestion;
    if (selected != null && query.equalsIgnoreCase(selected.displayName)) {
      routeToResolvedCandidate(selected, "SEARCH", "suggest");
      return;
    }
    geocodeAndRoute(query, "SEARCH");
  }

  private void scheduleDestinationSuggestions(String query) {
    uiHandler.removeCallbacks(destinationSuggestRunnable);
    pendingSuggestQuery = query;
    if (query.isEmpty()) {
      updateDestinationSuggestions(new ArrayList<>(), 0L);
      return;
    }
    uiHandler.postDelayed(destinationSuggestRunnable, 280L);
  }

  private void requestDestinationSuggestions() {
    String query = pendingSuggestQuery == null ? "" : pendingSuggestQuery.trim();
    if (query.isEmpty()) {
      updateDestinationSuggestions(new ArrayList<>(), 0L);
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long generation = ++destinationSuggestGeneration;
    Double biasLat = hasUsableDeviceFix() ? lastDeviceLat : lastMapLat;
    Double biasLon = hasUsableDeviceFix() ? lastDeviceLon : lastMapLon;
    addressCatalogRouter.suggest(
        base,
        query,
        biasLat,
        biasLon,
        8,
        new AddressCatalogRouter.SuggestCallback() {
          @Override
          public void onSuggestions(List<AddressCatalogRouter.AddressCandidate> suggestions) {
            updateDestinationSuggestions(suggestions, generation);
          }

          @Override
          public void onFailure(String message) {
            // keep silent for per-keystroke background fetches
          }
        });
  }

  private void updateDestinationSuggestions(
      List<AddressCatalogRouter.AddressCandidate> suggestions, long generation) {
    if (generation > 0 && generation != destinationSuggestGeneration) {
      return;
    }
    postPrivateUi(
        () -> {
          destinationSuggestionCandidates.clear();
          destinationSuggestionLabels.clear();
          if (suggestions != null) {
            for (AddressCatalogRouter.AddressCandidate suggestion : suggestions) {
              if (suggestion == null || TextUtils.isEmpty(suggestion.displayName)) {
                continue;
              }
              destinationSuggestionCandidates.add(suggestion);
              destinationSuggestionLabels.add(suggestion.displayName);
            }
          }
          destinationSuggestionAdapter.notifyDataSetChanged();
          if (!destinationSuggestionLabels.isEmpty() && destinationInput.hasFocus()) {
            destinationInput.showDropDown();
          } else {
            destinationInput.dismissDropDown();
          }
        });
  }

  private void routeToResolvedCandidate(
      AddressCatalogRouter.AddressCandidate candidate, String label, String sourceTag) {
    applyResolvedStop(candidate, label, sourceTag, false, true);
  }

  private void routeToResolvedCandidate(
      AddressCatalogRouter.AddressCandidate candidate,
      String label,
      String sourceTag,
      boolean addAsStop) {
    applyResolvedStop(candidate, label, sourceTag, addAsStop, !addAsStop);
  }

  private void geocodeAndRoute(String query, String label) {
    geocodeAndRoute(query, label, false);
  }

  private void geocodeAndRoute(String query, String label, boolean addAsStop) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    appendLine(label, "address catalog lookup: " + query);
    Double biasLat = hasUsableDeviceFix() ? lastDeviceLat : lastMapLat;
    Double biasLon = hasUsableDeviceFix() ? lastDeviceLon : lastMapLon;
    addressCatalogRouter.resolve(
        base,
        query,
        biasLat,
        biasLon,
        new AddressCatalogRouter.ResolveCallback() {
          @Override
          public void onResolved(AddressCatalogRouter.AddressCandidate candidate, boolean fromCatalog) {
            routeToResolvedCandidate(
                candidate, label, fromCatalog ? "catalog" : "osm-fallback", addAsStop);
          }

          @Override
          public void onFailure(String message) {
            appendLine(label, message);
          }
        });
  }

  private void openRouteOptionsScreen(double destLat, double destLon, String destLabel) {
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    double originLat = hasUsableDeviceFix() ? lastDeviceLat : destLat;
    double originLon = hasUsableDeviceFix() ? lastDeviceLon : destLon;
    Intent intent = new Intent(this, RouteOptionsActivity.class);
    intent.putExtra(RouteOptionsActivity.EXTRA_PRIVACY_REVISION, AppPrefs.privacyRevision(this));
    intent.putExtra(RouteOptionsActivity.EXTRA_BASE_URL, base);
    intent.putExtra(RouteOptionsActivity.EXTRA_ORIGIN_LAT, originLat);
    intent.putExtra(RouteOptionsActivity.EXTRA_ORIGIN_LON, originLon);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LAT, destLat);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LON, destLon);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LABEL, destLabel);
    startActivity(intent);
  }

  private void toggleMapMode() {
    map3dView.recenter();
    if (lastMapLat != null && lastMapLon != null) {
      fetchMapScene(lastMapLat, lastMapLon, DEFAULT_COUNTRY_SCENE_RADIUS_M, true);
    } else if (hasUsableDeviceFix()) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, DEFAULT_COUNTRY_SCENE_RADIUS_M, true);
    } else {
      fetchMapScene(DEFAULT_MAP_LAT, DEFAULT_MAP_LON, DEFAULT_COUNTRY_SCENE_RADIUS_M, true);
    }
  }

  private void applyMapMode(boolean enable3d) {
    map3dEnabled = true;
    mapModeBtn.setText(getString(R.string.map_mode_vector));
    map3dView.setVisibility(View.VISIBLE);
    zoomControls.setVisibility(View.VISIBLE);
    double lat;
    double lon;
    if (hasUsableDeviceFix()) {
      lat = lastDeviceLat;
      lon = lastDeviceLon;
    } else if (lastMapLat != null && lastMapLon != null) {
      lat = lastMapLat;
      lon = lastMapLon;
    } else {
      lat = DEFAULT_MAP_LAT;
      lon = DEFAULT_MAP_LON;
    }
    map3dView.recenter();
    fetchMapScene(lat, lon, DEFAULT_COUNTRY_SCENE_RADIUS_M, true);
  }

  private void fetchMapScene(double lat, double lon, double radiusM, boolean force) {
    long privacyRevision = AppPrefs.privacyRevision(this);
    String base = normalizedBaseUrl();
    if (base == null) {
      Log.d(TAG, "fetchMapScene skip: base url unavailable");
      return;
    }
    if (!map3dEnabled) {
      Log.d(TAG, "fetchMapScene skip: map3d disabled");
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (sceneFetchInFlight) {
      Log.d(TAG, "fetchMapScene skip: fetch already in flight");
      return;
    }
    if (!force && (now - lastSceneFetchMs) < 4000L) {
      Log.d(TAG, "fetchMapScene skip: throttled");
      return;
    }
    double requestedRadiusM = Math.max(MIN_SCENE_RADIUS_M, radiusM);
    sceneFetchInFlight = true;
    lastSceneFetchMs = now;
    String url =
        base
            + "/api/map/scene?lat="
            + String.format(Locale.ROOT, "%.6f", lat)
            + "&lon="
            + String.format(Locale.ROOT, "%.6f", lon)
            + "&radius_m="
            + Math.round(requestedRadiusM);
    Log.i(
        TAG,
        String.format(
            Locale.ROOT,
            "fetchMapScene request lat=%.6f lon=%.6f radius=%.1f force=%b url=%s",
            lat,
            lon,
            requestedRadiusM,
            force,
            url));
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                sceneFetchInFlight = false;
                if (call.isCanceled() || privacyRevision != AppPrefs.privacyRevision(MainActivity.this)
                    || isDestroyed()) {
                  return;
                }
                Log.w(TAG, "fetchMapScene failure: " + e.getMessage(), e);
                appendLine("MAP3D", "scene fetch failed: " + e.getMessage());
                map3dView.setLoadingHint("backend unreachable \u2014 retrying\u2026");
                scheduleSceneRetry();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "fetchMapScene http failure code=" + response.code());
                    appendLine("MAP3D", "scene unavailable (HTTP " + response.code() + ")");
                    map3dView.setLoadingHint(
                        "scene unavailable (HTTP " + response.code() + ") \u2014 retrying\u2026");
                    scheduleSceneRetry();
                    return;
                  }
                  String body = response.body().string();
                  Log.i(
                      TAG,
                      "fetchMapScene success code="
                          + response.code()
                          + " body_bytes="
                          + body.length());
                  if (call.isCanceled() || privacyRevision != AppPrefs.privacyRevision(MainActivity.this)
                      || isDestroyed()) {
                    return;
                  }
                  map3dView.setSceneJson(body);
                  Log.i(TAG, "fetchMapScene applied hasScene=" + map3dView.hasScene());
                  lastSceneLat = lat;
                  lastSceneLon = lon;
                  lastSceneRadiusM = requestedRadiusM;
                } catch (Exception e) {
                  Log.w(TAG, "fetchMapScene parse/apply failure: " + e.getMessage(), e);
                  appendLine("MAP3D", "scene parse failed: " + e.getMessage());
                  map3dView.setLoadingHint("scene parse failed \u2014 retrying\u2026");
                  scheduleSceneRetry();
                } finally {
                  sceneFetchInFlight = false;
                }
              }
            });
  }

  /**
   * The 3D view has no scene to render until a fetch succeeds; keep retrying
   * (one pending retry at a time) so a transient backend failure does not
   * leave the map stuck on the loading screen.
   */
  private void scheduleSceneRetry() {
    if (sceneRetryScheduled) {
      Log.d(TAG, "scheduleSceneRetry skip: already scheduled");
      return;
    }
    sceneRetryScheduled = true;
    Log.i(TAG, "scheduleSceneRetry +3000ms");
    postPrivateUiDelayed(
        () -> {
          sceneRetryScheduled = false;
          if (!map3dEnabled || map3dView.hasScene()) {
            Log.d(
                TAG,
                "scheduleSceneRetry cancel: map3d="
                    + map3dEnabled
                    + " hasScene="
                    + map3dView.hasScene());
            return;
          }
          double lat;
          double lon;
          if (hasUsableDeviceFix()) {
            lat = lastDeviceLat;
            lon = lastDeviceLon;
          } else if (lastMapLat != null && lastMapLon != null) {
            lat = lastMapLat;
            lon = lastMapLon;
          } else {
            lat = DEFAULT_MAP_LAT;
            lon = DEFAULT_MAP_LON;
          }
          Log.i(
              TAG,
              String.format(
                  Locale.ROOT,
                  "scheduleSceneRetry firing lat=%.6f lon=%.6f radius=700.0",
                  lat,
                  lon));
          fetchMapScene(lat, lon, 700.0, true);
        },
        3000L);
  }

  private void maybeRefreshSceneForDevice() {
    if (!map3dEnabled || !hasUsableDeviceFix()) {
      return;
    }
    if (Double.isNaN(lastSceneLat) || Double.isNaN(lastSceneLon)) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, 700.0, true);
      return;
    }
    double dLat = (lastDeviceLat - lastSceneLat) * 110540.0;
    double dLon =
        (lastDeviceLon - lastSceneLon)
            * 111320.0
            * Math.max(0.2, Math.cos(Math.toRadians(lastDeviceLat)));
    // Refetch when the device leaves ~40% of the loaded scene radius.
    if (Math.hypot(dLat, dLon) > Math.max(280.0, lastSceneRadiusM * 0.4)) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, lastSceneRadiusM, false);
    }
  }

  private void setControlPanelVisible(boolean visible) {
    controlPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    menuBtn.setText(visible ? getString(R.string.menu_close) : getString(R.string.menu_open));
    menuBackCallback.setEnabled(visible);
  }

  private void applyAppModeUi() {
    if (!BuildConfig.HAS_ANDROID_AUTO) {
      findViewById(R.id.autoNotificationPermissionBtn).setVisibility(View.GONE);
    }
    if (ENABLE_DEV_CONTROLS) {
      return;
    }
    int[] devViews = {
        R.id.baseUrlInput, R.id.tailscaleModeBtn, R.id.connectBtn, R.id.disconnectBtn,
        R.id.stackToolsTitle, R.id.stackStatusBtn, R.id.stackHealthBtn, R.id.stackStartBtn,
        R.id.stackRestartBtn, R.id.stackStopBtn, R.id.stackManageStatusText
    };
    for (int id : devViews) {
      findViewById(id).setVisibility(View.GONE);
    }
    if (baseUrlInput != null) {
      baseUrlInput.setEnabled(false);
    }
    appendLine(
        "NET",
        "Menu → Server settings to choose your HTTPS backend");
  }

  private void restoreRouteSelectionState(Intent intent) {
    double[] persistedDestination = AppPrefs.destination(this);
    if (persistedDestination != null && persistedDestination.length >= 2) {
      if (!Double.isFinite(lastMapLat == null ? Double.NaN : lastMapLat)
          || !Double.isFinite(lastMapLon == null ? Double.NaN : lastMapLon)) {
        lastMapLat = persistedDestination[0];
        lastMapLon = persistedDestination[1];
      }
    }
    String persistedLabel = AppPrefs.destinationLabel(this);
    if (!TextUtils.isEmpty(persistedLabel) && destinationInput != null) {
      destinationInput.setText(persistedLabel);
      destinationInput.setSelection(destinationInput.getText().length());
    }
    if (persistedDestination != null && persistedDestination.length >= 2) {
      rebuildStopsFromCurrentDestination(persistedLabel);
    } else {
      routeStops.clear();
      updateRouteStopsUi();
    }
    boolean focusRouteRequested = intent != null && intent.getBooleanExtra(EXTRA_FOCUS_ROUTE, false);
    routeSessionActive =
        focusRouteRequested
            || AppPrefs.isRouteSessionActive(this)
            || (persistedDestination != null && persistedDestination.length >= 2);
    if (routeSessionActive) {
      AppPrefs.setRouteSessionActive(this, true);
    }
    updateRouteActiveVariantUi();
    if (focusRouteRequested) {
      setControlPanelVisible(true);
      intent.removeExtra(EXTRA_FOCUS_ROUTE);
      setIntent(intent);
    }
  }

  private void updateRouteActiveVariantUi() {
    if (routeActivePanel == null) {
      return;
    }
    boolean routeSelected = routeSessionActive && lastMapLat != null && lastMapLon != null;
    routeActivePanel.setVisibility(routeSelected ? View.VISIBLE : View.GONE);
    if (!routeSelected) {
      alertManagementExpanded = false;
      if (alertManagementSubmenu != null) {
        alertManagementSubmenu.setVisibility(View.GONE);
      }
      if (alertManagementToggleBtn != null) {
        alertManagementToggleBtn.setText("Alert management ▼");
      }
      if (routeActionInput != null) {
        routeActionInput.setText("", false);
      }
    }
  }

  private void toggleAlertManagementSubmenu() {
    if (alertManagementSubmenu == null || alertManagementToggleBtn == null) {
      return;
    }
    alertManagementExpanded = !alertManagementExpanded;
    alertManagementSubmenu.setVisibility(alertManagementExpanded ? View.VISIBLE : View.GONE);
    alertManagementToggleBtn.setText(
        alertManagementExpanded ? "Alert management ▲" : "Alert management ▼");
  }

  private String extractRouteActionQuery(String rawInput, String prefix) {
    if (TextUtils.isEmpty(rawInput)) {
      return "";
    }
    String text = rawInput.trim();
    if (text.equalsIgnoreCase(prefix)) {
      return "";
    }
    String lower = text.toLowerCase(Locale.ROOT);
    String prefixLower = prefix.toLowerCase(Locale.ROOT);
    if (!lower.startsWith(prefixLower)) {
      return "";
    }
    String suffix = text.substring(prefix.length()).trim();
    if (suffix.startsWith(":")) {
      suffix = suffix.substring(1).trim();
    }
    return suffix;
  }

  private void applyRouteActionFromPanel() {
    if (routeActionInput == null) {
      return;
    }
    String rawAction = routeActionInput.getText() == null ? "" : routeActionInput.getText().toString().trim();
    if (rawAction.isEmpty()) {
      appendLine("ROUTE", "choose route action: Change route or Add stop");
      routeActionInput.showDropDown();
      return;
    }
    String lower = rawAction.toLowerCase(Locale.ROOT);
    if (lower.startsWith(ROUTE_ACTION_CHANGE_ROUTE.toLowerCase(Locale.ROOT))) {
      String query = extractRouteActionQuery(rawAction, ROUTE_ACTION_CHANGE_ROUTE);
      if (query.isEmpty()) {
        appendLine("ROUTE", "change route selected; enter destination in the top search box");
        if (destinationInput != null) {
          destinationInput.requestFocus();
          destinationInput.showDropDown();
        }
      } else {
        geocodeAndRoute(query, "ROUTE");
      }
      return;
    }
    if (lower.startsWith(ROUTE_ACTION_ADD_STOP.toLowerCase(Locale.ROOT))) {
      String query = extractRouteActionQuery(rawAction, ROUTE_ACTION_ADD_STOP);
      if (query.isEmpty()) {
        appendLine("ROUTE", "add stop selected; use format: Add stop: <destination>");
        return;
      }
      geocodeAndRoute(query, "STOP", true);
      return;
    }
    appendLine("ROUTE", "unsupported route action; use Change route or Add stop");
    routeActionInput.showDropDown();
  }

  private void clearActiveRouteSelection(boolean fromUserAction) {
    routeSessionActive = false;
    lastMapLat = null;
    lastMapLon = null;
    routeStops.clear();
    updateRouteStopsUi();
    selectedDestinationSuggestion = null;
    synchronized (currentRoutePoints) {
      currentRoutePoints.clear();
    }
    lastServerRouteFingerprint = "";
    AppPrefs.clearRouteSelectionCache(this);
    if (destinationInput != null) {
      destinationInput.setText("");
    }
    if (routeActionInput != null) {
      routeActionInput.setText("", false);
    }
    updateRouteActiveVariantUi();
    updateMapTargetUi();
    renderRouteOnMap(true);
    if (fromUserAction) {
      appendLine("ROUTE", "route cleared; route-active controls are now hidden");
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    ContextCompat.registerReceiver(this, meshStateReceiver,
        new IntentFilter(ScoutMeshVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED);
    updateMeshModeButton();
  }

  @Override
  protected void onStop() {
    unregisterReceiver(meshStateReceiver);
    super.onStop();
  }

  @Override
  protected void onResume() {
    super.onResume();
    locationTrackingActive = true;
    restoreRouteSelectionState(getIntent());
    registerMotionDetection();
    maybeShowTrackingConsentDialog();
    registerLocationTracking();
    uiHandler.removeCallbacks(errorReportPollRunnable);
    if (ENABLE_DEV_CONTROLS) {
      uiHandler.postDelayed(errorReportPollRunnable, 1200L);
    }
  }

  @Override
  protected void onPause() {
    locationTrackingActive = false;
    uiHandler.removeCallbacks(errorReportPollRunnable);
    unregisterLocationTracking();
    unregisterMotionDetection();
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    restoreRouteSelectionState(intent);
  }

  @Override
  protected void onDestroy() {
    locationTrackingActive = false;
    AppPrefs.unregisterPrivacyListener(this, privacyListener);
    client.close();
    sseClient.close();
    if (isFinishing() && !isChangingConfigurations()) {
      AppPrefs.clearRouteSelectionCache(this);
      routeSessionActive = false;
    }
    unregisterLocationTracking();
    unregisterMotionDetection();
    uiHandler.removeCallbacks(destinationSuggestRunnable);
    uiHandler.removeCallbacks(errorReportPollRunnable);
    stopStreaming("stopped");
    stopScoutSpeech();
    if (scoutTts != null) {
      scoutTts.shutdown();
      scoutTts = null;
    }
    super.onDestroy();
  }

  private void registerMotionDetection() {
    if (sensorManager == null || accelerometer == null) {
      updateDrivingModeUi(0f);
      appendLine("MOTION", "accelerometer unavailable; driving mode remains manual");
      return;
    }
    sensorManager.registerListener(accelListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
  }

  private void unregisterMotionDetection() {
    if (sensorManager != null) {
      sensorManager.unregisterListener(accelListener);
    }
  }

  private void registerLocationTracking() {
    if (!locationTrackingActive || isDestroyed()) {
      return;
    }
    if (!AppPrefs.isLocationSharingAllowed(this)) {
      unregisterLocationTracking();
      if (!trackingDisabledNoticeLogged) {
        trackingDisabledNoticeLogged = true;
        appendLine("GPS", "active tracking disabled by user; location permission prompts are suppressed");
      }
      return;
    }
    trackingDisabledNoticeLogged = false;
    if (!AppPrefs.isTrackingConsentResolved(this)) {
      return;
    }
    if (locationManager == null) {
      appendLine("GPS", "location manager unavailable");
      return;
    }
    boolean fineGranted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    boolean coarseGranted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    if (!fineGranted && !coarseGranted) {
      ActivityCompat.requestPermissions(
          this,
          new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
          LOCATION_PERMISSION_REQUEST_CODE);
      return;
    }
    unregisterLocationTracking();
    long registrationRevision = AppPrefs.privacyRevision(this);
    long registrationGeneration = locationRegistrationGeneration;
    locationListener = location -> {
      if (location != null && locationTrackingActive && !isDestroyed()
          && registrationGeneration == locationRegistrationGeneration
          && registrationRevision == AppPrefs.privacyRevision(this)) {
        handleDeviceLocationUpdate(location);
      }
    };
    try {
      locationManager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER,
          LOCATION_UPDATE_INTERVAL_MS,
          LOCATION_MIN_DISTANCE_M,
          locationListener);
    } catch (Exception ignored) {
      // GPS provider might be unavailable
    }
    try {
      locationManager.requestLocationUpdates(
          LocationManager.NETWORK_PROVIDER,
          LOCATION_UPDATE_INTERVAL_MS,
          LOCATION_MIN_DISTANCE_M,
          locationListener);
    } catch (Exception ignored) {
      // Network provider might be unavailable
    }
  }

  private void unregisterLocationTracking() {
    locationRegistrationGeneration++;
    if (locationManager == null || locationListener == null) {
      return;
    }
    try {
      locationManager.removeUpdates(locationListener);
    } catch (Exception ignored) {
      // no-op
    }
    locationListener = null;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
      boolean granted = false;
      for (int result : grantResults) {
        if (result == PackageManager.PERMISSION_GRANTED) {
          granted = true;
          break;
        }
      }
      if (granted) {
        appendLine("GPS", "location permission granted");
        registerLocationTracking();
      } else {
        appendLine("GPS", "location permission denied");
      }
      return;
    }
    if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
      boolean granted = false;
      for (int result : grantResults) {
        if (result == PackageManager.PERMISSION_GRANTED) {
          granted = true;
          break;
        }
      }
      if (granted) {
        appendLine("AUDIO", "microphone permission granted");
        if (pendingMicCaptureAfterPermission) {
          pendingMicCaptureAfterPermission = false;
          startScoutMicrophoneCapture();
        }
      } else {
        pendingMicCaptureAfterPermission = false;
        appendLine("AUDIO", "microphone permission denied");
      }
      return;
    }
    if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
      boolean granted = false;
      for (int result : grantResults) {
        if (result == PackageManager.PERMISSION_GRANTED) {
          granted = true;
          break;
        }
      }
      if (granted) {
        autoAudioNotificationPermissionWarned = false;
        appendLine("AUDIO", "notification permission granted for Android Auto alerts");
        Log.i(TAG, "POST_NOTIFICATIONS granted");
      } else {
        autoAudioNotificationPermissionWarned = true;
        appendLine("AUDIO", "notification permission denied; Android Auto audio notifications may be limited");
        Log.i(TAG, "POST_NOTIFICATIONS denied");
      }
    }
  }

  @Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == MESH_VPN_PERMISSION_REQUEST_CODE) {
      if (resultCode == RESULT_OK) {
        startScoutMeshService();
      } else {
        appendLine("MESH", getString(R.string.mesh_vpn_permission_required));
        updateMeshModeButton();
      }
      return;
    }
    if (requestCode != SCOUT_MICROPHONE_CAPTURE_REQUEST_CODE) {
      return;
    }
    if (resultCode != RESULT_OK || data == null) {
      appendLine("AUDIO", "no microphone transcript captured");
      return;
    }
    ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
    if (results == null || results.isEmpty()) {
      appendLine("AUDIO", "no speech recognized");
      return;
    }
    String transcript = results.get(0) == null ? "" : results.get(0).trim();
    if (transcript.isEmpty()) {
      appendLine("AUDIO", "empty transcript");
      return;
    }
    if (assistantPromptInput != null) {
      assistantPromptInput.setText(transcript);
      assistantPromptInput.setSelection(transcript.length());
    }
    appendLine("AUDIO", "heard: " + transcript);
    submitAssistantChatFromInput();
  }

  private void processAccelerometerSample(SensorEvent event) {
    if (event == null || event.values == null || event.values.length < 3) {
      return;
    }
    final float alpha = 0.85f;
    gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0];
    gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1];
    gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2];
    float linearX = event.values[0] - gravity[0];
    float linearY = event.values[1] - gravity[1];
    float linearZ = event.values[2] - gravity[2];
    float motion = (float) Math.sqrt((linearX * linearX) + (linearY * linearY) + (linearZ * linearZ));
    lastMotionMagnitude = motion;

    long now = SystemClock.elapsedRealtime();
    if (motion >= MOTION_FORCE_THRESHOLD_MS2) {
      if (motionAboveSinceMs == 0L) {
        motionAboveSinceMs = now;
      }
      motionBelowSinceMs = 0L;
      if (!forceDrivingMode && (now - motionAboveSinceMs >= MOTION_FORCE_HOLD_MS)) {
        forceDrivingMode = true;
        onForcedDrivingModeEnabled();
      }
    } else if (motion <= MOTION_IDLE_THRESHOLD_MS2) {
      motionAboveSinceMs = 0L;
      if (motionBelowSinceMs == 0L) {
        motionBelowSinceMs = now;
      }
      if (forceDrivingMode && (now - motionBelowSinceMs >= MOTION_IDLE_RELEASE_MS)) {
        forceDrivingMode = false;
        onForcedDrivingModeReleased();
      }
    } else {
      motionAboveSinceMs = 0L;
      motionBelowSinceMs = 0L;
    }

    if ((now - lastMotionUiUpdateMs) >= 1000L) {
      lastMotionUiUpdateMs = now;
      updateDrivingModeUi(motion);
    }
  }

  private void onForcedDrivingModeEnabled() {
    updateDrivingModeUi(MOTION_FORCE_THRESHOLD_MS2);
    appendLine("DRIVE_MODE", "forced driving mode enabled from accelerometer motion");
    if (ENABLE_DEV_CONTROLS) {
      postPrivateUi(() -> baseUrlInput.setEnabled(false));
    }
    if (ENABLE_DEV_CONTROLS && !running) {
      startStreaming();
    }
  }

  private void onForcedDrivingModeReleased() {
    updateDrivingModeUi(0f);
    appendLine("DRIVE_MODE", "forced driving mode released after sustained idle motion");
    if (ENABLE_DEV_CONTROLS) {
      postPrivateUi(() -> baseUrlInput.setEnabled(true));
    }
  }

  private void updateDrivingModeUi(float motion) {
    postPrivateUi(
        () -> {
          if (drivingModeText == null) {
            return;
          }
          String mode = forceDrivingMode ? "FORCED ON" : "manual";
          drivingModeText.setText(
              String.format(
                  Locale.ROOT,
                  "Driving Mode: %s  |  accel=%.2f m/s²",
                  mode,
                  motion));
        });
  }

  private void startStreaming() {
    if (!ENABLE_DEV_CONTROLS) {
      setStatus("dev stream controls disabled");
      return;
    }
    if (running) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    AppPrefs.saveBaseUrl(this, base);
    String resolved = AppPrefs.resolveReachableBaseUrl(this);
    if (!resolved.equals(base)) {
      appendLine("NET", "auto-switched backend to " + resolved);
      if (ENABLE_DEV_CONTROLS) {
        baseUrlInput.setText(resolved);
      }
    }
    running = true;
    setStatus("connecting...");
    appendLine("STREAM TARGET", resolved);
    registerClientRoute(resolved);
    final String target = resolved;
    new Thread(
            () -> {
              fetchSnapshot(target);
              streamSse(target);
            })
        .start();
    syncDeviceGpsToBackend();
  }

  private String normalizedBaseUrl() {
    String base;
    if (ENABLE_DEV_CONTROLS) {
      base = baseUrlInput.getText().toString();
    } else {
      base = AppPrefs.resolveReachableBaseUrl(this);
    }
    String normalized = normalizeBaseUrlCandidate(base);
    if (normalized == null
        || (!BuildConfig.ALLOW_CLEARTEXT && AppPrefs.DEFAULT_BASE_URL.equals(normalized))) {
      return null;
    }
    AppPrefs.saveBaseUrl(this, normalized);
    return AppPrefs.resolveReachableBaseUrl(this);
  }

  private void stopStreaming(String reason) {
    running = false;
    if (streamCall != null) {
      streamCall.cancel();
      streamCall = null;
    }
    setStatus(reason);
  }

  private void fetchSnapshot(String base) {
    Request request = new Request.Builder().url(base + "/api/pipeline/snapshot").build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        appendLine("SNAPSHOT", "unavailable");
        return;
      }
      String body = response.body().string();
      JSONObject json = new JSONObject(body);
      JSONObject metrics = json.optJSONObject("metrics");
      if (metrics != null) {
        appendLine(
            "SNAPSHOT",
            String.format(
                Locale.ROOT,
                "captured=%d silence=%d clipped=%d alerts=%d fallback=%d",
                metrics.optInt("captured", 0),
                metrics.optInt("skipped_silence", 0),
                metrics.optInt("skipped_clipped", 0),
                metrics.optInt("llm_alert", 0),
                metrics.optInt("soft_alert_fallback", 0)));
      } else {
        appendLine("SNAPSHOT", "loaded");
      }
    } catch (Exception e) {
      appendLine("SNAPSHOT", "error: " + e.getMessage());
    }
  }

  private void streamSse(String base) {
    int attempt = 0;
    String activeBase = base;
    while (running) {
      String streamUrl = activeBase + "/api/pipeline/stream";
      String token = clientPullToken == null ? "" : clientPullToken.trim();
      if (!token.isEmpty()) {
        streamUrl =
            streamUrl
                + "?client_id="
                + Uri.encode(clientId())
                + "&user_id="
                + Uri.encode(relayUserId())
                + "&source="
                + Uri.encode("android_stream_client")
                + "&session_id="
                + Uri.encode(streamSessionId)
                + "&pull_token="
                + Uri.encode(token);
      }
      Request request = new Request.Builder().url(streamUrl).build();
      streamCall = sseClient.newCall(request);
      try (Response response = streamCall.execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          setStatus("stream unavailable");
        } else {
          setStatus("streaming");
          attempt = 0;
          InputStream stream = response.body().byteStream();
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
              if (!line.startsWith("data:")) {
                continue;
              }
              String payload = line.substring(5).trim();
              if (payload.isEmpty()) {
                continue;
              }
              appendEvent(payload);
            }
          }
        }
      } catch (IOException e) {
        if (running) {
          appendLine("STREAM", "error: " + e.getMessage());
        }
      }
      if (!running) {
        break;
      }
      String nextBase = AppPrefs.resolveReachableBaseUrl(this);
      if (!nextBase.equals(activeBase)) {
        activeBase = nextBase;
        registerClientRoute(activeBase);
        appendLine("NET", "auto-switched backend to " + activeBase);
        String switchedBase = activeBase;
        postPrivateUi(() -> baseUrlInput.setText(switchedBase));
      }
      // Server closed the stream or the connection dropped: back off and retry.
      attempt++;
      long delayMs = Math.min(15000L, 1000L << Math.min(attempt, 4));
      setStatus("stream reconnecting...");
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (running) {
      setStatus("idle");
    }
    running = false;
  }

  private void appendEvent(String payload) {
    try {
      JSONObject json = new JSONObject(payload);
      String eventType = json.optString("event_type", "unknown");
      String kind = json.optString("kind", "");
      String alert = json.optString("alert", "");
      String transcript = json.optString("transcript", "");
      String message = json.optString("message", "");
      String text;
      if (!alert.isEmpty()) {
        text = alert;
      } else if (!message.isEmpty()) {
        text = message;
      } else {
        text = transcript;
      }
      if (TextUtils.isEmpty(text)) {
        text = "(no text payload)";
      }
      captureMapTargetFromEventPayload(alert);
      captureMapTargetFromEventPayload(transcript);
      captureMapTargetFromEventPayload(message);
      List<String> mentions = new ArrayList<>();
      collectMentions(json.optJSONArray("location_mentions"), mentions);
      collectMentions(json.optJSONArray("poi_mentions"), mentions);
      JSONObject llmIntel = json.optJSONObject("llm_intel");
      String intelLine = buildIntelLine(llmIntel);
      updateLocalCallContext(llmIntel, json);
      captureBroadcastifyStreamContext(json, eventType, kind, text);
      boolean isAlert = "alert_triggered".equals(eventType);
      if (isAlert) {
        String coreRatingLine = buildCore0RatingLine(json);
        if (!TextUtils.isEmpty(coreRatingLine)) {
          intelLine = TextUtils.isEmpty(intelLine) ? coreRatingLine : (coreRatingLine + "\n" + intelLine);
        }
        maybeSpeakPipelineAlertFromRating(json, text);
        maybePostRouteAlertNotification(json, mentions, text, intelLine);
      }
      if (!mentions.isEmpty() || (isAlert && !TextUtils.isEmpty(text))) {
        float[] levelSeries = parseAudioLevels(json.optJSONArray("audio_levels"));
        long levelWindowMs = json.optLong("audio_level_window_ms", 250L);
        maybeShowLocationPopup(
            eventType, mentions, intelLine, text, json.optDouble("rms", 0.0),
            levelSeries, levelWindowMs);
      }
      String label =
          kind.isEmpty()
              ? eventType.toUpperCase(Locale.ROOT)
              : (eventType + "/" + kind).toUpperCase(Locale.ROOT);
      appendLine(label, text);
    } catch (JSONException e) {
      appendLine("PARSE", "error: " + e.getMessage());
    }
  }

  private void collectMentions(JSONArray array, List<String> sink) {
    if (array == null) {
      return;
    }
    for (int i = 0; i < array.length(); i++) {
      String mention = array.optString(i, "").trim();
      if (!mention.isEmpty() && !sink.contains(mention)) {
        sink.add(mention);
      }
    }
  }

  /**
   * Converts the event's per-window RMS envelope into normalized visualizer amplitudes
   * (same rms*8 scaling as the static amplitude path); null when absent.
   */
  private float[] parseAudioLevels(JSONArray array) {
    if (array == null || array.length() == 0) {
      return null;
    }
    float[] levels = new float[array.length()];
    for (int i = 0; i < array.length(); i++) {
      double windowRms = array.optDouble(i, 0.0);
      levels[i] = (float) Math.min(1.0, Math.max(0.0, windowRms * 8.0));
    }
    return levels;
  }

  /**
   * Picks the first mention that is actually routable: strips bare directional words and skips
   * junk tokens (e.g. "northbound") that would geocode to arbitrary far-away places.
   */
  private String pickRoutableQuery(List<String> mentions) {
    for (String mention : mentions) {
      String cleaned = stripDirectional(mention);
      if (cleaned.isEmpty()
          || NON_ROUTABLE_MENTIONS.contains(cleaned.toLowerCase(Locale.ROOT))) {
        continue;
      }
      return cleaned;
    }
    return null;
  }

  private String stripDirectional(String mention) {
    return DIRECTIONAL_TOKEN_PATTERN
        .matcher(mention)
        .replaceAll(" ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  /** One-line summary of the scout-intel extraction; empty when intel is unavailable. */
  private String buildIntelLine(JSONObject intel) {
    if (intel == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    List<String> callTypes = new ArrayList<>();
    collectMentions(intel.optJSONArray("call_types"), callTypes);
    if (!callTypes.isEmpty()) {
      parts.add(TextUtils.join(", ", callTypes).replace('_', ' '));
    }
    String priority = intel.optString("priority", "").trim();
    if (!priority.isEmpty() && !"unknown".equalsIgnoreCase(priority)) {
      parts.add("priority " + priority);
    }
    List<String> units = new ArrayList<>();
    collectMentions(intel.optJSONArray("units"), units);
    if (!units.isEmpty()) {
      parts.add("units " + TextUtils.join(", ", units));
    }
    String line = TextUtils.join("  \u2022  ", parts);
    String summary = intel.optString("summary", "").trim();
    if (!summary.isEmpty()) {
      line = line.isEmpty() ? summary : line + "\n" + summary;
    }
    return line;
  }

  /** Core0 legacy call-rating line shown at the top of alert intel details. */
  private String buildCore0RatingLine(JSONObject eventJson) {
    if (eventJson == null) {
      return "";
    }
    int rating = extractLegacyCallRating(eventJson);
    if (rating <= 0) {
      return getString(R.string.popup_core0_unknown);
    }
    StringBuilder sb = new StringBuilder(getString(R.string.popup_core0_rating));
    sb.append(" ").append(rating).append("/5");
    String reason = extractLegacyCallReason(eventJson);
    if (!TextUtils.isEmpty(reason)) {
      sb.append(" • ").append(reason);
    }
    return sb.toString();
  }

  private int extractLegacyCallRating(JSONObject eventJson) {
    if (eventJson == null) {
      return 0;
    }
    int direct = eventJson.optInt("call_rating", Integer.MIN_VALUE);
    if (direct != Integer.MIN_VALUE) {
      return Math.max(0, Math.min(5, direct));
    }
    JSONObject intel = eventJson.optJSONObject("llm_intel");
    if (intel != null) {
      int intelRating = intel.optInt("call_rating", Integer.MIN_VALUE);
      if (intelRating != Integer.MIN_VALUE) {
        return Math.max(0, Math.min(5, intelRating));
      }
      int genericRating = intel.optInt("rating", Integer.MIN_VALUE);
      if (genericRating != Integer.MIN_VALUE) {
        return Math.max(0, Math.min(5, genericRating));
      }
      String priority = intel.optString("priority", "").trim().toLowerCase(Locale.ROOT);
      if ("critical".equals(priority) || "high".equals(priority)) {
        return 5;
      }
      if ("medium".equals(priority)) {
        return 3;
      }
      if ("low".equals(priority)) {
        return 2;
      }
    }
    return 0;
  }

  private String extractLegacyCallReason(JSONObject eventJson) {
    if (eventJson == null) {
      return "";
    }
    String reason = eventJson.optString("call_rating_reason", "").trim();
    if (!reason.isEmpty()) {
      return reason.replace('_', ' ');
    }
    JSONObject intel = eventJson.optJSONObject("llm_intel");
    if (intel != null) {
      reason = intel.optString("rating_reason", "").trim();
      if (!reason.isEmpty()) {
        return reason.replace('_', ' ');
      }
      reason = intel.optString("summary", "").trim();
      if (!reason.isEmpty()) {
        return reason;
      }
    }
    return "";
  }

  private void maybeSpeakPipelineAlertFromRating(JSONObject eventJson, String alertText) {
    int rating = extractLegacyCallRating(eventJson);
    if (rating < PIPELINE_ALERT_SPEAK_MIN_RATING || TextUtils.isEmpty(alertText)) {
      return;
    }
    String eventTs = eventJson != null ? eventJson.optString("ts", "") : "";
    String dedupeKey = eventTs + "|" + alertText;
    long now = SystemClock.elapsedRealtime();
    if (dedupeKey.equals(lastSpokenPipelineAlertKey)
        && (now - lastSpokenPipelineAlertAtMs) < PIPELINE_ALERT_SPEAK_COOLDOWN_MS) {
      return;
    }
    lastSpokenPipelineAlertKey = dedupeKey;
    lastSpokenPipelineAlertAtMs = now;
    String spoken = "Pipeline alert rating " + rating + ". " + alertText;
    Log.i(TAG, "pipeline alert vocalized from legacy rating=" + rating);
    postPrivateUi(() -> speakScoutResponse(spoken));
  }

  private void ensureRouteAlertNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }
    NotificationManager notificationManager = getSystemService(NotificationManager.class);
    if (notificationManager == null) {
      return;
    }
    NotificationChannel existing =
        notificationManager.getNotificationChannel(ROUTE_ALERT_NOTIFICATION_CHANNEL_ID);
    if (existing != null) {
      return;
    }
    NotificationChannel channel =
        new NotificationChannel(
            ROUTE_ALERT_NOTIFICATION_CHANNEL_ID,
            getString(R.string.route_alert_channel_name),
            ROUTE_ALERT_NOTIFICATION_CHANNEL_IMPORTANCE);
    channel.setDescription(getString(R.string.route_alert_channel_description));
    channel.enableVibration(true);
    notificationManager.createNotificationChannel(channel);
  }

  private void maybePostRouteAlertNotification(
      JSONObject eventJson, List<String> mentions, String alertText, String intelLine) {
    if (!routeSessionActive || TextUtils.isEmpty(alertText)) {
      return;
    }
    noteAutoAudioNotificationPermissionState();
    if (!hasNotificationPermission()) {
      return;
    }
    String eventTs = eventJson != null ? eventJson.optString("ts", "").trim() : "";
    String dedupeKey = (eventTs.isEmpty() ? "ts_missing" : eventTs) + "|" + alertText;
    long now = SystemClock.elapsedRealtime();
    if (dedupeKey.equals(lastRouteAlertNotificationKey)
        && (now - lastRouteAlertNotificationAtMs) < ROUTE_ALERT_NOTIFICATION_COOLDOWN_MS) {
      return;
    }
    lastRouteAlertNotificationKey = dedupeKey;
    lastRouteAlertNotificationAtMs = now;

    String routeLabel = destinationInput != null ? destinationInput.getText().toString().trim() : "";
    String contentTitle =
        TextUtils.isEmpty(routeLabel)
            ? getString(R.string.route_alert_notification_title)
            : getString(R.string.route_alert_notification_title_with_destination, routeLabel);
    String mentionSummary =
        mentions == null || mentions.isEmpty()
            ? ""
            : getString(R.string.route_alert_notification_mentions, TextUtils.join(", ", mentions));
    String detailText = alertText.trim();
    if (detailText.length() > 240) {
      detailText = detailText.substring(0, 237) + "...";
    }
    StringBuilder bigTextBuilder = new StringBuilder(detailText);
    if (!TextUtils.isEmpty(mentionSummary)) {
      bigTextBuilder.append("\n").append(mentionSummary);
    }
    if (!TextUtils.isEmpty(intelLine)) {
      bigTextBuilder.append("\n").append(intelLine.replace('\n', ' '));
    }
    String bigText = bigTextBuilder.toString();

    Intent launchIntent = new Intent(this, MainActivity.class);
    launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    launchIntent.putExtra(EXTRA_FOCUS_ROUTE, true);
    int requestCode = Math.abs(dedupeKey.hashCode());
    int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent contentIntent =
        PendingIntent.getActivity(this, requestCode, launchIntent, pendingIntentFlags);
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(this, ROUTE_ALERT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(contentTitle)
            .setContentText(detailText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_directions,
                getString(R.string.route_alert_notification_action_open_route),
                contentIntent)
            .extend(new NotificationCompat.CarExtender());
    try {
      NotificationManagerCompat.from(this).notify(requestCode, builder.build());
    } catch (SecurityException denied) {
      // Notification permission can be revoked between the check and the post.
      return;
    }
    appendLine("ALERT", "posted route notification");
    Log.i(TAG, "posted route alert notification for active route");
  }

  private void maybeShowLocationPopup(
      String eventType,
      List<String> mentions,
      String intelLine,
      String text,
      double rms,
      float[] levelSeries,
      long levelWindowMs) {
    String key = TextUtils.join("|", mentions).toLowerCase(Locale.ROOT);
    long now = SystemClock.elapsedRealtime();
    boolean isAlert = "alert_triggered".equals(eventType);
    if (!isAlert
        && key.equals(lastPopupMentionKey)
        && (now - lastPopupShownMs) < POPUP_REPEAT_SUPPRESS_MS) {
      return;
    }
    lastPopupMentionKey = key;
    lastPopupShownMs = now;
    boolean hasMention = !mentions.isEmpty();
    pendingPopupQuery = pickRoutableQuery(mentions);
    boolean canRoute = pendingPopupQuery != null;
    float amplitude = (float) Math.min(1.0, Math.max(0.0, rms * 8.0));
    String title =
        isAlert ? getString(R.string.popup_title_alert) : getString(R.string.popup_title_location);
    String locations =
        hasMention ? TextUtils.join("  \u2022  ", mentions) : getString(R.string.popup_no_location);
    if (hasMention) {
      appendLine("LOCATION", locations);
    }
    if (!TextUtils.isEmpty(intelLine)) {
      appendLine("INTEL", intelLine.replace('\n', ' '));
    }
    postPrivateUi(
        () -> {
          popupTitle.setText(title);
          popupLocationText.setText(locations);
          popupIntelText.setText(intelLine);
          popupIntelText.setVisibility(TextUtils.isEmpty(intelLine) ? View.GONE : View.VISIBLE);
          popupRouteBtn.setEnabled(canRoute);
          popupRouteBtn.setAlpha(canRoute ? 1f : 0.5f);
          popupTranscriptText.setText(text);
          if (levelSeries != null) {
            popupVisualizer.setLevels(levelSeries, levelWindowMs);
          } else {
            popupVisualizer.setAmplitude(amplitude);
          }
          popupVisualizer.start();
          locationPopup.setVisibility(View.VISIBLE);
          uiHandler.removeCallbacks(popupAutoHideRunnable);
          uiHandler.postDelayed(popupAutoHideRunnable, POPUP_AUTO_HIDE_MS);
        });
  }

  private void hideLocationPopup() {
    uiHandler.removeCallbacks(popupAutoHideRunnable);
    postPrivateUi(
        () -> {
          popupVisualizer.stop();
          locationPopup.setVisibility(View.GONE);
        });
  }

  private void routeToPopupLocation() {
    String query = pendingPopupQuery;
    hideLocationPopup();
    if (TextUtils.isEmpty(query)) {
      return;
    }
    geocodeAndRoute(query, "LOCATION");
  }

  private void captureMapTargetFromEventPayload(String payloadText) {
    if (TextUtils.isEmpty(payloadText)) {
      return;
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(payloadText);
    if (!matcher.find()) {
      return;
    }
    try {
      double lat = Double.parseDouble(matcher.group(1));
      double lon = Double.parseDouble(matcher.group(2));
      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        return;
      }
      lastMapLat = lat;
      lastMapLon = lon;
      updateMapTargetUi();
      renderRouteOnMap(true);
    } catch (NumberFormatException ignored) {
      // ignore malformed coordinate values
    }
  }

  private void updateMapTargetUi() {
    AppPrefs.saveDestination(this, lastMapLat, lastMapLon);
    postPrivateUi(
        () -> {
          if (mapTargetText == null) {
            return;
          }
          if (lastMapLat != null && lastMapLon != null) {
            mapTargetText.setText("Map Target: navigation target active");
            return;
          }
          if (hasUsableDeviceFix()) {
            mapTargetText.setText("Map Target: device fallback active");
            return;
          }
          mapTargetText.setText(getString(R.string.map_target_none));
        });
  }

  private void openLatestMapTarget() {
    double targetLat;
    double targetLon;
    if (lastMapLat != null && lastMapLon != null) {
      targetLat = lastMapLat;
      targetLon = lastMapLon;
    } else if (hasUsableDeviceFix()) {
      targetLat = lastDeviceLat;
      targetLon = lastDeviceLon;
      appendLine("MAPS", "using live device GPS as route target");
    } else {
      appendLine("MAPS", "no coordinate target available yet");
      return;
    }

    lastMapLat = targetLat;
    lastMapLon = targetLon;
    AppPrefs.saveDestination(this, targetLat, targetLon);
    AppPrefs.saveDestinationLabel(
        this,
        String.format(Locale.ROOT, "Target %.5f, %.5f", targetLat, targetLon));
    routeStops.clear();
    routeStops.add(
        new RouteStop(
            String.format(Locale.ROOT, "Target %.5f, %.5f", targetLat, targetLon),
            targetLat,
            targetLon));
    updateRouteStopsUi();
    routeSessionActive = true;
    AppPrefs.setRouteSessionActive(this, true);
    updateRouteActiveVariantUi();
    updateMapTargetUi();
    if (!map3dEnabled) {
      applyMapMode(true);
    }
    renderRouteOnMap(true);
    openRouteOptionsScreen(targetLat, targetLon, "Map target");
    appendLine(
        "MAPS",
        "opened in-app route view to current target");
  }

  private void handleDeviceLocationUpdate(Location location) {
    if (!locationTrackingActive || isDestroyed() || !AppPrefs.isLocationSharingAllowed(this)) {
      return;
    }
    lastDevicePrivacyRevision = AppPrefs.privacyRevision(this);
    lastDeviceLat = location.getLatitude();
    lastDeviceLon = location.getLongitude();
    lastDeviceAccuracyM = location.hasAccuracy() ? location.getAccuracy() : null;
    lastDeviceSpeedMps = location.hasSpeed() ? location.getSpeed() : null;
    lastDeviceHeadingDeg = location.hasBearing() ? location.getBearing() : null;
    if (lastMapLat == null || lastMapLon == null) {
      updateMapTargetUi();
    }
    map3dView.updateDevice(
        location.getLatitude(), location.getLongitude(), lastDeviceHeadingDeg, lastDeviceSpeedMps);
    maybeRefreshSceneForDevice();
    renderRouteOnMap(false);
    syncDeviceGpsToBackend();
  }

  private String deriveDeviceCondition() {
    if (forceDrivingMode && running) {
      return "driving_streaming";
    }
    if (forceDrivingMode) {
      return "driving_idle";
    }
    if (running) {
      return "streaming_stationary";
    }
    if (lastMotionMagnitude >= MOTION_FORCE_THRESHOLD_MS2 * 0.6f) {
      return "motion_detected";
    }
    return "idle";
  }

  private void syncDeviceGpsToBackend() {
    if (!hasUsableDeviceFix()) {
      return;
    }
    if (!running && !forceDrivingMode) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if ((now - lastDeviceGpsPostMs) < DEVICE_GPS_POST_INTERVAL_MS) {
      return;
    }
    lastDeviceGpsPostMs = now;
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
    String userId = "android-" + Build.MODEL.replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
    String payload =
        "{"
            + "\"user_id\":\""
            + userId
            + "\","
            + "\"source\":\"android_stream_client\","
            + "\"lat\":"
            + String.format(Locale.ROOT, "%.7f", lastDeviceLat)
            + ","
            + "\"lon\":"
            + String.format(Locale.ROOT, "%.7f", lastDeviceLon)
            + ","
            + "\"accuracy\":"
            + String.format(
                Locale.ROOT, "%.2f", lastDeviceAccuracyM != null ? lastDeviceAccuracyM : 0f)
            + ","
            + "\"speed\":"
            + String.format(Locale.ROOT, "%.2f", lastDeviceSpeedMps != null ? lastDeviceSpeedMps : 0f)
            + ","
            + "\"heading\":"
            + String.format(
                Locale.ROOT, "%.2f", lastDeviceHeadingDeg != null ? lastDeviceHeadingDeg : 0f)
            + ","
            + "\"device_condition\":\""
            + deriveDeviceCondition()
            + "\","
            + "\"forced_driving\":"
            + (forceDrivingMode ? "true" : "false")
            + ","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
            + "}";

    Request request =
        new Request.Builder()
            .url(base + "/api/gps/update")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                // non-blocking fire-and-forget sync
              }

              @Override
              public void onResponse(Call call, Response response) {
                response.close();
              }
            });
  }

  private void renderRouteOnMap(boolean forceServerRefresh) {
    double originLat = hasUsableDeviceFix() ? lastDeviceLat : (lastMapLat != null ? lastMapLat : DEFAULT_MAP_LAT);
    double originLon = hasUsableDeviceFix() ? lastDeviceLon : (lastMapLon != null ? lastMapLon : DEFAULT_MAP_LON);
    double targetLat = (lastMapLat != null) ? lastMapLat : originLat + 0.0045d;
    double targetLon = (lastMapLon != null) ? lastMapLon : originLon + 0.0065d;

    maybeFetchServerRoute(originLat, originLon, targetLat, targetLon, forceServerRefresh);
    pushRouteSceneToView(originLat, originLon, targetLat, targetLon);
  }

  private void pushRouteSceneToView(double originLat, double originLon, double targetLat, double targetLon) {
    List<double[]> routeSnapshot;
    synchronized (currentRoutePoints) {
      routeSnapshot = new ArrayList<>(currentRoutePoints);
    }
    map3dView.setRoute(routeSnapshot);
    if (lastMapLat != null && lastMapLon != null) {
      map3dView.setDestination(lastMapLat, lastMapLon);
    } else {
      map3dView.setDestination(null, null);
    }
  }

  private void maybeFetchServerRoute(
      double originLat, double originLon, double destLat, double destLon, boolean forceRefresh) {
    long privacyRevision = AppPrefs.privacyRevision(this);
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    String fingerprint =
        String.format(
            Locale.ROOT,
            "%.4f,%.4f->%.4f,%.4f",
            originLat,
            originLon,
            destLat,
            destLon);
    long now = SystemClock.elapsedRealtime();
    if (serverRouteRequestInFlight) {
      return;
    }
    if (!forceRefresh
        && fingerprint.equals(lastServerRouteFingerprint)
        && (now - lastServerRouteFetchMs) < SERVER_ROUTE_REFRESH_MS) {
      return;
    }
    serverRouteRequestInFlight = true;
    lastServerRouteFingerprint = fingerprint;
    lastServerRouteFetchMs = now;

    String routeUrl =
        base
            + "/api/platform/route/local"
            + "?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destLon)
            + "&condition="
            + Uri.encode(deriveDeviceCondition());
    Request request = new Request.Builder().url(routeUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                serverRouteRequestInFlight = false;
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONArray routePoints = json.optJSONArray("route_points");
                  if (call.isCanceled() || privacyRevision != AppPrefs.privacyRevision(MainActivity.this)
                      || isDestroyed()) {
                    return;
                  }
                  if (routePoints == null || routePoints.length() < 2) {
                    return;
                  }
                  List<double[]> serverRoute = new ArrayList<>();
                  for (int i = 0; i < routePoints.length(); i++) {
                    JSONObject point = routePoints.optJSONObject(i);
                    if (point == null) {
                      continue;
                    }
                    double lat = point.optDouble("lat", Double.NaN);
                    double lon = point.optDouble("lon", Double.NaN);
                    if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                      continue;
                    }
                    serverRoute.add(new double[] {lat, lon});
                  }
                  if (serverRoute.size() < 2) {
                    return;
                  }
                  synchronized (currentRoutePoints) {
                    currentRoutePoints.clear();
                    currentRoutePoints.addAll(serverRoute);
                  }
                  pushRouteSceneToView(originLat, originLon, destLat, destLon);
                } catch (Exception ignored) {
                  // previous route remains active
                } finally {
                  serverRouteRequestInFlight = false;
                }
              }
            });
  }

  private void setStatus(String status) {
    postPrivateUi(() -> statusText.setText("Status: " + status));
  }

  private void postPrivateUi(Runnable task) {
    uiHandler.post(client.guard(() -> {
      if (!isDestroyed()) {
        task.run();
      }
    }));
  }

  private void postPrivateUiDelayed(Runnable task, long delayMs) {
    uiHandler.postDelayed(client.guard(() -> {
      if (!isDestroyed()) {
        task.run();
      }
    }), delayMs);
  }

  private void appendLine(String label, String text) {
    postPrivateUi(
        () -> {
          String existing = outputText.getText().toString();
          if (getString(R.string.stream_placeholder).equals(existing)) {
            existing = "";
          }
          if (existing.length() > 20000) {
            existing = existing.substring(existing.length() - 12000);
          }
          String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
          outputText.setText(existing + "[" + stamp + "] " + label + "  " + text + "\n");
        });
  }
}
