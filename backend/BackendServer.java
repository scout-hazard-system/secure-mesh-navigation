import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;

public final class BackendServer {
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 18080;
  private static final int RECENT_EVENT_LIMIT = 120;
  private static final int SNAPSHOT_EVENT_RETURN_LIMIT = 30;
  private static final int MOBILE_EVENT_RETURN_LIMIT = 12;
  private static final int ASSISTANT_STREAM_EVENT_RETURN_LIMIT =
      parseIntOrDefault(System.getenv("ASSISTANT_STREAM_EVENT_RETURN_LIMIT"), 40);
  private static final int ASSISTANT_CONTEXT_MAX_CHARS =
      parseIntOrDefault(System.getenv("ASSISTANT_CONTEXT_MAX_CHARS"), 24000);
  private static final int ASSISTANT_HIGH_LOAD_CONTEXT_MAX_CHARS =
      parseIntOrDefault(System.getenv("ASSISTANT_HIGH_LOAD_CONTEXT_MAX_CHARS"), 14000);
  private static final int ASSISTANT_TOOL_SECTION_MAX_CHARS =
      parseIntOrDefault(System.getenv("ASSISTANT_TOOL_SECTION_MAX_CHARS"), 4200);
  private static final boolean ASSISTANT_INCLUDE_ROUTE_CONTEXT =
      "true".equalsIgnoreCase(
          System.getenv().getOrDefault("ASSISTANT_INCLUDE_ROUTE_CONTEXT", "false"));
  private static final int ASSISTANT_HIGH_QUERY_THRESHOLD =
      parseIntOrDefault(System.getenv("ASSISTANT_HIGH_QUERY_THRESHOLD"), 12);
  private static final long ASSISTANT_HIGH_QUERY_WINDOW_MS =
      parseLongOrDefault(System.getenv("ASSISTANT_HIGH_QUERY_WINDOW_MS"), 60000L);
  private static final long ASSISTANT_TOOL_FANOUT_TIMEOUT_MS =
      parseLongOrDefault(System.getenv("ASSISTANT_TOOL_FANOUT_TIMEOUT_MS"), 4500L);
  private static final long STREAM_POLL_MILLIS = 350L;
  private static final long CLIENT_ROUTE_TTL_MS =
      parseLongOrDefault(System.getenv("CLIENT_ROUTE_TTL_MS"), 300000L);
  private static final int CLIENT_MAILBOX_MAX_MESSAGES =
      parseIntOrDefault(System.getenv("CLIENT_MAILBOX_MAX_MESSAGES"), 120);
  private static final int CLIENT_PULL_DEFAULT_LIMIT =
      parseIntOrDefault(System.getenv("CLIENT_PULL_DEFAULT_LIMIT"), 20);
  private static final int CLIENT_PULL_MAX_LIMIT =
      parseIntOrDefault(System.getenv("CLIENT_PULL_MAX_LIMIT"), 80);
  private static final String EVENT_PREFIX = "[EVENT_JSON] ";
  private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("\"event_type\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern KIND_PATTERN = Pattern.compile("\"kind\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TS_PATTERN = Pattern.compile("\"ts\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TRANSCRIPT_PATTERN = Pattern.compile("\"transcript\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern ALERT_PATTERN = Pattern.compile("\"alert\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern OSRM_DISTANCE_PATTERN =
      Pattern.compile("\"distance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern OSRM_DURATION_PATTERN =
      Pattern.compile("\"duration\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern OSM_MAXSPEED_SPEED_PATTERN =
      Pattern.compile("\\\"speed\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern OSM_MAXSPEED_UNIT_PATTERN =
      Pattern.compile("\\\"unit\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern OSRM_TOLL_CLASS_PATTERN =
      Pattern.compile("\\\"classes\\\"\\s*:\\s*\\[[^\\]]*\\\"toll\\\"[^\\]]*\\]");
  private static final Pattern OSRM_FERRY_CLASS_PATTERN =
      Pattern.compile("\\\"classes\\\"\\s*:\\s*\\[[^\\]]*\\\"ferry\\\"[^\\]]*\\]");
  private static final Pattern ALERT_COORD_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");
  private static final String WEATHER_PROVIDER = System.getenv().getOrDefault("WEATHER_PROVIDER", "mock");
  private static final String WAZE_DEEPLINK_BASE_URL =
      System.getenv().getOrDefault("WAZE_DEEPLINK_BASE_URL", "https://waze.com/ul");
  private static final String WAZE_EMBED_BASE_URL =
      System.getenv().getOrDefault("WAZE_EMBED_BASE_URL", "https://embed.waze.com/iframe");
  private static final String WAZE_HAZARDS_API_URL =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_URL", "");
  private static final String WAZE_HAZARDS_API_AUTH_HEADER =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_AUTH_HEADER", "Authorization");
  private static final String WAZE_HAZARDS_API_AUTH_PREFIX =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_AUTH_PREFIX", "Bearer ");
  private static final String WAZE_HAZARDS_API_KEY =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_KEY", "");
  private static final String NOMINATIM_SEARCH_URL =
      System.getenv().getOrDefault("NOMINATIM_SEARCH_URL", "https://nominatim.openstreetmap.org/search");
  private static final double GEOCODE_BIAS_RADIUS_DEGREES =
      parseDouble(System.getenv("GEOCODE_BIAS_RADIUS_DEGREES"), 0.35);
  private static final int SUGGEST_DEFAULT_LIMIT =
      parseIntOrDefault(System.getenv("ADDRESS_SUGGEST_DEFAULT_LIMIT"), 8);
  private static final int SUGGEST_MAX_LIMIT =
      parseIntOrDefault(System.getenv("ADDRESS_SUGGEST_MAX_LIMIT"), 12);
  private static final long SUGGEST_POI_CACHE_TTL_MS =
      parseLongOrDefault(System.getenv("ADDRESS_SUGGEST_POI_CACHE_TTL_MS"), 12000L);
  private static final int SUGGEST_POI_CACHE_MAX_ENTRIES =
      parseIntOrDefault(System.getenv("ADDRESS_SUGGEST_POI_CACHE_MAX_ENTRIES"), 48);
  private static final Pattern ZIP_PREFIX_PATTERN = Pattern.compile("^\\d{3,10}$");
  private static final Pattern COORD_QUERY_PATTERN =
      Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");
  private static final Pattern SCENE_POI_OBJECT_PATTERN =
      Pattern.compile(
          "\\{\"n\":\"((?:\\\\.|[^\"\\\\])*)\",\"k\":\"((?:\\\\.|[^\"\\\\])*)\",\"lat\":(-?\\d+(?:\\.\\d+)?),\"lon\":(-?\\d+(?:\\.\\d+)?)\\}");
  private static final String OSRM_ROUTE_BASE_URL =
      System.getenv().getOrDefault("OSRM_ROUTE_BASE_URL", "https://router.project-osrm.org/route/v1/driving");
  private static final String EXTERNAL_HTTP_USER_AGENT =
      System.getenv().getOrDefault("EXTERNAL_HTTP_USER_AGENT", "scanner-stream-backend/0.1 (self-hosted)");
  private static final int EXTERNAL_HTTP_TIMEOUT_MS =
      parseIntOrDefault(System.getenv("EXTERNAL_HTTP_TIMEOUT_MS"), 6000);
  private static final int ROUTE_ZHS_TILE_ZOOM =
      parseIntOrDefault(System.getenv("ROUTE_ZHS_TILE_ZOOM"), 15);
  private static final int ROUTE_ZHS_H3_RESOLUTION =
      parseIntOrDefault(System.getenv("ROUTE_ZHS_H3_RESOLUTION"), 9);
  private static final int ROUTE_ZHS_S2_LEVEL =
      parseIntOrDefault(System.getenv("ROUTE_ZHS_S2_LEVEL"), 12);
  private static final int[] MAP_ZOOM_LADDER = {15, 13, 11, 9, 7, 5, 3};
  private static final int MAP_CELL_RING_LIMIT =
      parseIntOrDefault(System.getenv("MAP_ZHS_CELL_RING_LIMIT"), 5);
  private static final int MAP_ZHS_H_RESOLUTION =
      parseIntOrDefault(System.getenv("MAP_ZHS_H_RESOLUTION"), 9);
  private static final int MAP_ZHS_S2_LEVEL =
      parseIntOrDefault(System.getenv("MAP_ZHS_S2_LEVEL"), 12);
  private static final int MAP_RENDER_MAX_DIM_PX = 1600;
  private static final double MAP_RENDER_MIN_MPP = 0.1;
  private static final double MAP_RENDER_MAX_MPP = 60000.0;
  private static final String MAP_MODE = "zhs_only";
  private static final double OSM_MAXSPEED_FALLBACK_MPH = 30.0;
  private static final double OSM_MAXSPEED_FALLBACK_MPS = OSM_MAXSPEED_FALLBACK_MPH * 0.44704;
  private static final long OSRM_CACHE_TTL_MS =
      parseLongOrDefault(System.getenv("OSRM_CACHE_TTL_MS"), 5000L);
  private static final int OSRM_CACHE_MAX_ENTRIES =
      parseIntOrDefault(System.getenv("OSRM_CACHE_MAX_ENTRIES"), 512);
  private static final long LLM_STATUS_CACHE_TTL_MS =
      parseLongOrDefault(System.getenv("LLM_STATUS_CACHE_TTL_MS"), 5000L);
  private static final String SELECTOR_PYTHON_BIN =
      System.getenv().getOrDefault("SELECTOR_PYTHON_BIN", repoPath("cop_pipeline/bin/python3"));
  private static final String SELECTOR_SCRIPT_PATH =
      System.getenv().getOrDefault("SELECTOR_SCRIPT_PATH", repoPath("navigation/pipeline/channel_selector.py"));
  private static final String BROADCASTIFY_CATALOG_SCRIPT_PATH =
      System.getenv().getOrDefault("BROADCASTIFY_CATALOG_SCRIPT_PATH", repoPath("navigation/pipeline/broadcastify_catalog_service.py"));
  private static final String BROADCASTIFY_CHANNELS_FILE =
      System.getenv().getOrDefault("BROADCASTIFY_CHANNELS_FILE", repoPath("stack/config/broadcastify_channels.sample.json"));
  private static final String BROADCASTIFY_SELECTOR_CITY =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_CITY", "Sample City");
  private static final String BROADCASTIFY_SELECTOR_COUNTY =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_COUNTY", "Sample County");
  private static final String BROADCASTIFY_SELECTOR_STATE =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_STATE", "Sample State");
  private static final boolean BROADCASTIFY_SELECTOR_LOCK_STATE =
      "true".equalsIgnoreCase(
          System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_LOCK_STATE", "false"));
  private static final String BROADCASTIFY_SELECTOR_DESIRED_TYPES =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_DESIRED_TYPES", "law,dispatch");
  private static final int BROADCASTIFY_SELECTOR_TOP_K =
      parseIntOrDefault(System.getenv("BROADCASTIFY_SELECTOR_TOP_K"), 8);
  private static final int BROADCASTIFY_SELECTOR_PRINT_TOP =
      parseIntOrDefault(System.getenv("BROADCASTIFY_SELECTOR_PRINT_TOP"), 3);
  private static final String BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK", "true");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_MODEL =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_MODEL", "scout-rank");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_URL =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_URL", "http://localhost:11434/api/generate");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT", "8.0");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT", "0.2");
  private static final int HELPER_PROCESS_TIMEOUT_SECONDS =
      parseIntOrDefault(System.getenv("BACKEND_HELPER_TIMEOUT_SECONDS"), 90);
  private static final String LLM_SET_CLIENT_SCRIPT_PATH =
      System.getenv().getOrDefault("LLM_SET_CLIENT_SCRIPT_PATH", repoPath("llm_set_client.py"));
  private static final int LLM_CHAT_HELPER_TIMEOUT_SECONDS =
      parseIntOrDefault(System.getenv("LLM_CHAT_HELPER_TIMEOUT_SECONDS"), 45);
  private static final String STACK_MANAGE_SCRIPT_PATH =
      System.getenv().getOrDefault("STACK_MANAGE_SCRIPT_PATH", repoPath("stack/commands/start_termius_stack.sh"));
  private static final int STACK_MANAGE_TIMEOUT_SECONDS =
      parseIntOrDefault(System.getenv("STACK_MANAGE_TIMEOUT_SECONDS"), 45);
  private static final int STACK_MANAGE_OUTPUT_MAX_CHARS =
      parseIntOrDefault(System.getenv("STACK_MANAGE_OUTPUT_MAX_CHARS"), 24000);
  private static final Set<String> STACK_MANAGE_ALLOWED_ACTIONS =
      Set.of("start", "stop", "restart", "status", "health");
  private static final String OLLAMA_TAGS_URL =
      System.getenv().getOrDefault("OLLAMA_TAGS_URL", "http://localhost:11434/api/tags");
  private static final String LLM_BASE_MODEL =
      System.getenv().getOrDefault("LLM_BASE_MODEL", "llama3.1");
  private static final String[] SCOUT_MODELS = {"scout-core1.0.8", "scout-vet1.0.8", "scout-rank"};
  private static final Pattern MODEL_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  private static final int METRICS_MAX_ITEMS =
      parseIntOrDefault(System.getenv("BACKEND_METRICS_MAX_ITEMS"), 12);
  private static final int GPS_TRACK_MAX_POINTS =
      parseIntOrDefault(System.getenv("BACKEND_GPS_TRACK_MAX_POINTS"), 2000);
  private static final int ERROR_REPORT_MAX_ITEMS =
      parseIntOrDefault(System.getenv("ERROR_REPORT_MAX_ITEMS"), 500);
  private static final int ERROR_REPORT_DEFAULT_LIMIT =
      parseIntOrDefault(System.getenv("ERROR_REPORT_DEFAULT_LIMIT"), 20);
  private static final int ERROR_REPORT_MAX_LIMIT =
      parseIntOrDefault(System.getenv("ERROR_REPORT_MAX_LIMIT"), 120);
  private static final int MAX_QUERY_LENGTH =
      parseIntOrDefault(System.getenv("BACKEND_MAX_QUERY_LENGTH"), 4096);
  private static final int MAX_QUERY_PARAMS =
      parseIntOrDefault(System.getenv("BACKEND_MAX_QUERY_PARAMS"), 64);
  private static final int MAX_QUERY_KEY_LENGTH =
      parseIntOrDefault(System.getenv("BACKEND_MAX_QUERY_KEY_LENGTH"), 128);
  private static final int MAX_QUERY_VALUE_LENGTH =
      parseIntOrDefault(System.getenv("BACKEND_MAX_QUERY_VALUE_LENGTH"), 1024);
  private static final int MAX_REQUEST_BODY_BYTES =
      parseIntOrDefault(System.getenv("BACKEND_MAX_REQUEST_BODY_BYTES"), 65536);
  private static final String CORS_ALLOW_ORIGIN =
      System.getenv().getOrDefault("BACKEND_CORS_ALLOW_ORIGIN", "*");
  private static final boolean RESTRICT_ALL_API_ROUTES =
      !"false".equalsIgnoreCase(System.getenv().getOrDefault("BACKEND_RESTRICT_ALL_APIS", "true"));
  private static final String GLOBAL_API_KEY =
      System.getenv().getOrDefault("BACKEND_GLOBAL_API_KEY", "");
  private static final String GLOBAL_API_KEY_HEADER =
      System.getenv().getOrDefault("BACKEND_GLOBAL_API_KEY_HEADER", "X-Backend-Global-Key");
  private static final String SECURE_PULL_ALLOWLIST_RAW =
      System.getenv().getOrDefault("BACKEND_PULL_ALLOWLIST", "127.0.0.1,::1,localhost");
  private static final String SECURE_PULL_ALLOW_CIDRS_RAW =
      System.getenv()
          .getOrDefault(
              "BACKEND_PULL_ALLOW_CIDRS",
              "100.64.0.0/10,127.0.0.1/32,::1/128,172.16.0.0/12,192.168.0.0/16,10.0.0.0/8");
  private static final String SECURE_PULL_API_KEY =
      System.getenv().getOrDefault("BACKEND_PULL_API_KEY", "");
  private static final String SECURE_PULL_API_KEY_HEADER =
      System.getenv().getOrDefault("BACKEND_PULL_API_KEY_HEADER", "X-Backend-Api-Key");
  private static final String CLIENT_PULL_TOKEN_HEADER =
      System.getenv().getOrDefault("BACKEND_CLIENT_PULL_TOKEN_HEADER", "X-Client-Pull-Token");
  private static final String ANALYTICS_OPT_OUT_HEADER =
      System.getenv().getOrDefault("BACKEND_ANALYTICS_OPT_OUT_HEADER", "X-Client-Analytics-Opt-Out");
  private static final String SHARE_ETA_SIGNING_SECRET =
      initShareEtaSigningSecret(
          System.getenv().getOrDefault("SHARE_ETA_SIGNING_SECRET", ""));
  private static final String SHARE_ETA_PUBLIC_BASE_URL =
      System.getenv().getOrDefault("SHARE_ETA_PUBLIC_BASE_URL", "").trim();
  private static final long SHARE_ETA_TOKEN_TTL_SECONDS =
      clampLong(parseLongOrDefault(System.getenv("SHARE_ETA_TOKEN_TTL_SECONDS"), 1800L), 60L, 86400L);
  private static final int SHARE_ETA_DEST_LABEL_MAX_CHARS =
      parseIntOrDefault(System.getenv("SHARE_ETA_DEST_LABEL_MAX_CHARS"), 96);
  private static final int SHARE_ETA_MAX_ALERT_ITEMS =
      parseIntOrDefault(System.getenv("SHARE_ETA_MAX_ALERT_ITEMS"), 4);
  private static final double SHARE_ETA_NEARBY_RADIUS_METERS =
      parseDouble(System.getenv("SHARE_ETA_NEARBY_RADIUS_METERS"), 30000.0);
  private static final Pattern SHARE_ROUTE_DISTANCE_PATTERN =
      Pattern.compile("\\\"distance_m\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern SHARE_ROUTE_DURATION_PATTERN =
      Pattern.compile("\\\"duration_s\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern SHARE_WEATHER_TEMP_PATTERN =
      Pattern.compile("\\\"temp\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern SHARE_WEATHER_CONDITION_PATTERN =
      Pattern.compile("\\\"condition\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final Set<String> SECURE_PULL_ALLOWED_SOURCES =
      parseLowercaseCsv(SECURE_PULL_ALLOWLIST_RAW);
  private static final List<CidrBlock> SECURE_PULL_ALLOWED_CIDRS =
      parseCidrCsv(SECURE_PULL_ALLOW_CIDRS_RAW);
  private static final Set<String> GLOBAL_PUBLIC_ENDPOINTS =
      Set.of("/api/health", "/api/public/share/eta");
  private static final Set<String> SECURE_PULL_ENDPOINTS =
      Set.of(
          "/api/pipeline/snapshot",
          "/api/pipeline/stream",
          "/api/mobile/client/register",
          "/api/mobile/client/send",
          "/api/mobile/snapshot",
          "/api/mobile/stream",
          "/api/mobile/client/pull",
          "/api/mobile/clients",
          "/api/platform/error-reports/recent",
          "/api/gps/latest",
          "/api/gps/track",
          "/api/gps/triangulation");
  private static final Path ADDRESS_CATALOG_STORE_PATH =
      Path.of(
          System.getenv()
              .getOrDefault(
                  "ADDRESS_CATALOG_STORE_PATH",
                  repoPath("stack/config/address_catalog_store.tsv")));
  private static final Path ERROR_REPORT_STORE_PATH =
      Path.of(
          System.getenv()
              .getOrDefault(
                  "ERROR_REPORT_STORE_PATH",
                  repoPath("stack/config/error_report_store.tsv")));
  private static final Path LOG_PATH =
      Path.of(System.getenv().getOrDefault("PIPELINE_LOG_PATH", "/tmp/pipeline_live_events.log"));
  private static final String HOST = System.getenv().getOrDefault("JAVA_BACKEND_HOST", DEFAULT_HOST);
  private static final int PORT = parsePort(System.getenv("JAVA_BACKEND_PORT"), DEFAULT_PORT);
  private static final Map<String, TimingStats> REQUEST_STATS = new ConcurrentHashMap<>();
  private static final Map<String, TimingStats> HELPER_STATS = new ConcurrentHashMap<>();
  private static final Set<String> ALLOWED_CATALOG_STATE_ABBREVS =
      Set.of(
          "CA",
          "OR",
          "WA",
          "AZ",
          "NM",
          "NV",
          "UT",
          "CO",
          "TX",
          "ND",
          "SD",
          "NE",
          "KS",
          "MN",
          "IA",
          "MO",
          "WI",
          "IL",
          "IN",
          "MI",
          "OH");
  private static final Map<String, String> STATE_NAME_TO_ABBREV =
      Map.ofEntries(
          Map.entry("california", "CA"),
          Map.entry("oregon", "OR"),
          Map.entry("washington", "WA"),
          Map.entry("arizona", "AZ"),
          Map.entry("new mexico", "NM"),
          Map.entry("nevada", "NV"),
          Map.entry("utah", "UT"),
          Map.entry("colorado", "CO"),
          Map.entry("texas", "TX"),
          Map.entry("north dakota", "ND"),
          Map.entry("south dakota", "SD"),
          Map.entry("nebraska", "NE"),
          Map.entry("kansas", "KS"),
          Map.entry("minnesota", "MN"),
          Map.entry("iowa", "IA"),
          Map.entry("missouri", "MO"),
          Map.entry("wisconsin", "WI"),
          Map.entry("illinois", "IL"),
          Map.entry("indiana", "IN"),
          Map.entry("michigan", "MI"),
          Map.entry("ohio", "OH"));
  private static final Map<String, AddressCatalogEntry> ADDRESS_CATALOG = new ConcurrentHashMap<>();
  private static final List<AddressCatalogEntry> CITY_CATALOG_ALIASES = buildCityCatalogAliases();
  private static final Map<String, TimedStringValue> OSRM_ROUTE_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, TimedStringValue> OSRM_ALT_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, TimedStringValue> SUGGEST_POI_SCENE_CACHE = new ConcurrentHashMap<>();
  private static volatile TimedStringValue llmStatusCache = null;
  private static final Object ADDRESS_CATALOG_IO_LOCK = new Object();
  private static final Object GPS_LOCK = new Object();
  private static final Object CLIENT_ROUTE_LOCK = new Object();
  private static final Deque<GpsPoint> GPS_TRACK = new ArrayDeque<>();
  private static final Map<String, GpsPoint> GPS_BY_USER = new ConcurrentHashMap<>();
  private static final Map<String, ClientRoute> CLIENT_ROUTES = new ConcurrentHashMap<>();
  private static final Map<String, Deque<ClientMessage>> CLIENT_MAILBOX = new ConcurrentHashMap<>();
  private static final Deque<ErrorReportEntry> ERROR_REPORTS = new ArrayDeque<>();
  private static final AtomicLong ERROR_REPORT_SEQ = new AtomicLong(1L);
  private static volatile GpsPoint latestGpsPoint = null;
  private static final Object ERROR_REPORT_IO_LOCK = new Object();
  private static final Object ASSISTANT_QUERY_LOCK = new Object();
  private static final Deque<Long> ASSISTANT_QUERY_TIMESTAMPS_MS = new ArrayDeque<>();

  public static void main(String[] args) throws IOException {
    loadAddressCatalogFromDisk();
    loadErrorReportsFromDisk();
    ProprietaryMapEngine.init();
    HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
    registerContext(server, "/api/health", new HealthHandler());
    registerContext(server, "/api/pipeline/snapshot", new SnapshotHandler());
    registerContext(server, "/api/pipeline/stream", new StreamHandler());
    registerContext(server, "/api/route/weather", new WeatherHandler());
    registerContext(server, "/api/platform/weather/forecast", new PlatformWeatherHandler());
    registerContext(server, "/api/platform/waze/route", new WazeRouteHandler());
    registerContext(server, "/api/platform/route/local", new LocalRouteHandler());
    registerContext(server, "/api/platform/route/options", new RouteOptionsHandler());
    registerContext(server, "/api/platform/geocode", new GeocodeHandler());
    registerContext(server, "/api/platform/address-catalog/resolve", new AddressCatalogResolveHandler());
    registerContext(server, "/api/platform/address-catalog/suggest", new AddressCatalogSuggestHandler());
    registerContext(server, "/api/platform/address-catalog/upsert", new AddressCatalogUpsertHandler());
    registerContext(server, "/api/platform/address-catalog/export", new AddressCatalogExportHandler());
    registerContext(server, "/api/platform/error-reports/submit", new ErrorReportSubmitHandler());
    registerContext(server, "/api/platform/error-reports/recent", new ErrorReportRecentHandler());
    registerContext(server, "/api/platform/alerts/clusters", new AlertClustersHandler());
    registerContext(server, "/api/gps/update", new GpsUpdateHandler());
    registerContext(server, "/api/gps/latest", new GpsLatestHandler());
    registerContext(server, "/api/gps/track", new GpsTrackHandler());
    registerContext(server, "/api/gps/triangulation", new GpsTriangulationHandler());
    registerContext(server, "/api/platform/broadcastify/select", new BroadcastifySelectHandler());
    registerContext(server, "/api/platform/broadcastify/catalog", new BroadcastifyCatalogHandler());
    registerContext(server, "/api/platform/providers/status", new ProviderStatusHandler());
    registerContext(server, "/api/platform/share/eta/create", new ShareEtaCreateHandler());
    registerContext(server, "/api/public/share/eta", new ShareEtaPublicHandler());
    registerContext(server, "/api/platform/assistant/chat", new AssistantChatHandler());
    registerContext(server, "/api/platform/dev/stack/manage", new DevStackManageHandler());
    registerContext(server, "/api/platform/llm/status", new LlmStatusHandler());
    registerContext(server, "/api/mobile/bootstrap", new MobileBootstrapHandler());
    registerContext(server, "/api/mobile/snapshot", new MobileSnapshotHandler());
    registerContext(server, "/api/mobile/stream", new MobileStreamHandler());
    registerContext(server, "/api/mobile/client/register", new MobileClientRegisterHandler());
    registerContext(server, "/api/mobile/client/send", new MobileClientSendHandler());
    registerContext(server, "/api/mobile/client/pull", new MobileClientPullHandler());
    registerContext(server, "/api/mobile/clients", new MobileClientsHandler());
    registerContext(server, "/api/map/scene", new MapSceneHandler());
    registerContext(server, "/api/map/render", new MapRenderHandler());
    registerContext(server, "/api/map/status", new MapStatusHandler());
    registerContext(server, "/api/map/shard", new MapShardHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    System.out.printf(
        Locale.ROOT,
        "[java-backend] serving on http://%s:%d%n[java-backend] pipeline log source: %s%n",
        HOST,
        PORT,
        LOG_PATH);
    server.start();
  }

  private static String formatScaledEtaDuration(double durationSeconds) {
    if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
      return "under 1 min";
    }
    long totalMinutes = Math.max(1L, Math.round(durationSeconds / 60.0));
    long days = totalMinutes / (24L * 60L);
    long hours = (totalMinutes % (24L * 60L)) / 60L;
    long minutes = totalMinutes % 60L;
    List<String> parts = new ArrayList<>();
    if (days > 0) {
      parts.add(days + (days == 1 ? " day" : " days"));
    }
    if (hours > 0) {
      parts.add(hours + (hours == 1 ? " hr" : " hrs"));
    }
    if (minutes > 0 || parts.isEmpty()) {
      parts.add(minutes + (minutes == 1 ? " min" : " mins"));
    }
    return String.join(" ", parts);
  }

  private static boolean registerAssistantQueryAndDetectHighLoad() {
    long now = System.currentTimeMillis();
    synchronized (ASSISTANT_QUERY_LOCK) {
      ASSISTANT_QUERY_TIMESTAMPS_MS.addLast(now);
      while (!ASSISTANT_QUERY_TIMESTAMPS_MS.isEmpty()) {
        long oldest = ASSISTANT_QUERY_TIMESTAMPS_MS.peekFirst();
        if ((now - oldest) <= ASSISTANT_HIGH_QUERY_WINDOW_MS) {
          break;
        }
        ASSISTANT_QUERY_TIMESTAMPS_MS.removeFirst();
      }
      return ASSISTANT_QUERY_TIMESTAMPS_MS.size() >= ASSISTANT_HIGH_QUERY_THRESHOLD;
    }
  }

  private static void appendContextSection(
      StringBuilder context, String label, String rawValue, int maxChars) {
    if (rawValue == null || rawValue.isBlank()) {
      return;
    }
    String value = rawValue.trim();
    if (maxChars > 0 && value.length() > maxChars) {
      value = value.substring(0, maxChars);
    }
    context.append("\n").append(label).append(": ").append(value);
  }

  private static Map<String, String> buildAssistantToolQuery(Map<String, String> query, String body) {
    Map<String, String> merged = new HashMap<>(query);
    String[] scalarKeys = new String[] {"origin_lat", "origin_lon", "dest_lat", "dest_lon"};
    for (String key : scalarKeys) {
      String value = extractStringFieldByName(body, key, merged.getOrDefault(key, "")).trim();
      if (!value.isBlank()) {
        merged.put(key, value);
      }
    }
    if (!merged.containsKey("lat")) {
      merged.put("lat", merged.getOrDefault("origin_lat", ""));
    }
    if (!merged.containsKey("lon")) {
      merged.put("lon", merged.getOrDefault("origin_lon", ""));
    }
    if (!merged.containsKey("end")) {
      String destinationLabel =
          extractStringFieldByName(body, "destination_label", merged.getOrDefault("destination_label", "")).trim();
      if (!destinationLabel.isBlank()) {
        merged.put("end", destinationLabel);
      }
    }
    return merged;
  }

  private static String buildScoutToolDefinitionsText() {
    return "get_weather(endpoint=/api/platform/weather/forecast; purpose=localized weather + driving impact); "
        + "check_waze(endpoint=/api/platform/waze/route; purpose=deep-link route intent and coordinates); "
        + "find_route(endpoint=/api/platform/route/local + /api/platform/route/options; purpose=route geometry/alternatives); "
        + "cluster_alerts(endpoint=/api/platform/alerts/clusters; purpose=hazard cluster summaries); "
        + "broadcastify_context(endpoint=/api/mobile/snapshot + /api/platform/broadcastify/select; purpose=live scanner transcript + channel context).";
  }

  private static String buildScoutToolObservations(Map<String, String> toolQuery, String body) {
    CompletableFuture<String> weatherFuture =
        CompletableFuture.supplyAsync(() -> buildWeatherJson(toolQuery));
    CompletableFuture<String> wazeFuture =
        CompletableFuture.supplyAsync(() -> wazeRouteToJson(buildWazeRoute(toolQuery)));
    CompletableFuture<String> routeFuture =
        CompletableFuture.supplyAsync(() -> buildStandaloneLocalRouteJson(toolQuery));
    CompletableFuture<String> routeOptionsFuture =
        CompletableFuture.supplyAsync(() -> buildRouteOptionsJson(toolQuery));
    CompletableFuture<String> alertsFuture =
        CompletableFuture.supplyAsync(() -> buildAlertClustersJson(toolQuery));
    CompletableFuture<String> mobileSnapshotFuture =
        CompletableFuture.supplyAsync(BackendServer::compactAssistantMobileSnapshotJson);
    StringBuilder observations = new StringBuilder();
    appendContextSection(
        observations,
        "weather",
        awaitToolFuture(weatherFuture, "weather_unavailable"),
        1800);
    appendContextSection(
        observations,
        "waze",
        awaitToolFuture(wazeFuture, "waze_unavailable"),
        1800);
    appendContextSection(
        observations,
        "route",
        awaitToolFuture(routeFuture, "route_unavailable"),
        2200);
    appendContextSection(
        observations,
        "route_options",
        awaitToolFuture(routeOptionsFuture, "route_options_unavailable"),
        2200);
    appendContextSection(
        observations,
        "alerts",
        awaitToolFuture(alertsFuture, "alerts_unavailable"),
        2200);
    appendContextSection(
        observations,
        "mobile_snapshot",
        awaitToolFuture(mobileSnapshotFuture, "mobile_snapshot_unavailable"),
        2600);
    appendContextSection(
        observations,
        "provided_alert_clusters_summary",
        extractStringFieldByName(body, "alert_clusters_summary", toolQuery.getOrDefault("alert_clusters_summary", "")),
        1200);
    appendContextSection(
        observations,
        "provided_local_call_context",
        extractStringFieldByName(body, "local_call_context", toolQuery.getOrDefault("local_call_context", "")),
        1800);
    return observations.toString();
  }

  private static String awaitToolFuture(CompletableFuture<String> future, String fallbackValue) {
    if (future == null) {
      return fallbackValue;
    }
    try {
      String value = future.get(ASSISTANT_TOOL_FANOUT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (value == null || value.isBlank()) {
        return fallbackValue;
      }
      return value;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return fallbackValue;
    } catch (ExecutionException | TimeoutException ex) {
      return fallbackValue;
    }
  }

  private static String compactAssistantMobileSnapshotJson() {
    SnapshotData snapshot = buildSnapshotData();
    List<EventInfo> events = snapshot.events;
    int fromIndex = Math.max(0, events.size() - ASSISTANT_STREAM_EVENT_RETURN_LIMIT);
    StringBuilder compactEvents = new StringBuilder("[");
    for (int i = fromIndex; i < events.size(); i++) {
      if (i > fromIndex) {
        compactEvents.append(",");
      }
      compactEvents.append(compactMobileEventJson(events.get(i)));
    }
    compactEvents.append("]");
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"metrics\":" + metricMapToJson(snapshot.metrics) + ","
        + "\"event_type_counts\":" + eventTypeCountsToJson(snapshot.eventTypeCounts) + ","
        + "\"events\":" + compactEvents
        + "}";
  }

  private static boolean extractBooleanField(String json, String fieldName, boolean fallback) {
    Pattern pattern =
        Pattern.compile(
            "\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*(?:\\\"(true|false|1|0|yes|no|on|off)\\\"|(true|false|1|0|yes|no|on|off))",
            Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (!matcher.find()) {
      return fallback;
    }
    String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    return parseFlexibleBoolean(raw, fallback);
  }

  private static void enforceGlobalApiAccess(String path, HttpExchange exchange) {
    if (!RESTRICT_ALL_API_ROUTES || GLOBAL_PUBLIC_ENDPOINTS.contains(path)) {
      return;
    }
    String remoteAddress = remoteAddressFromExchange(exchange);
    if (!isAllowedPullSource(remoteAddress)) {
      throw new IllegalArgumentException("forbidden_network_source");
    }
    if (!GLOBAL_API_KEY.isBlank()) {
      String received = exchange.getRequestHeaders().getFirst(GLOBAL_API_KEY_HEADER);
      if (received == null || !GLOBAL_API_KEY.equals(received.trim())) {
        throw new IllegalArgumentException("invalid_global_api_key");
      }
    }
  }

  private static Set<String> parseLowercaseCsv(String raw) {
    Set<String> out = new HashSet<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    String[] parts = raw.split(",");
    for (String part : parts) {
      String token = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
      if (!token.isBlank()) {
        out.add(token);
      }
    }
    return out;
  }

  private static boolean isAllowedCatalogStateAbbrev(String stateAbbrev) {
    if (stateAbbrev == null || stateAbbrev.isBlank()) {
      return false;
    }
    return ALLOWED_CATALOG_STATE_ABBREVS.contains(stateAbbrev.trim().toUpperCase(Locale.ROOT));
  }

  private static boolean matchesAllowedRegionCoordinates(double lat, double lon) {
    if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
      return false;
    }
    boolean westCoast = lat >= 31.0 && lat <= 49.5 && lon >= -125.5 && lon <= -116.0;
    boolean southWest = lat >= 25.0 && lat <= 42.5 && lon >= -120.5 && lon <= -93.0;
    boolean midWest = lat >= 36.0 && lat <= 49.5 && lon >= -104.5 && lon <= -80.0;
    return westCoast || southWest || midWest;
  }

  private static String stateAbbrevFromCatalogText(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = normalizeCatalogQuery(value);
    if (normalized.isBlank()) {
      return "";
    }
    List<String> commaParts = new ArrayList<>();
    for (String part : value.split(",")) {
      String token = normalizeCatalogQuery(part);
      if (!token.isBlank()) {
        commaParts.add(token);
      }
    }
    for (int i = commaParts.size() - 1; i >= 0; i--) {
      String token = commaParts.get(i);
      if (token.matches("[a-z]{2}")) {
        return token.toUpperCase(Locale.ROOT);
      }
      String fromName = STATE_NAME_TO_ABBREV.get(token);
      if (fromName != null) {
        return fromName;
      }
    }
    List<String> words = splitWords(normalized);
    if (words.isEmpty()) {
      return "";
    }
    String tail = words.get(words.size() - 1);
    if (tail.matches("[a-z]{2}")) {
      return tail.toUpperCase(Locale.ROOT);
    }
    for (int len = Math.min(2, words.size()); len >= 1; len--) {
      String phrase = String.join(" ", words.subList(words.size() - len, words.size()));
      String fromName = STATE_NAME_TO_ABBREV.get(phrase);
      if (fromName != null) {
        return fromName;
      }
    }
    return "";
  }

  private static boolean isAllowedRegionalCatalogEntry(AddressCatalogEntry entry) {
    if (entry == null) {
      return false;
    }
    String stateAbbrev = stateAbbrevFromCatalogText(entry.displayName);
    if (stateAbbrev.isBlank()) {
      stateAbbrev = stateAbbrevFromCatalogText(entry.queryKey);
    }
    if (!stateAbbrev.isBlank()) {
      return isAllowedCatalogStateAbbrev(stateAbbrev);
    }
    return matchesAllowedRegionCoordinates(entry.lat, entry.lon);
  }

  private static boolean isAllowedRegionalSuggestionEntry(SuggestionEntry entry) {
    if (entry == null) {
      return false;
    }
    String stateAbbrev = stateAbbrevFromCatalogText(entry.displayName);
    if (!stateAbbrev.isBlank()) {
      return isAllowedCatalogStateAbbrev(stateAbbrev);
    }
    return matchesAllowedRegionCoordinates(entry.lat, entry.lon);
  }

  private static AddressCatalogEntry lookupNamedCommunityCityState(
      String rawQuery, String queryKey, double biasLat, double biasLon, boolean hasBias) {
    if (!isCityStateAbbrevQuery(queryKey)) {
      return null;
    }
    String cityPart = cityPartFromCityStateQuery(queryKey);
    String stateAbbrev = extractTrailingStateAbbrev(queryKey);
    if (cityPart.isBlank() || stateAbbrev.isBlank()) {
      return null;
    }
    if (!isAllowedCatalogStateAbbrev(stateAbbrev)) {
      return null;
    }
    String baseUrl = NOMINATIM_SEARCH_URL + "?format=json&limit=5&q=" + urlEncode(rawQuery);
    String geocodeBody = null;
    boolean bounded = false;
    if (hasBias) {
      String viewbox =
          trimDouble(biasLon - GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLat - GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLon + GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLat + GEOCODE_BIAS_RADIUS_DEGREES);
      geocodeBody = httpGetExternal(baseUrl + "&viewbox=" + viewbox + "&bounded=1");
      bounded = geocodeBody != null && looksLikeJson(geocodeBody) && !"[]".equals(geocodeBody.trim());
    }
    if (!bounded) {
      geocodeBody = httpGetExternal(baseUrl);
    }
    List<SuggestionEntry> geocodeEntries = parseGeocodeSuggestionEntries(geocodeBody, 5);
    if (geocodeEntries.isEmpty()) {
      return null;
    }
    SuggestionEntry best = geocodeEntries.get(0);
    if (!isAllowedRegionalSuggestionEntry(best)) {
      return null;
    }
    return new AddressCatalogEntry(
        queryKey,
        toTitleCaseWords(cityPart) + ", " + stateAbbrev,
        "city_catalog",
        best.lat,
        best.lon,
        System.currentTimeMillis());
  }

  private static boolean isSpecificAddressDisplayName(String displayName) {
    String normalized = normalizeSuggestToken(displayName);
    if (normalized.isEmpty()) {
      return false;
    }
    return normalized.matches(".*\\d+.*");
  }
  private static boolean isValidCityCatalogQueryKey(String queryKey) {
    String normalized = normalizeSuggestToken(queryKey);
    if (normalized.isEmpty()) {
      return false;
    }
    if (normalized.contains(" in ") || normalized.contains(" near ")) {
      return false;
    }
    if (normalized.matches(".*\\d+.*")) {
      return false;
    }
    return isCityStateAbbrevQuery(normalized);
  }

  private static boolean isCityCatalogEntry(AddressCatalogEntry entry) {
    return entry != null
        && "city_catalog".equals(entry.source)
        && isValidCityCatalogQueryKey(entry.queryKey);
  }

  private static String extractTrailingStateAbbrev(String queryKey) {
    if (queryKey == null || queryKey.isBlank()) {
      return "";
    }
    List<String> tokens = splitWords(queryKey);
    if (tokens.size() < 2) {
      return "";
    }
    String tail = tokens.get(tokens.size() - 1);
    if (!tail.matches("[a-z]{2}")) {
      return "";
    }
    return tail.toUpperCase(Locale.ROOT);
  }

  private static boolean isCityStateAbbrevQuery(String queryKey) {
    return !extractTrailingStateAbbrev(queryKey).isBlank();
  }

  private static String cityPartFromCityStateQuery(String queryKey) {
    List<String> tokens = splitWords(queryKey);
    if (tokens.size() < 2) {
      return "";
    }
    return String.join(" ", tokens.subList(0, tokens.size() - 1)).trim();
  }

  private static String toTitleCaseWords(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    List<String> tokens = splitWords(value);
    List<String> titled = new ArrayList<>();
    for (String token : tokens) {
      if (token.length() == 1) {
        titled.add(token.toUpperCase(Locale.ROOT));
      } else {
        titled.add(
            token.substring(0, 1).toUpperCase(Locale.ROOT)
                + token.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return String.join(" ", titled);
  }

  private static List<AddressCatalogEntry> buildCombinedAddressCatalogEntries() {
    Map<String, AddressCatalogEntry> merged = new LinkedHashMap<>();
    for (AddressCatalogEntry entry : ADDRESS_CATALOG.values()) {
      if (!isAllowedRegionalCatalogEntry(entry)) {
        continue;
      }
      merged.put(entry.queryKey, entry);
    }
    for (AddressCatalogEntry cityEntry : CITY_CATALOG_ALIASES) {
      if (!isAllowedRegionalCatalogEntry(cityEntry)) {
        continue;
      }
      merged.putIfAbsent(cityEntry.queryKey, cityEntry);
    }
    return new ArrayList<>(merged.values());
  }

  private static List<String> splitTopLevelValues(String csvLike) {
    List<String> out = new ArrayList<>();
    if (csvLike == null || csvLike.isBlank()) {
      return out;
    }
    int braceDepth = 0;
    int bracketDepth = 0;
    boolean inString = false;
    int tokenStart = 0;
    for (int i = 0; i < csvLike.length(); i++) {
      char ch = csvLike.charAt(i);
      if (ch == '\"' && (i == 0 || csvLike.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        braceDepth++;
      } else if (ch == '}') {
        braceDepth = Math.max(0, braceDepth - 1);
      } else if (ch == '[') {
        bracketDepth++;
      } else if (ch == ']') {
        bracketDepth = Math.max(0, bracketDepth - 1);
      } else if (ch == ',' && braceDepth == 0 && bracketDepth == 0) {
        String token = csvLike.substring(tokenStart, i).trim();
        if (!token.isEmpty()) {
          out.add(token);
        }
        tokenStart = i + 1;
      }
    }
    String tail = csvLike.substring(tokenStart).trim();
    if (!tail.isEmpty()) {
      out.add(tail);
    }
    return out;
  }

  private static double parseMaxspeedTokenMps(String token) {
    if (token == null) {
      return Double.NaN;
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty() || "null".equals(trimmed)) {
      return Double.NaN;
    }
    if (trimmed.startsWith("{")) {
      Matcher speedMatcher = OSM_MAXSPEED_SPEED_PATTERN.matcher(trimmed);
      if (!speedMatcher.find()) {
        return Double.NaN;
      }
      double speed;
      try {
        speed = Double.parseDouble(speedMatcher.group(1));
      } catch (NumberFormatException ignored) {
        return Double.NaN;
      }
      Matcher unitMatcher = OSM_MAXSPEED_UNIT_PATTERN.matcher(trimmed);
      String unit = unitMatcher.find() ? unitMatcher.group(1).toLowerCase(Locale.ROOT) : "km/h";
      return unit.contains("mph") ? speed * 0.44704 : speed / 3.6;
    }
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      String unquoted = trimmed.substring(1, trimmed.length() - 1).toLowerCase(Locale.ROOT);
      Matcher numericMatcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(unquoted);
      if (!numericMatcher.find()) {
        return Double.NaN;
      }
      double speed;
      try {
        speed = Double.parseDouble(numericMatcher.group(1));
      } catch (NumberFormatException ignored) {
        return Double.NaN;
      }
      return unquoted.contains("mph") ? speed * 0.44704 : speed / 3.6;
    }
    try {
      return Double.parseDouble(trimmed) / 3.6;
    } catch (NumberFormatException ignored) {
      return Double.NaN;
    }
  }

  private static SpeedLimitEtaEstimate estimateEtaFromMaxspeed(
      String routeObject, double distanceMeters, double durationSeconds) {
    if (!Double.isFinite(distanceMeters) || distanceMeters <= 0) {
      return new SpeedLimitEtaEstimate(Math.max(0.0, durationSeconds), 0.0, "duration_fallback");
    }
    String maxspeedArray = extractJsonArrayContent(routeObject, "maxspeed");
    List<String> tokens = splitTopLevelValues(maxspeedArray);
    if (tokens.isEmpty()) {
      return new SpeedLimitEtaEstimate(distanceMeters / OSM_MAXSPEED_FALLBACK_MPS, 0.0, "fallback_30mph");
    }
    int knownCount = 0;
    double speedMpsSum = 0.0;
    for (String token : tokens) {
      double speedMps = parseMaxspeedTokenMps(token);
      if (Double.isFinite(speedMps) && speedMps > 0.5) {
        speedMpsSum += speedMps;
        knownCount++;
      } else {
        speedMpsSum += OSM_MAXSPEED_FALLBACK_MPS;
      }
    }
    double avgSpeedMps = speedMpsSum / Math.max(1, tokens.size());
    double etaSeconds = distanceMeters / Math.max(0.5, avgSpeedMps);
    if (!Double.isFinite(etaSeconds) || etaSeconds <= 0) {
      etaSeconds = Math.max(0.0, durationSeconds);
    }
    double coverage = knownCount / (double) Math.max(1, tokens.size());
    String source = knownCount > 0 ? "osm_maxspeed_blended" : "fallback_30mph";
    return new SpeedLimitEtaEstimate(etaSeconds, coverage, source);
  }


  private static List<CidrBlock> parseCidrCsv(String raw) {
    List<CidrBlock> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    String[] parts = raw.split(",");
    for (String part : parts) {
      CidrBlock block = parseCidrBlock(part == null ? "" : part.trim());
      if (block != null) {
        out.add(block);
      }
    }
    return out;
  }

  private static CidrBlock parseCidrBlock(String cidr) {
    if (cidr == null || cidr.isBlank()) {
      return null;
    }
    String[] split = cidr.split("/", 2);
    if (split.length != 2) {
      return null;
    }
    int prefix = parseIntOrDefault(split[1].trim(), -1);
    if (prefix < 0) {
      return null;
    }
    try {
      byte[] network = InetAddress.getByName(split[0].trim()).getAddress();
      if (prefix > network.length * 8) {
        return null;
      }
      return new CidrBlock(network, prefix);
    } catch (UnknownHostException ex) {
      return null;
    }
  }

  private static String normalizeIpLiteral(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int zoneIdx = normalized.indexOf('%');
    if (zoneIdx >= 0) {
      normalized = normalized.substring(0, zoneIdx);
    }
    return normalized;
  }

  private static boolean matchesCidr(byte[] address, byte[] network, int prefixBits) {
    int fullBytes = prefixBits / 8;
    int remBits = prefixBits % 8;
    for (int i = 0; i < fullBytes; i++) {
      if (address[i] != network[i]) {
        return false;
      }
    }
    if (remBits == 0) {
      return true;
    }
    int mask = 0xFF << (8 - remBits);
    return (address[fullBytes] & mask) == (network[fullBytes] & mask);
  }

  private static boolean ipInAllowedCidrs(String normalizedIp) {
    try {
      byte[] ip = InetAddress.getByName(normalizedIp).getAddress();
      for (CidrBlock block : SECURE_PULL_ALLOWED_CIDRS) {
        if (ip.length != block.networkBytes.length) {
          continue;
        }
        if (matchesCidr(ip, block.networkBytes, block.prefixBits)) {
          return true;
        }
      }
      return false;
    } catch (UnknownHostException ex) {
      return false;
    }
  }
  private static int requestValidationStatus(String reason) {
    if ("query_too_large".equals(reason)) {
      return 414;
    }
    if ("body_too_large".equals(reason)) {
      return 413;
    }
    if ("forbidden_pull_source".equals(reason) || "invalid_pull_api_key".equals(reason)) {
      return 403;
    }
    if ("forbidden_network_source".equals(reason) || "invalid_global_api_key".equals(reason)) {
      return 403;
    }
    if ("invalid_client_pull_token".equals(reason)) {
      return 403;
    }
    return 400;
  }

  private static void validateRawQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return;
    }
    if (rawQuery.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("query_too_large");
    }
  }

  private static void enforceBodySizeHeaders(HttpExchange exchange) {
    String method = exchange.getRequestMethod();
    if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) {
      return;
    }
    String rawLen = exchange.getRequestHeaders().getFirst("Content-Length");
    if (rawLen == null || rawLen.isBlank()) {
      return;
    }
    long declaredLength = parseLongOrDefault(rawLen.trim(), -1L);
    if (declaredLength > MAX_REQUEST_BODY_BYTES) {
      throw new IllegalArgumentException("body_too_large");
    }
  }

  private static void enforceSecurePullAccess(String path, HttpExchange exchange) {
    if (!SECURE_PULL_ENDPOINTS.contains(path)) {
      return;
    }
    String remoteAddress = remoteAddressFromExchange(exchange);
    if (!isAllowedPullSource(remoteAddress)) {
      throw new IllegalArgumentException("forbidden_pull_source");
    }
    if (!SECURE_PULL_API_KEY.isBlank()) {
      String received = exchange.getRequestHeaders().getFirst(SECURE_PULL_API_KEY_HEADER);
      if (received == null || !SECURE_PULL_API_KEY.equals(received.trim())) {
        throw new IllegalArgumentException("invalid_pull_api_key");
      }
    }
  }

  private static boolean isAllowedPullSource(String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isBlank()) {
      return false;
    }
    String normalized = normalizeIpLiteral(remoteAddress);
    if (SECURE_PULL_ALLOWED_SOURCES.contains(normalized)) {
      return true;
    }
    if ("::1".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized)) {
      return true;
    }
    return ipInAllowedCidrs(normalized);
  }
  private static final class SuggestionEntry {
    private final String displayName;
    private final String source;
    private final double lat;
    private final double lon;
    private final long updatedAtMs;

    private SuggestionEntry(
        String displayName, String source, double lat, double lon, long updatedAtMs) {
      this.displayName = displayName;
      this.source = source;
      this.lat = lat;
      this.lon = lon;
      this.updatedAtMs = updatedAtMs;
    }
  }
  private static final class ClientRoute {
    private final String clientId;
    private final String userId;
    private final String source;
    private final String sessionId;
    private final String remoteAddr;
    private final boolean analyticsOptOut;
    private final String pullToken;
    private final long pullTokenIssuedAtMs;
    private final long lastSeenMs;

    private ClientRoute(
        String clientId,
        String userId,
        String source,
        String sessionId,
        String remoteAddr,
        boolean analyticsOptOut,
        String pullToken,
        long pullTokenIssuedAtMs,
        long lastSeenMs) {
      this.clientId = clientId;
      this.userId = userId;
      this.source = source;
      this.sessionId = sessionId;
      this.remoteAddr = remoteAddr;
      this.analyticsOptOut = analyticsOptOut;
      this.pullToken = pullToken;
      this.pullTokenIssuedAtMs = pullTokenIssuedAtMs;
      this.lastSeenMs = lastSeenMs;
    }
  }

  private static final class CidrBlock {
    private final byte[] networkBytes;
    private final int prefixBits;

    private CidrBlock(byte[] networkBytes, int prefixBits) {
      this.networkBytes = networkBytes;
      this.prefixBits = prefixBits;
    }
  }

  private static final class ClientMessage {
    private final String payloadJson;
    private final long enqueuedAtMs;

    private ClientMessage(String payloadJson, long enqueuedAtMs) {
      this.payloadJson = payloadJson;
      this.enqueuedAtMs = enqueuedAtMs;
    }
  }

  private static final class TimedStringValue {
    private final String value;
    private final long expiresAtMs;

    private TimedStringValue(String value, long expiresAtMs) {
      this.value = value;
      this.expiresAtMs = expiresAtMs;
    }
  }
  private static final class RouteNode {
    private final double lat;
    private final double lon;

    private RouteNode(double lat, double lon) {
      this.lat = lat;
      this.lon = lon;
    }
  }
  private static final class ZhsCellData {
    private final String zKey;
    private final int zZoom;
    private final int zX;
    private final int zY;
    private final String hKey;
    private final int hResolution;
    private final long hQ;
    private final long hR;
    private final String sKey;
    private final int sLevel;
    private final int sFace;
    private final long sI;
    private final long sJ;
    private final String unifiedKey;

    private ZhsCellData(
        String zKey,
        int zZoom,
        int zX,
        int zY,
        String hKey,
        int hResolution,
        long hQ,
        long hR,
        String sKey,
        int sLevel,
        int sFace,
        long sI,
        long sJ) {
      this.zKey = zKey;
      this.zZoom = zZoom;
      this.zX = zX;
      this.zY = zY;
      this.hKey = hKey;
      this.hResolution = hResolution;
      this.hQ = hQ;
      this.hR = hR;
      this.sKey = sKey;
      this.sLevel = sLevel;
      this.sFace = sFace;
      this.sI = sI;
      this.sJ = sJ;
      this.unifiedKey = zKey + "|" + hKey + "|" + sKey;
    }
  }
  private static final class AddressCatalogEntry {
    private final String queryKey;
    private final String displayName;
    private final String source;
    private final double lat;
    private final double lon;
    private final long updatedAtMs;

    private AddressCatalogEntry(
        String queryKey, String displayName, String source, double lat, double lon, long updatedAtMs) {
      this.queryKey = queryKey;
      this.displayName = displayName;
      this.source = source;
      this.lat = lat;
      this.lon = lon;
      this.updatedAtMs = updatedAtMs;
    }
  }
  private static final class CityCatalogSeed {
    private final String cityName;
    private final String stateAbbrev;
    private final String stateName;
    private final double lat;
    private final double lon;

    private CityCatalogSeed(
        String cityName, String stateAbbrev, String stateName, double lat, double lon) {
      this.cityName = cityName;
      this.stateAbbrev = stateAbbrev;
      this.stateName = stateName;
      this.lat = lat;
      this.lon = lon;
    }
  }
  private static final class ErrorReportEntry {
    private final String id;
    private final String ts;
    private final String userId;
    private final String clientId;
    private final String source;
    private final String severity;
    private final String message;
    private final String details;
    private final long createdAtMs;

    private ErrorReportEntry(
        String id,
        String ts,
        String userId,
        String clientId,
        String source,
        String severity,
        String message,
        String details,
        long createdAtMs) {
      this.id = id;
      this.ts = ts;
      this.userId = userId;
      this.clientId = clientId;
      this.source = source;
      this.severity = severity;
      this.message = message;
      this.details = details;
      this.createdAtMs = createdAtMs;
    }
  }

  private static final class RouteAlternative {
    private final List<RouteNode> nodes;
    private final double distanceMeters;
    private final double durationSeconds;
    private final double etaSpeedLimitSeconds;
    private final double speedLimitCoverage;
    private final boolean hasTollHint;
    private final boolean hasFerryHint;

    private RouteAlternative(
        List<RouteNode> nodes,
        double distanceMeters,
        double durationSeconds,
        double etaSpeedLimitSeconds,
        double speedLimitCoverage,
        boolean hasTollHint,
        boolean hasFerryHint) {
      this.nodes = nodes;
      this.distanceMeters = distanceMeters;
      this.durationSeconds = durationSeconds;
      this.etaSpeedLimitSeconds = etaSpeedLimitSeconds;
      this.speedLimitCoverage = speedLimitCoverage;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
    }
  }
  private static final class SpeedLimitEtaEstimate {
    private final double etaSeconds;
    private final double coverage;
    private final String source;

    private SpeedLimitEtaEstimate(double etaSeconds, double coverage, String source) {
      this.etaSeconds = etaSeconds;
      this.coverage = coverage;
      this.source = source;
    }
  }
  private static final class GpsPoint {
    private final String ts;
    private final String userId;
    private final String source;
    private final long seq;
    private final double lat;
    private final double lon;
    private final double accuracy;
    private final double speed;
    private final double heading;
    private final long receivedAtMs;

    private GpsPoint(
        String ts,
        String userId,
        String source,
        long seq,
        double lat,
        double lon,
        double accuracy,
        double speed,
        double heading,
        long receivedAtMs) {
      this.ts = ts;
      this.userId = userId;
      this.source = source;
      this.seq = seq;
      this.lat = lat;
      this.lon = lon;
      this.accuracy = accuracy;
      this.speed = speed;
      this.heading = heading;
      this.receivedAtMs = receivedAtMs;
    }
  }

  private static final class TimingStats {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong totalMs = new AtomicLong();
    private final AtomicLong maxMs = new AtomicLong();

    private void record(long durationMs, boolean success) {
      count.incrementAndGet();
      if (!success) {
        errorCount.incrementAndGet();
      }
      totalMs.addAndGet(durationMs);
      maxMs.getAndUpdate(prev -> Math.max(prev, durationMs));
    }
  }

  private static String timingStatsToJson(Map<String, TimingStats> statsMap) {
    StringBuilder sb = new StringBuilder("{");
    List<Map.Entry<String, TimingStats>> entries = new ArrayList<>(statsMap.entrySet());
    entries.sort(Comparator.comparing(Map.Entry::getKey));
    int emitted = 0;
    for (Map.Entry<String, TimingStats> entry : entries) {
      if (emitted >= METRICS_MAX_ITEMS) {
        break;
      }
      if (emitted > 0) {
        sb.append(",");
      }
      TimingStats stats = entry.getValue();
      long count = stats.count.get();
      long total = stats.totalMs.get();
      long avg = count == 0 ? 0 : Math.round((double) total / (double) count);
      sb.append("\"").append(jsonEscape(entry.getKey())).append("\":{")
          .append("\"count\":").append(count).append(",")
          .append("\"errors\":").append(stats.errorCount.get()).append(",")
          .append("\"avg_ms\":").append(avg).append(",")
          .append("\"max_ms\":").append(stats.maxMs.get())
          .append("}");
      emitted += 1;
    }
    sb.append("}");
    return sb.toString();
  }
  private static void registerContext(HttpServer server, String path, HttpHandler handler) {
    server.createContext(path, exchange -> {
      long started = System.nanoTime();
      boolean success = false;
      try {
        if (handleCorsPreflight(exchange)) {
          success = true;
          return;
        }
        String rawQuery = exchange.getRequestURI() == null ? null : exchange.getRequestURI().getRawQuery();
        validateRawQuery(rawQuery);
        enforceGlobalApiAccess(path, exchange);
        enforceBodySizeHeaders(exchange);
        enforceSecurePullAccess(path, exchange);
        handler.handle(exchange);
        success = true;
      } catch (IllegalArgumentException ex) {
        success = false;
        try {
          String reason = ex.getMessage() == null ? "invalid_request" : ex.getMessage();
          int status = requestValidationStatus(reason);
          logRequestRejection(path, exchange, reason, status, "request_gate");
          writeJson(exchange, status, "{\"error\":\"" + jsonEscape(reason) + "\"}");
        } catch (IOException ignored) {
        }
      } catch (Exception ex) {
        success = false;
        try {
          writeJson(exchange, 500, "{\"error\":\"internal_error\"}");
        } catch (IOException ignored) {
        }
      } finally {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        REQUEST_STATS.computeIfAbsent(path, k -> new TimingStats()).record(durationMs, success);
      }
    });
  }

  private static final class EventInfo {
    private final String rawJson;
    private final String eventType;
    private final String kind;

    private EventInfo(String rawJson, String eventType, String kind) {
      this.rawJson = rawJson;
      this.eventType = eventType;
      this.kind = kind;
    }
  }

  private static final class SnapshotData {
    private final List<EventInfo> events;
    private final Map<String, Integer> eventTypeCounts;
    private final Map<String, Integer> metrics;

    private SnapshotData(List<EventInfo> events, Map<String, Integer> eventTypeCounts, Map<String, Integer> metrics) {
      this.events = events;
      this.eventTypeCounts = eventTypeCounts;
      this.metrics = metrics;
    }
  }

  private static final class WazeRouteData {
    private final String appUrl;
    private final String embedUrl;
    private final String mode;
    private final String start;
    private final String end;
    private final double lat;
    private final double lon;

    private WazeRouteData(String appUrl, String embedUrl, String mode, String start, String end, double lat, double lon) {
      this.appUrl = appUrl;
      this.embedUrl = embedUrl;
      this.mode = mode;
      this.start = start;
      this.end = end;
      this.lat = lat;
      this.lon = lon;
    }
  }
  private static final class AlertClusterItem {
    private final String ts;
    private final String alert;
    private final String transcript;

    private AlertClusterItem(String ts, String alert, String transcript) {
      this.ts = ts;
      this.alert = alert;
      this.transcript = transcript;
    }
  }
  private static final class ShareEtaTokenData {
    private final long expiresAtEpochSeconds;
    private final double originLat;
    private final double originLon;
    private final double destLat;
    private final double destLon;
    private final String destinationLabel;

    private ShareEtaTokenData(
        long expiresAtEpochSeconds,
        double originLat,
        double originLon,
        double destLat,
        double destLon,
        String destinationLabel) {
      this.expiresAtEpochSeconds = expiresAtEpochSeconds;
      this.originLat = originLat;
      this.originLon = originLon;
      this.destLat = destLat;
      this.destLon = destLon;
      this.destinationLabel = destinationLabel;
    }
  }
  private static final class ShareAlertSnippet {
    private final String ts;
    private final String alert;
    private final String transcript;
    private final double lat;
    private final double lon;
    private final double distanceMeters;

    private ShareAlertSnippet(
        String ts,
        String alert,
        String transcript,
        double lat,
        double lon,
        double distanceMeters) {
      this.ts = ts;
      this.alert = alert;
      this.transcript = transcript;
      this.lat = lat;
      this.lon = lon;
      this.distanceMeters = distanceMeters;
    }
  }

  private static int parsePort(String maybePort, int fallback) {
    if (maybePort == null || maybePort.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(maybePort);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static Path repoRoot() {
    String root = System.getenv("SCOUT_REPO_ROOT");
    if (root != null && !root.isBlank()) {
      return Path.of(root);
    }
    return Path.of("/home/gibi/Desktop");
  }

  private static String repoPath(String relPath) {
    return repoRoot().resolve(relPath).toString();
  }

  private static int parseIntOrDefault(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static SnapshotData buildSnapshotData() {
    Deque<EventInfo> events = new ArrayDeque<>(Math.max(RECENT_EVENT_LIMIT, 1));
    Map<String, Integer> eventTypeCounts = new HashMap<>();
    Map<String, Integer> metrics = new HashMap<>();
    metrics.put("captured", 0);
    metrics.put("skipped_silence", 0);
    metrics.put("skipped_clipped", 0);
    metrics.put("llm_alert", 0);
    metrics.put("soft_alert_fallback", 0);
    metrics.put("chunk_total", 0);

    if (Files.exists(LOG_PATH)) {
      try (BufferedReader reader = Files.newBufferedReader(LOG_PATH, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          for (String raw : extractEventPayloads(line)) {
            String eventType = extractStringField(raw, EVENT_TYPE_PATTERN);
            String kind = extractStringField(raw, KIND_PATTERN);
            EventInfo event = new EventInfo(raw, eventType, kind);
            events.addLast(event);
            while (events.size() > RECENT_EVENT_LIMIT) {
              events.removeFirst();
            }
            if (!eventType.isEmpty()) {
              eventTypeCounts.merge(eventType, 1, Integer::sum);
            }
            if (eventType.startsWith("chunk_")) {
              metrics.merge("chunk_total", 1, Integer::sum);
            }
            switch (eventType) {
              case "chunk_captured":
                metrics.merge("captured", 1, Integer::sum);
                break;
              case "chunk_skipped_silence":
                metrics.merge("skipped_silence", 1, Integer::sum);
                break;
              case "chunk_skipped_clipped":
                metrics.merge("skipped_clipped", 1, Integer::sum);
                break;
              case "alert_triggered":
                if ("soft_alert_fallback".equals(kind)) {
                  metrics.merge("soft_alert_fallback", 1, Integer::sum);
                } else {
                  metrics.merge("llm_alert", 1, Integer::sum);
                }
                break;
              case "run_summary":
                applyRunSummaryMetrics(metrics, raw);
                break;
              default:
                break;
            }
          }
        }
      } catch (IOException ignored) {
        // return whatever we accumulated so far
      }
    }

    List<EventInfo> recentEvents = new ArrayList<>(events);
    int fromIndex = Math.max(0, recentEvents.size() - SNAPSHOT_EVENT_RETURN_LIMIT);
    return new SnapshotData(recentEvents.subList(fromIndex, recentEvents.size()), eventTypeCounts, metrics);
  }

  private static void applyRunSummaryMetrics(Map<String, Integer> metrics, String rawJson) {
    metrics.put("captured", extractIntField(rawJson, "captured", metrics.getOrDefault("captured", 0)));
    metrics.put(
        "skipped_silence",
        extractIntField(rawJson, "skipped_silence", metrics.getOrDefault("skipped_silence", 0)));
    metrics.put(
        "skipped_clipped",
        extractIntField(rawJson, "skipped_clipped", metrics.getOrDefault("skipped_clipped", 0)));
    metrics.put("llm_alert", extractIntField(rawJson, "llm_alert", metrics.getOrDefault("llm_alert", 0)));
    metrics.put(
        "soft_alert_fallback",
        extractIntField(rawJson, "soft_alert_fallback", metrics.getOrDefault("soft_alert_fallback", 0)));
    int chunkTotal = extractIntField(rawJson, "chunk_total", -1);
    if (chunkTotal >= 0) {
      metrics.put("chunk_total", chunkTotal);
    } else {
      int fallbackChunkTotal =
          metrics.getOrDefault("captured", 0)
              + metrics.getOrDefault("skipped_silence", 0)
              + metrics.getOrDefault("skipped_clipped", 0);
      metrics.put("chunk_total", Math.max(metrics.getOrDefault("chunk_total", 0), fallbackChunkTotal));
    }
  }

  private static List<EventInfo> readEventLines(int maxEvents) {
    Deque<EventInfo> out = new ArrayDeque<>(Math.max(maxEvents, 1));
    if (!Files.exists(LOG_PATH)) {
      return List.of();
    }
    try (BufferedReader reader = Files.newBufferedReader(LOG_PATH, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        for (String raw : extractEventPayloads(line)) {
          String eventType = extractStringField(raw, EVENT_TYPE_PATTERN);
          String kind = extractStringField(raw, KIND_PATTERN);
          out.addLast(new EventInfo(raw, eventType, kind));
          while (out.size() > maxEvents) {
            out.removeFirst();
          }
        }
      }
    } catch (IOException ignored) {
      return List.of();
    }
    return new ArrayList<>(out);
  }

  private static List<String> extractEventPayloads(String line) {
    List<String> payloads = new ArrayList<>();
    if (line == null || line.isEmpty()) {
      return payloads;
    }
    int searchFrom = 0;
    while (true) {
      int marker = line.indexOf(EVENT_PREFIX, searchFrom);
      if (marker < 0) {
        break;
      }
      int payloadStart = marker + EVENT_PREFIX.length();
      int nextMarker = line.indexOf(EVENT_PREFIX, payloadStart);
      String payload = (nextMarker >= 0 ? line.substring(payloadStart, nextMarker) : line.substring(payloadStart)).trim();
      if (!payload.isEmpty()) {
        payloads.add(payload);
      }
      if (nextMarker < 0) {
        break;
      }
      searchFrom = nextMarker;
    }
    return payloads;
  }

  private static String extractStringField(String json, Pattern pattern) {
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return "";
  }

  private static int extractIntField(String json, String fieldName, int fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      try {
        return Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\"':
          escaped.append("\\\"");
          break;
        case '\\':
          escaped.append("\\\\");
          break;
        case '\n':
          escaped.append("\\n");
          break;
        case '\r':
          escaped.append("\\r");
          break;
        case '\t':
          escaped.append("\\t");
          break;
        default:
          if (c < 0x20) {
            escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
      }
    }
    return escaped.toString();
  }

  private static String metricMapToJson(Map<String, Integer> map) {
    return "{"
        + "\"captured\":" + map.getOrDefault("captured", 0) + ","
        + "\"skipped_silence\":" + map.getOrDefault("skipped_silence", 0) + ","
        + "\"skipped_clipped\":" + map.getOrDefault("skipped_clipped", 0) + ","
        + "\"chunk_total\":" + map.getOrDefault("chunk_total", 0) + ","
        + "\"llm_alert\":" + map.getOrDefault("llm_alert", 0) + ","
        + "\"soft_alert_fallback\":" + map.getOrDefault("soft_alert_fallback", 0)
        + "}";
  }

  private static String eventTypeCountsToJson(Map<String, Integer> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Integer> entry : map.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(jsonEscape(entry.getKey())).append("\":").append(entry.getValue());
    }
    sb.append("}");
    return sb.toString();
  }

  private static String snapshotToJson(SnapshotData data) {
    StringBuilder eventsJson = new StringBuilder("[");
    for (int i = 0; i < data.events.size(); i++) {
      if (i > 0) {
        eventsJson.append(",");
      }
      eventsJson.append(data.events.get(i).rawJson);
    }
    eventsJson.append("]");

    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\","
        + "\"event_type_counts\":" + eventTypeCountsToJson(data.eventTypeCounts) + ","
        + "\"metrics\":" + metricMapToJson(data.metrics) + ","
        + "\"recentEvents\":" + eventsJson
        + "}";
  }

  private static String mobileBootstrapJson() {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"version\":\"v1\","
        + "\"endpoints\":{"
        + "\"snapshot\":\"/api/mobile/snapshot\","
        + "\"stream\":\"/api/mobile/stream\","
        + "\"weather\":\"/api/platform/weather/forecast\","
        + "\"waze\":\"/api/platform/waze/route\","
        + "\"geocode\":\"/api/platform/geocode\","
        + "\"address_catalog_resolve\":\"/api/platform/address-catalog/resolve\","
        + "\"address_catalog_suggest\":\"/api/platform/address-catalog/suggest\","
        + "\"address_catalog_upsert\":\"/api/platform/address-catalog/upsert\","
        + "\"error_reports_submit\":\"/api/platform/error-reports/submit\","
        + "\"error_reports_recent\":\"/api/platform/error-reports/recent\","
        + "\"local_route\":\"/api/platform/route/local\","
        + "\"route_options\":\"/api/platform/route/options\","
        + "\"alert_clusters\":\"/api/platform/alerts/clusters\","
        + "\"client_register\":\"/api/mobile/client/register\","
        + "\"client_send\":\"/api/mobile/client/send\","
        + "\"client_pull\":\"/api/mobile/client/pull\","
        + "\"clients\":\"/api/mobile/clients\","
        + "\"assistant_chat\":\"/api/platform/assistant/chat\","
        + "\"stack_manage\":\"/api/platform/dev/stack/manage\","
        + "\"map_scene\":\"/api/map/scene\","
        + "\"map_render\":\"/api/map/render\","
        + "\"map_status\":\"/api/map/status\","
        + "\"map_shard\":\"/api/map/shard\""
        + "},"
        + "\"network\":" + ProprietaryMapEngine.networkAdvertiseJsonPublic() + ","
        + "\"notes\":\"Compact endpoints are intended for low-bandwidth mobile companion clients. Map/shard URLs are also advertised under network.urls.\""
        + "}";
  }

  private static String buildAssistantRouteContext(
      String userQuery, String transcript, Map<String, String> query, String body) {
    boolean highLoad = registerAssistantQueryAndDetectHighLoad();
    Map<String, String> toolQuery = buildAssistantToolQuery(query, body);
    int maxContextChars =
        highLoad ? ASSISTANT_HIGH_LOAD_CONTEXT_MAX_CHARS : ASSISTANT_CONTEXT_MAX_CHARS;
    StringBuilder context = new StringBuilder();
    context.append("user_query: ").append(userQuery);
    if (!transcript.isBlank()) {
      context.append("\ntranscript: ").append(transcript);
    }
    String routeSummary = extractStringFieldByName(body, "route_summary", query.getOrDefault("route_summary", ""));
    if (!routeSummary.isBlank()) {
      context.append("\nroute_summary: ").append(routeSummary);
    }
    String destinationLabel =
        extractStringFieldByName(body, "destination_label", query.getOrDefault("destination_label", ""));
    if (!destinationLabel.isBlank()) {
      context.append("\ndestination_label: ").append(destinationLabel);
    }
    String[] scalarKeys = new String[] {"origin_lat", "origin_lon", "dest_lat", "dest_lon", "eta_minutes"};
    for (String key : scalarKeys) {
      String value = extractStringFieldByName(body, key, query.getOrDefault(key, "")).trim();
      if (!value.isBlank()) {
        context.append("\n").append(key).append(": ").append(value);
      }
    }
    appendContextSection(context, "tool_definitions", buildScoutToolDefinitionsText(), ASSISTANT_TOOL_SECTION_MAX_CHARS);
    appendContextSection(
        context,
        "tool_observations",
        buildScoutToolObservations(toolQuery, body),
        ASSISTANT_TOOL_SECTION_MAX_CHARS);
    appendContextSection(
        context,
        "broadcastify_stream_context",
        extractStringFieldByName(body, "broadcastify_stream_context", query.getOrDefault("broadcastify_stream_context", "")),
        ASSISTANT_TOOL_SECTION_MAX_CHARS);
    appendContextSection(
        context,
        "hazard_api_stream_context",
        extractStringFieldByName(body, "hazard_api_stream_context", query.getOrDefault("hazard_api_stream_context", "")),
        ASSISTANT_TOOL_SECTION_MAX_CHARS);
    context.append(
        "\nresponse_style_rule: for alert location language, do not use generic 'nearby' by itself; prefer approximate distance plus relative location when supported by provided coordinates/route context.");
    context.append(
        "\nresponse_style_fallback: if spatial precision is missing, explicitly state approximate location unavailable and include missing_data entries instead of guessing.");
    if (highLoad) {
      context.append("\nguardrail_mode: strict_high_query_load");
      context.append(
          "\nguardrail_directive: use only explicit facts from tool_observations and provided context; if uncertain respond with low confidence and list missing_data.");
    }
    if (context.length() > maxContextChars) {
      return context.substring(0, maxContextChars);
    }
    return context.toString();
  }

  private static String runScoutAssistantChat(String routeContext) {
    List<String> cmd = new ArrayList<>();
    cmd.add(SELECTOR_PYTHON_BIN);
    cmd.add(LLM_SET_CLIENT_SCRIPT_PATH);
    cmd.add("chat");
    cmd.add(routeContext);
    cmd.add("--timeout");
    cmd.add(String.valueOf(LLM_CHAT_HELPER_TIMEOUT_SECONDS));
    return runHelperCommand(cmd, "llm_chat", LLM_CHAT_HELPER_TIMEOUT_SECONDS);
  }

  private static String compactMobileEventJson(EventInfo event) {
    String ts = extractStringField(event.rawJson, TS_PATTERN);
    String transcript = extractStringField(event.rawJson, TRANSCRIPT_PATTERN);
    String alert = extractStringField(event.rawJson, ALERT_PATTERN);
    return "{"
        + "\"ts\":\"" + jsonEscape(ts) + "\","
        + "\"event_type\":\"" + jsonEscape(event.eventType) + "\","
        + "\"kind\":\"" + jsonEscape(event.kind) + "\","
        + "\"transcript\":\"" + jsonEscape(transcript) + "\","
        + "\"alert\":\"" + jsonEscape(alert) + "\""
        + "}";
  }

  private static String mobileSnapshotJson() {
    SnapshotData snapshot = buildSnapshotData();
    List<EventInfo> events = snapshot.events;
    int fromIndex = Math.max(0, events.size() - MOBILE_EVENT_RETURN_LIMIT);
    StringBuilder compactEvents = new StringBuilder("[");
    for (int i = fromIndex; i < events.size(); i++) {
      if (i > fromIndex) {
        compactEvents.append(",");
      }
      compactEvents.append(compactMobileEventJson(events.get(i)));
    }
    compactEvents.append("]");
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"metrics\":" + metricMapToJson(snapshot.metrics) + ","
        + "\"events\":" + compactEvents + ","
        + "\"log_exists\":" + Files.exists(LOG_PATH)
        + "}";
  }

  private static WazeRouteData buildWazeRoute(Map<String, String> query) {
    String start = query.getOrDefault("start", "");
    String end = query.getOrDefault("end", "");
    String latRaw = query.getOrDefault("lat", "");
    String lonRaw = query.getOrDefault("lon", "");
    double lat = parseDouble(latRaw, 34.0522);
    double lon = parseDouble(lonRaw, -118.2437);
    boolean hasCoords = !latRaw.isBlank() && !lonRaw.isBlank();
    String appUrl;
    if (hasCoords) {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?ll=" + urlEncode(latRaw) + "," + urlEncode(lonRaw) + "&navigate=yes";
    } else if (!end.isBlank()) {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?q=" + urlEncode(end) + "&navigate=yes";
    } else {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?ll=" + lat + "," + lon + "&navigate=yes";
    }
    String embedUrl = WAZE_EMBED_BASE_URL + "?zoom=11&lat=" + lat + "&lon=" + lon;
    String mode = hasCoords ? "coords" : (!end.isBlank() ? "destination_query" : "default");
    return new WazeRouteData(appUrl, embedUrl, mode, start, end, lat, lon);
  }
  private static String buildStandaloneLocalRouteJson(Map<String, String> query) {
    GpsPoint latest = latestGpsPoint;
    double originLat = parseDouble(query.getOrDefault("origin_lat", ""), Double.NaN);
    double originLon = parseDouble(query.getOrDefault("origin_lon", ""), Double.NaN);
    double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
    double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);

    if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
      if (latest != null) {
        originLat = latest.lat;
        originLon = latest.lon;
      }
    }
    if (!Double.isFinite(destLat) || !Double.isFinite(destLon)) {
      if (latest != null) {
        destLat = latest.lat;
        destLon = latest.lon;
      }
    }
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)) {
      return "{\"error\":\"invalid_route_coordinates\"}";
    }
    if (originLat < -90
        || originLat > 90
        || destLat < -90
        || destLat > 90
        || originLon < -180
        || originLon > 180
        || destLon < -180
        || destLon > 180) {
      return "{\"error\":\"out_of_range_route_coordinates\"}";
    }

    String condition = query.getOrDefault("condition", "idle");
    String engine = "osrm_openstreetmap";
    List<RouteNode> routeNodes = null;
    Double externalMeters = null;
    String osrmBody = fetchOsrmRouteBody(originLat, originLon, destLat, destLon);
    if (osrmBody != null) {
      List<RouteNode> osrmNodes = parsePrimaryRouteGeometryCoordinates(osrmBody);
      if (osrmNodes != null) {
        routeNodes = osrmNodes;
        externalMeters = parseOsrmDistanceMeters(osrmBody);
        engine = "osrm_openstreetmap";
      }
    }
    if (routeNodes == null) {
      return "{\"error\":\"route_unavailable_no_roadway\"}";
    }
    double meters = externalMeters != null ? externalMeters : approximateRouteMeters(routeNodes);
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"engine\":\"" + jsonEscape(engine) + "\","
        + "\"condition\":\"" + jsonEscape(condition) + "\","
        + "\"origin\":{\"lat\":" + trimDouble(originLat) + ",\"lon\":" + trimDouble(originLon) + "},"
        + "\"destination\":{\"lat\":" + trimDouble(destLat) + ",\"lon\":" + trimDouble(destLon) + "},"
        + "\"distance_m\":" + trimDouble(meters) + ","
        + "\"route_points\":" + routeNodesToJson(routeNodes)
        + "}";
  }

  private static double approximateRouteMeters(List<RouteNode> nodes) {
    if (nodes.size() < 2) {
      return 0.0;
    }
    double total = 0.0;
    for (int i = 1; i < nodes.size(); i++) {
      RouteNode a = nodes.get(i - 1);
      RouteNode b = nodes.get(i);
      total += haversineMeters(a.lat, a.lon, b.lat, b.lon);
    }
    return total;
  }

  private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6371000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return r * c;
  }

  private static String routeNodesToJson(List<RouteNode> nodes) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < nodes.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      RouteNode node = nodes.get(i);
      ZhsCellData zhs = buildZhsCellData(node.lat, node.lon);
      sb.append("{\"lat\":")
          .append(trimDouble(node.lat))
          .append(",\"lon\":")
          .append(trimDouble(node.lon))
          .append(",\"zhs\":")
          .append(zhsCellToJson(zhs))
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String zhsCellToJson(ZhsCellData cell) {
    return "{"
        + "\"key\":\"" + jsonEscape(cell.unifiedKey) + "\","
        + "\"z\":{"
          + "\"key\":\"" + jsonEscape(cell.zKey) + "\","
          + "\"zoom\":" + cell.zZoom + ","
          + "\"x\":" + cell.zX + ","
          + "\"y\":" + cell.zY
        + "},"
        + "\"h\":{"
          + "\"key\":\"" + jsonEscape(cell.hKey) + "\","
          + "\"resolution\":" + cell.hResolution + ","
          + "\"q\":" + cell.hQ + ","
          + "\"r\":" + cell.hR
        + "},"
        + "\"s\":{"
          + "\"key\":\"" + jsonEscape(cell.sKey) + "\","
          + "\"level\":" + cell.sLevel + ","
          + "\"face\":" + cell.sFace + ","
          + "\"i\":" + cell.sI + ","
          + "\"j\":" + cell.sJ
        + "}"
        + "}";
  }

  private static ZhsCellData buildZhsCellData(double lat, double lon) {
    int zZoom = clampIndex(ROUTE_ZHS_TILE_ZOOM, 0, 20);
    int zX = MapModel.lonToTileX(lon, zZoom);
    int zY = MapModel.latToTileY(lat, zZoom);
    String zKey = "z/" + zZoom + "/" + zX + "/" + zY;

    int hResolution = clampIndex(ROUTE_ZHS_H3_RESOLUTION, 0, 15);
    long[] axial = h3LikeAxial(lat, lon, hResolution);
    long hQ = axial[0];
    long hR = axial[1];
    String hKey = "h3/" + hResolution + "/" + encodeSignedBase36(hQ) + "/" + encodeSignedBase36(hR);

    int sLevel = clampIndex(ROUTE_ZHS_S2_LEVEL, 0, 20);
    long[] faceIj = s2LikeFaceIj(lat, lon, sLevel);
    int sFace = (int) faceIj[0];
    long sI = faceIj[1];
    long sJ = faceIj[2];
    String sKey =
        "s2/"
            + sLevel
            + "/f"
            + sFace
            + "/"
            + Long.toString(sI, 36)
            + "/"
            + Long.toString(sJ, 36);

    return new ZhsCellData(
        zKey,
        zZoom,
        zX,
        zY,
        hKey,
        hResolution,
        hQ,
        hR,
        sKey,
        sLevel,
        sFace,
        sI,
        sJ);
  }

  private static int clampIndex(int value, int min, int max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
  }

  private static long[] h3LikeAxial(double lat, double lon, int resolution) {
    double clampedLat = Math.max(-85.0, Math.min(85.0, lat));
    double latRad = Math.toRadians(clampedLat);
    double lonRad = Math.toRadians(lon);
    double metersY = 6371000.0 * latRad;
    double metersX = 6371000.0 * lonRad * Math.cos(latRad);
    double edgeMeters = 1100000.0 / Math.pow(Math.sqrt(7.0), Math.max(0, resolution));
    edgeMeters = Math.max(1.0, edgeMeters);
    double q = (Math.sqrt(3.0) / 3.0 * metersX - 1.0 / 3.0 * metersY) / edgeMeters;
    double r = (2.0 / 3.0 * metersY) / edgeMeters;
    long[] rounded = axialRound(q, r);
    return new long[] {rounded[0], rounded[1]};
  }

  private static long[] axialRound(double q, double r) {
    double x = q;
    double z = r;
    double y = -x - z;
    long rx = Math.round(x);
    long ry = Math.round(y);
    long rz = Math.round(z);
    double dx = Math.abs(rx - x);
    double dy = Math.abs(ry - y);
    double dz = Math.abs(rz - z);
    if (dx > dy && dx > dz) {
      rx = -ry - rz;
    } else if (dy > dz) {
      ry = -rx - rz;
    } else {
      rz = -rx - ry;
    }
    return new long[] {rx, rz};
  }

  private static long[] s2LikeFaceIj(double lat, double lon, int level) {
    double latRad = Math.toRadians(Math.max(-89.999999, Math.min(89.999999, lat)));
    double lonRad = Math.toRadians(lon);
    double x = Math.cos(latRad) * Math.cos(lonRad);
    double y = Math.cos(latRad) * Math.sin(lonRad);
    double z = Math.sin(latRad);
    double ax = Math.abs(x);
    double ay = Math.abs(y);
    double az = Math.abs(z);
    int face;
    double u;
    double v;
    if (ax >= ay && ax >= az) {
      if (x >= 0) {
        face = 0;
        u = y / x;
        v = z / x;
      } else {
        face = 3;
        u = z / x;
        v = y / x;
      }
    } else if (ay >= az) {
      if (y >= 0) {
        face = 1;
        u = -x / y;
        v = z / y;
      } else {
        face = 4;
        u = z / y;
        v = -x / y;
      }
    } else {
      if (z >= 0) {
        face = 2;
        u = -x / z;
        v = -y / z;
      } else {
        face = 5;
        u = -y / z;
        v = -x / z;
      }
    }
    double s = 0.5 + (Math.atan(u) / Math.PI);
    double t = 0.5 + (Math.atan(v) / Math.PI);
    int n = 1 << Math.min(30, Math.max(0, level));
    long i = Math.max(0L, Math.min((long) n - 1L, (long) Math.floor(s * n)));
    long j = Math.max(0L, Math.min((long) n - 1L, (long) Math.floor(t * n)));
    return new long[] {face, i, j};
  }

  private static String encodeSignedBase36(long value) {
    if (value < 0) {
      return "n" + Long.toString(-value, 36);
    }
    return "p" + Long.toString(value, 36);
  }

  private static String buildZhsSceneJson(double lat, double lon, double radiusM, int zoomOverride) {
    double radius = Math.max(80.0, Math.min(8_000_000.0, radiusM));
    int zoom = zoomOverride > 0 ? snapToMapLadder(zoomOverride) : mapZoomForRadius(lat, radius);
    double tileSpanM = tileSpanMetersAt(lat, zoom);
    int ring = Math.max(0, Math.min(MAP_CELL_RING_LIMIT, (int) Math.ceil(radius / Math.max(1.0, tileSpanM))));
    int centerX = MapModel.lonToTileX(lon, zoom);
    int centerY = MapModel.latToTileY(lat, zoom);
    int maxTile = (1 << zoom) - 1;

    StringBuilder cellSb = new StringBuilder("[");
    StringBuilder areaSb = new StringBuilder("[");
    boolean firstCell = true;
    boolean firstArea = true;
    int cells = 0;

    for (int dx = -ring; dx <= ring; dx++) {
      for (int dy = -ring; dy <= ring; dy++) {
        int tx = centerX + dx;
        int ty = centerY + dy;
        if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) {
          continue;
        }
        ZhsCellData cell = buildZhsCellDataForTile(zoom, tx, ty);
        if (!firstCell) {
          cellSb.append(",");
        }
        firstCell = false;
        cellSb.append("{")
            .append("\"z\":").append(zoom).append(",")
            .append("\"x\":").append(tx).append(",")
            .append("\"y\":").append(ty).append(",")
            .append("\"zhs\":").append(zhsCellToJson(cell))
            .append("}");
        double north = MapModel.tileToLat(ty, zoom);
        double south = MapModel.tileToLat(ty + 1, zoom);
        double west = MapModel.tileToLon(tx, zoom);
        double east = MapModel.tileToLon(tx + 1, zoom);
        if (!firstArea) {
          areaSb.append(",");
        }
        firstArea = false;
        areaSb.append("{\"k\":\"civic\",\"p\":[")
            .append(trimDouble(north)).append(",").append(trimDouble(west)).append(",")
            .append(trimDouble(north)).append(",").append(trimDouble(east)).append(",")
            .append(trimDouble(south)).append(",").append(trimDouble(east)).append(",")
            .append(trimDouble(south)).append(",").append(trimDouble(west))
            .append("]}");
        cells++;
      }
    }
    cellSb.append("]");
    areaSb.append("]");

    return "{"
        + "\"status\":\"ok\","
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"mode\":\"" + MAP_MODE + "\","
        + "\"center\":{\"lat\":" + trimDouble(lat) + ",\"lon\":" + trimDouble(lon) + "},"
        + "\"radius_m\":" + Math.round(radius) + ","
        + "\"zoom\":" + zoom + ","
        + "\"attribution\":\"" + jsonEscape("© OpenStreetMap contributors") + "\","
        + "\"cells\":" + cellSb + ","
        + "\"areas\":" + areaSb + ","
        + "\"roads\":[],"
        + "\"buildings\":[],"
        + "\"pois\":[],"
        + "\"counts\":{\"cells\":" + cells + ",\"areas\":" + cells + ",\"roads\":0,\"buildings\":0,\"pois\":0}"
        + "}";
  }

  private static int snapToMapLadder(int requested) {
    int best = MAP_ZOOM_LADDER[0];
    int bestDiff = Integer.MAX_VALUE;
    for (int z : MAP_ZOOM_LADDER) {
      int diff = Math.abs(z - requested);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = z;
      }
    }
    return best;
  }

  private static int mapZoomForRadius(double lat, double radiusM) {
    for (int z : MAP_ZOOM_LADDER) {
      double span = tileSpanMetersAt(lat, z);
      double coverage = span * (1 + (MAP_CELL_RING_LIMIT * 2.0));
      if (coverage >= radiusM * 2.0) {
        return z;
      }
    }
    return MAP_ZOOM_LADDER[MAP_ZOOM_LADDER.length - 1];
  }

  private static double tileSpanMetersAt(double lat, int zoom) {
    double cosLat = Math.max(0.08, Math.cos(Math.toRadians(lat)));
    return 40075016.686 * cosLat / (1L << zoom);
  }

  private static ZhsCellData buildZhsCellDataForTile(int zoom, int x, int y) {
    double lat = MapModel.tileToLat(y + 0.5, zoom);
    double lon = MapModel.tileToLon(x + 0.5, zoom);
    String zKey = "z/" + zoom + "/" + x + "/" + y;
    long[] axial = h3LikeAxial(lat, lon, MAP_ZHS_H_RESOLUTION);
    String hKey =
        "h3/"
            + MAP_ZHS_H_RESOLUTION
            + "/"
            + encodeSignedBase36(axial[0])
            + "/"
            + encodeSignedBase36(axial[1]);
    long[] faceIj = s2LikeFaceIj(lat, lon, MAP_ZHS_S2_LEVEL);
    int sFace = (int) faceIj[0];
    long sI = faceIj[1];
    long sJ = faceIj[2];
    String sKey =
        "s2/"
            + MAP_ZHS_S2_LEVEL
            + "/f"
            + sFace
            + "/"
            + Long.toString(sI, 36)
            + "/"
            + Long.toString(sJ, 36);
    return new ZhsCellData(
        zKey,
        zoom,
        x,
        y,
        hKey,
        MAP_ZHS_H_RESOLUTION,
        axial[0],
        axial[1],
        sKey,
        MAP_ZHS_S2_LEVEL,
        sFace,
        sI,
        sJ);
  }

  private static byte[] buildZhsRenderPng(
      double lat,
      double lon,
      double metersPerPixel,
      double headingDeg,
      double tiltDeg,
      int w,
      int h,
      double[] routePts,
      Double destLat,
      Double destLon)
      throws IOException {
    int width = Math.max(64, Math.min(w, MAP_RENDER_MAX_DIM_PX));
    int height = Math.max(64, Math.min(h, MAP_RENDER_MAX_DIM_PX));
    double mpp = Math.max(MAP_RENDER_MIN_MPP, Math.min(MAP_RENDER_MAX_MPP, metersPerPixel));
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setColor(new Color(0x17, 0x1A, 0x21));
      g.fillRect(0, 0, width, height);
      g.setColor(new Color(0x2B, 0x31, 0x3B));
      g.setStroke(new BasicStroke(1f));
      int grid = 96;
      for (int x = 0; x < width; x += grid) {
        g.drawLine(x, 0, x, height);
      }
      for (int y = 0; y < height; y += grid) {
        g.drawLine(0, y, width, y);
      }
      if (routePts != null && routePts.length >= 4) {
        drawRoutePolyline(g, lat, lon, mpp, width, height, routePts, headingDeg);
      }
      if (destLat != null && destLon != null) {
        int[] p = projectPoint(lat, lon, mpp, width, height, destLat, destLon, headingDeg);
        g.setColor(new Color(0xD8, 0x40, 0x40));
        g.fillOval(p[0] - 6, p[1] - 6, 12, 12);
      }
      g.setColor(new Color(0x2B, 0x6B, 0xE6));
      g.fillOval(width / 2 - 6, height / 2 - 6, 12, 12);
      g.setColor(new Color(0xC7, 0xCE, 0xDB));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      g.drawString("Map mode: " + MAP_MODE, 12, 18);
      g.drawString("z/h/s grid overlay only", 12, 34);
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  private static void drawRoutePolyline(
      Graphics2D g,
      double originLat,
      double originLon,
      double mpp,
      int width,
      int height,
      double[] routePts,
      double headingDeg) {
    int[] first = projectPoint(originLat, originLon, mpp, width, height, routePts[0], routePts[1], headingDeg);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    int px = first[0];
    int py = first[1];
    for (int i = 2; i + 1 < routePts.length; i += 2) {
      int[] p = projectPoint(originLat, originLon, mpp, width, height, routePts[i], routePts[i + 1], headingDeg);
      g.drawLine(px, py, p[0], p[1]);
      px = p[0];
      py = p[1];
    }
    g.setColor(new Color(0x2B, 0x6B, 0xE6));
    g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    px = first[0];
    py = first[1];
    for (int i = 2; i + 1 < routePts.length; i += 2) {
      int[] p = projectPoint(originLat, originLon, mpp, width, height, routePts[i], routePts[i + 1], headingDeg);
      g.drawLine(px, py, p[0], p[1]);
      px = p[0];
      py = p[1];
    }
  }

  private static int[] projectPoint(
      double originLat,
      double originLon,
      double mpp,
      int width,
      int height,
      double lat,
      double lon,
      double headingDeg) {
    double mPerDegLat = 110540.0;
    double mPerDegLon = 111320.0 * Math.max(0.2, Math.cos(Math.toRadians(originLat)));
    double dx = (lon - originLon) * mPerDegLon;
    double dy = (lat - originLat) * mPerDegLat;
    double r = Math.toRadians(headingDeg);
    double xr = dx * Math.cos(r) - dy * Math.sin(r);
    double yr = dx * Math.sin(r) + dy * Math.cos(r);
    int sx = (int) Math.round(width / 2.0 + xr / mpp);
    int sy = (int) Math.round(height / 2.0 - yr / mpp);
    return new int[] {sx, sy};
  }

  private static String zhsMapStatusJson() {
    return "{"
        + "\"status\":\"ok\","
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"mode\":\"" + MAP_MODE + "\","
        + "\"zoom_ladder\":[" + mapZoomLadderJson() + "],"
        + "\"cell_ring_limit\":" + MAP_CELL_RING_LIMIT + ","
        + "\"h_resolution\":" + MAP_ZHS_H_RESOLUTION + ","
        + "\"s2_level\":" + MAP_ZHS_S2_LEVEL + ","
        + "\"upstream_fetches_disabled\":true"
        + "}";
  }

  private static String mapZoomLadderJson() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < MAP_ZOOM_LADDER.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(MAP_ZOOM_LADDER[i]);
    }
    return sb.toString();
  }

  private static String normalizeCatalogQuery(String raw) {
    if (raw == null) {
      return "";
    }
    String lower = raw.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(lower.length());
    boolean previousWhitespace = false;
    for (int i = 0; i < lower.length(); i++) {
      char ch = lower.charAt(i);
      if (!(Character.isLetterOrDigit(ch) || Character.isWhitespace(ch))) {
        ch = ' ';
      }
      if (Character.isWhitespace(ch)) {
        if (!previousWhitespace) {
          sb.append(' ');
          previousWhitespace = true;
        }
      } else {
        sb.append(ch);
        previousWhitespace = false;
      }
    }
    int start = 0;
    int end = sb.length();
    while (start < end && sb.charAt(start) == ' ') {
      start++;
    }
    while (end > start && sb.charAt(end - 1) == ' ') {
      end--;
    }
    return start == 0 && end == sb.length() ? sb.toString() : sb.substring(start, end);
  }

  private static String addressCatalogEntryToJson(AddressCatalogEntry entry) {
    return "{"
        + "\"query\":\"" + jsonEscape(entry.queryKey) + "\","
        + "\"display_name\":\"" + jsonEscape(entry.displayName) + "\","
        + "\"source\":\"" + jsonEscape(entry.source) + "\","
        + "\"lat\":" + trimDouble(entry.lat) + ","
        + "\"lon\":" + trimDouble(entry.lon) + ","
        + "\"updated_at_ms\":" + entry.updatedAtMs
        + "}";
  }
  private static String addressCatalogEntriesToJson(List<AddressCatalogEntry> entries) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(addressCatalogEntryToJson(entries.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static int catalogTokenOverlapScore(String queryKey, String entryKey) {
    if (queryKey.isEmpty() || entryKey.isEmpty()) {
      return 0;
    }
    Set<String> queryTokens = new HashSet<>(Arrays.asList(queryKey.split(" ")));
    Set<String> entryTokens = new HashSet<>(Arrays.asList(entryKey.split(" ")));
    queryTokens.remove("");
    entryTokens.remove("");
    if (queryTokens.isEmpty() || entryTokens.isEmpty()) {
      return 0;
    }
    int overlap = 0;
    for (String token : queryTokens) {
      if (entryTokens.contains(token)) {
        overlap++;
      }
    }
    if (overlap == 0) {
      return 0;
    }
    return overlap * 40;
  }

  private static double catalogBiasScore(
      AddressCatalogEntry entry, double biasLat, double biasLon, boolean hasBias) {
    if (!hasBias) {
      return 0.0;
    }
    double meters = haversineMeters(entry.lat, entry.lon, biasLat, biasLon);
    // Nearby results get a meaningful boost; distant ones decay smoothly.
    return Math.max(0.0, 1200.0 / (1.0 + (meters / 1000.0)));
  }

  private static double catalogMatchScore(
      String queryKey, AddressCatalogEntry entry, double biasLat, double biasLon, boolean hasBias) {
    if (entry == null) {
      return 0.0;
    }
    String houseNumber = extractLeadingHouseNumber(queryKey);
    String queryWithoutNumber = stripLeadingHouseNumber(queryKey);
    String entryDisplayKey = normalizeCatalogQuery(entry.displayName);
    if (!houseNumber.isBlank() && !addressHasHouseNumberPrefix(entryDisplayKey, houseNumber)) {
      return 0.0;
    }
    String entryKey = entry.queryKey;
    if (queryKey.equals(entryKey)) {
      return 2000.0 + catalogBiasScore(entry, biasLat, biasLon, hasBias);
    }
    String compareQuery = queryWithoutNumber.isBlank() ? queryKey : queryWithoutNumber;
    double score = 0.0;
    if (entryKey.startsWith(compareQuery) || compareQuery.startsWith(entryKey)) {
      score += 240.0;
    }
    if (entryKey.contains(compareQuery) || compareQuery.contains(entryKey)) {
      score += 140.0;
    }
    score += catalogTokenOverlapScore(compareQuery, entryKey);
    score += catalogBiasScore(entry, biasLat, biasLon, hasBias);
    if (!houseNumber.isBlank()) {
      score += 600.0;
    }
    return score;
  }

  private static List<AddressCatalogEntry> buildCityCatalogAliases() {
    List<CityCatalogSeed> seeds =
        List.of(
            new CityCatalogSeed("San Francisco", "CA", "California", 37.7749, -122.4194),
            new CityCatalogSeed("Los Angeles", "CA", "California", 34.0522, -118.2437),
            new CityCatalogSeed("San Diego", "CA", "California", 32.7157, -117.1611),
            new CityCatalogSeed("Sacramento", "CA", "California", 38.5816, -121.4944),
            new CityCatalogSeed("Seattle", "WA", "Washington", 47.6062, -122.3321),
            new CityCatalogSeed("Portland", "OR", "Oregon", 45.5152, -122.6784),
            new CityCatalogSeed("Las Vegas", "NV", "Nevada", 36.1699, -115.1398),
            new CityCatalogSeed("Phoenix", "AZ", "Arizona", 33.4484, -112.0740),
            new CityCatalogSeed("Denver", "CO", "Colorado", 39.7392, -104.9903),
            new CityCatalogSeed("Dallas", "TX", "Texas", 32.7767, -96.7970),
            new CityCatalogSeed("Houston", "TX", "Texas", 29.7604, -95.3698),
            new CityCatalogSeed("Austin", "TX", "Texas", 30.2672, -97.7431),
            new CityCatalogSeed("Chicago", "IL", "Illinois", 41.8781, -87.6298),
            new CityCatalogSeed("New York", "NY", "New York", 40.7128, -74.0060),
            new CityCatalogSeed("Boston", "MA", "Massachusetts", 42.3601, -71.0589),
            new CityCatalogSeed("Miami", "FL", "Florida", 25.7617, -80.1918),
            new CityCatalogSeed("Orlando", "FL", "Florida", 28.5383, -81.3792),
            new CityCatalogSeed("Atlanta", "GA", "Georgia", 33.7490, -84.3880),
            new CityCatalogSeed("Washington", "DC", "District of Columbia", 38.9072, -77.0369));
    Map<String, AddressCatalogEntry> aliases = new LinkedHashMap<>();
    for (CityCatalogSeed seed : seeds) {
      String city = seed.cityName.trim();
      String abbrev = seed.stateAbbrev.trim().toUpperCase(Locale.ROOT);
      String display = city + ", " + abbrev;
      List<String> variants = List.of(city + " " + abbrev, city + ", " + abbrev);
      for (String variant : variants) {
        String key = normalizeCatalogQuery(variant);
        if (key.isEmpty()) {
          continue;
        }
        aliases.putIfAbsent(
            key,
            new AddressCatalogEntry(key, display, "city_catalog", seed.lat, seed.lon, 0L));
      }
    }
    return new ArrayList<>(aliases.values());
  }

  private static String suggestionEntryToJson(SuggestionEntry entry, double score, String shardTier) {
    return "{"
        + "\"display_name\":\"" + jsonEscape(entry.displayName) + "\","
        + "\"source\":\"" + jsonEscape(entry.source) + "\","
        + "\"lat\":" + trimDouble(entry.lat) + ","
        + "\"lon\":" + trimDouble(entry.lon) + ","
        + "\"updated_at_ms\":" + entry.updatedAtMs + ","
        + "\"score\":" + trimDouble(score) + ","
        + "\"shard_tier\":\"" + jsonEscape(shardTier) + "\""
        + "}";
  }

  private static String normalizeSuggestToken(String value) {
    return normalizeCatalogQuery(value == null ? "" : value);
  }

  private static List<String> splitWords(String value) {
    List<String> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split(" ")) {
      String token = part == null ? "" : part.trim();
      if (!token.isEmpty()) {
        out.add(token);
      }
    }
    return out;
  }

  private static String extractLeadingHouseNumber(String normalizedQuery) {
    if (normalizedQuery == null || normalizedQuery.isBlank()) {
      return "";
    }
    Matcher matcher = Pattern.compile("^\\s*(\\d{1,8})\\b").matcher(normalizedQuery);
    if (!matcher.find()) {
      return "";
    }
    return matcher.group(1);
  }

  private static String stripLeadingHouseNumber(String normalizedQuery) {
    if (normalizedQuery == null || normalizedQuery.isBlank()) {
      return "";
    }
    return normalizedQuery.replaceFirst("^\\s*\\d{1,8}\\b\\s*", "").trim();
  }

  private static boolean addressHasHouseNumberPrefix(String normalizedDisplayName, String houseNumber) {
    if (houseNumber == null || houseNumber.isBlank()) {
      return true;
    }
    if (normalizedDisplayName == null || normalizedDisplayName.isBlank()) {
      return false;
    }
    Matcher matcher = Pattern.compile("^\\s*(\\d{1,8})\\b").matcher(normalizedDisplayName);
    if (!matcher.find()) {
      return false;
    }
    return houseNumber.equals(matcher.group(1));
  }

  private static boolean queryLooksLikeZipOrAddress(String q, String key) {
    if (key.isEmpty()) {
      return false;
    }
    if (ZIP_PREFIX_PATTERN.matcher(key.replace(" ", "")).matches()) {
      return true;
    }
    return key.matches(".*\\d+.*") && (key.contains(" ") || q.contains(",") || q.contains("#"));
  }

  private static SuggestionEntry parseCoordinateSuggestion(String rawQuery) {
    if (rawQuery == null) {
      return null;
    }
    Matcher matcher = COORD_QUERY_PATTERN.matcher(rawQuery);
    if (!matcher.matches()) {
      return null;
    }
    double lat = parseDouble(matcher.group(1), Double.NaN);
    double lon = parseDouble(matcher.group(2), Double.NaN);
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || Math.abs(lat) > 90.0
        || Math.abs(lon) > 180.0) {
      return null;
    }
    String label = String.format(Locale.ROOT, "Coordinates %.6f, %.6f", lat, lon);
    return new SuggestionEntry(label, "coordinate_query", lat, lon, System.currentTimeMillis());
  }

  private static String[] parsePoiSearchIntent(String normalizedQuery) {
    if (normalizedQuery == null || normalizedQuery.isBlank()) {
      return null;
    }
    String query = normalizedQuery.trim();
    String[] separators = {" in ", " near ", " at "};
    for (String separator : separators) {
      int idx = query.indexOf(separator);
      if (idx <= 0 || idx >= query.length() - separator.length()) {
        continue;
      }
      String poiTerm = query.substring(0, idx).trim();
      String cityHint = query.substring(idx + separator.length()).trim();
      if (poiTerm.isBlank() || cityHint.isBlank()) {
        continue;
      }
      if (cityHint.matches(".*\\d+.*")) {
        continue;
      }
      return new String[] {poiTerm, cityHint};
    }
    String bestCityHint = "";
    String bestPoiTerm = "";
    int bestPrefixLength = -1;
    for (AddressCatalogEntry entry : buildCombinedAddressCatalogEntries()) {
      if (!isCityCatalogEntry(entry)) {
        continue;
      }
      String cityKey = entry.queryKey;
      if (cityKey.isBlank()) {
        continue;
      }
      List<String> prefixCandidates = new ArrayList<>();
      prefixCandidates.add(cityKey);
      int commaIdx = entry.displayName.indexOf(',');
      if (commaIdx > 0) {
        String cityOnly = normalizeSuggestToken(entry.displayName.substring(0, commaIdx));
        if (!cityOnly.isBlank()) {
          prefixCandidates.add(cityOnly);
        }
      }
      for (String prefixCandidate : prefixCandidates) {
        if (prefixCandidate.isBlank() || query.equals(prefixCandidate)) {
          continue;
        }
        String prefix = prefixCandidate + " ";
        if (!query.startsWith(prefix)) {
          continue;
        }
        String poiTerm = query.substring(prefix.length()).trim();
        if (poiTerm.isBlank()) {
          continue;
        }
        if (prefixCandidate.length() > bestPrefixLength) {
          bestPrefixLength = prefixCandidate.length();
          bestCityHint = cityKey;
          bestPoiTerm = poiTerm;
        }
      }
    }
    if (!bestCityHint.isBlank() && !bestPoiTerm.isBlank()) {
      return new String[] {bestPoiTerm, bestCityHint};
    }
    return null;
  }

  private static SuggestionEntry resolvePoiCityScopeCenter(
      String cityHintRaw, String cityHintKey, double biasLat, double biasLon, boolean hasBias) {
    if (cityHintKey == null || cityHintKey.isBlank()) {
      return null;
    }
    AddressCatalogEntry bestCatalogCity = null;
    double bestCatalogScore = Double.NEGATIVE_INFINITY;
    for (AddressCatalogEntry entry : buildCombinedAddressCatalogEntries()) {
      if (!isCityCatalogEntry(entry)) {
        continue;
      }
      double score =
          cityCatalogTextScore(cityHintKey, entry.queryKey)
              + shardScoreFor(entry.lat, entry.lon, biasLat, biasLon, hasBias)
              + nearbyDistanceScore(entry.lat, entry.lon, biasLat, biasLon, hasBias);
      if (score > bestCatalogScore) {
        bestCatalogScore = score;
        bestCatalogCity = entry;
      }
    }
    if (bestCatalogCity != null && bestCatalogScore > 160.0) {
      return new SuggestionEntry(
          bestCatalogCity.displayName,
          "poi_city_scope_catalog",
          bestCatalogCity.lat,
          bestCatalogCity.lon,
          bestCatalogCity.updatedAtMs);
    }
    if (isCityStateAbbrevQuery(cityHintKey) && !ADDRESS_CATALOG.containsKey(cityHintKey)) {
      AddressCatalogEntry discovered =
          lookupNamedCommunityCityState(cityHintRaw, cityHintKey, biasLat, biasLon, hasBias);
      if (discovered != null) {
        ADDRESS_CATALOG.putIfAbsent(cityHintKey, discovered);
        persistAddressCatalogToDisk();
        return new SuggestionEntry(
            discovered.displayName,
            "poi_city_scope_geocode",
            discovered.lat,
            discovered.lon,
            discovered.updatedAtMs);
      }
    }
    String baseUrl = NOMINATIM_SEARCH_URL + "?format=json&limit=5&q=" + urlEncode(cityHintRaw);
    String geocodeBody = null;
    boolean bounded = false;
    if (hasBias) {
      String viewbox =
          trimDouble(biasLon - GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLat - GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLon + GEOCODE_BIAS_RADIUS_DEGREES)
              + ","
              + trimDouble(biasLat + GEOCODE_BIAS_RADIUS_DEGREES);
      geocodeBody = httpGetExternal(baseUrl + "&viewbox=" + viewbox + "&bounded=1");
      bounded = geocodeBody != null && looksLikeJson(geocodeBody) && !"[]".equals(geocodeBody.trim());
    }
    if (!bounded) {
      geocodeBody = httpGetExternal(baseUrl);
    }
    List<SuggestionEntry> geocodeEntries = parseGeocodeSuggestionEntries(geocodeBody, 5);
    for (SuggestionEntry geocodeEntry : geocodeEntries) {
      if (!isAllowedRegionalSuggestionEntry(geocodeEntry)) {
        continue;
      }
      if (isSpecificAddressDisplayName(geocodeEntry.displayName)) {
        continue;
      }
      return new SuggestionEntry(
          geocodeEntry.displayName, "poi_city_scope_geocode", geocodeEntry.lat, geocodeEntry.lon, 0L);
    }
    return null;
  }

  private static List<SuggestionEntry> collectCoordinateCatalogCandidates(
      String queryKey, double biasLat, double biasLon, boolean hasBias) {
    List<SuggestionEntry> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    GpsPoint latest = latestGpsPoint;
    if (latest != null) {
      String label = String.format(Locale.ROOT, "Live GPS %.5f, %.5f", latest.lat, latest.lon);
      out.add(new SuggestionEntry(label, "coordinate_catalog", latest.lat, latest.lon, latest.receivedAtMs));
      seen.add(String.format(Locale.ROOT, "%.5f,%.5f", latest.lat, latest.lon));
    }
    List<GpsPoint> recent = copyRecentTrack(24);
    for (GpsPoint point : recent) {
      String key = String.format(Locale.ROOT, "%.5f,%.5f", point.lat, point.lon);
      if (seen.contains(key)) {
        continue;
      }
      seen.add(key);
      String label =
          String.format(Locale.ROOT, "Recent GPS %.5f, %.5f", point.lat, point.lon);
      out.add(new SuggestionEntry(label, "coordinate_catalog", point.lat, point.lon, point.receivedAtMs));
    }
    if (!hasBias || queryKey.isEmpty()) {
      return out;
    }
    List<SuggestionEntry> filtered = new ArrayList<>();
    for (SuggestionEntry entry : out) {
      String entryKey = normalizeSuggestToken(entry.displayName);
      String coordKey = normalizeSuggestToken(String.format(Locale.ROOT, "%.5f %.5f", entry.lat, entry.lon));
      if (entryKey.startsWith(queryKey) || coordKey.startsWith(queryKey) || coordKey.contains(queryKey)) {
        filtered.add(entry);
      }
    }
    return filtered.isEmpty() ? out : filtered;
  }

  private static String suggestPoiCacheKey(double lat, double lon, double radiusM, int zoom) {
    int x = MapModel.lonToTileX(lon, zoom);
    int y = MapModel.latToTileY(lat, zoom);
    return "z" + zoom + "/" + x + "/" + y + "/r" + Math.round(radiusM);
  }

  private static List<SuggestionEntry> collectPoiCatalogCandidates(
      double centerLat, double centerLon, String queryKey) {
    List<SuggestionEntry> out = new ArrayList<>();
    Set<String> dedupe = new HashSet<>();
    double[][] sweeps = {
        {2200.0, 14.0},
        {8000.0, 13.0},
        {26000.0, 12.0}
    };
    for (double[] sweep : sweeps) {
      double radiusM = sweep[0];
      int zoom = (int) sweep[1];
      String cacheKey = suggestPoiCacheKey(centerLat, centerLon, radiusM, zoom);
      String sceneJson = getCachedString(SUGGEST_POI_SCENE_CACHE, cacheKey);
      if (sceneJson == null) {
        sceneJson = ProprietaryMapEngine.sceneJson(centerLat, centerLon, radiusM, zoom);
        if (sceneJson != null && !sceneJson.isBlank()) {
          putCachedString(
              SUGGEST_POI_SCENE_CACHE,
              cacheKey,
              sceneJson,
              SUGGEST_POI_CACHE_TTL_MS,
              SUGGEST_POI_CACHE_MAX_ENTRIES);
        }
      }
      if (sceneJson == null || sceneJson.isBlank()) {
        continue;
      }
      Matcher matcher = SCENE_POI_OBJECT_PATTERN.matcher(sceneJson);
      while (matcher.find()) {
        String name = unescapeJsonString(matcher.group(1)).trim();
        if (name.isBlank()) {
          continue;
        }
        String kind = unescapeJsonString(matcher.group(2)).trim();
        double lat = parseDouble(matcher.group(3), Double.NaN);
        double lon = parseDouble(matcher.group(4), Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
          continue;
        }
        String label = kind.isBlank() ? name : (name + " (" + kind + ")");
        String labelKey = normalizeSuggestToken(label);
        if (!queryKey.isEmpty()
            && !labelKey.contains(queryKey)
            && !queryKey.contains(labelKey)
            && catalogTokenOverlapScore(queryKey, labelKey) <= 0.0) {
          continue;
        }
        String sourceTier = zoom >= 14 ? "poi_catalog_near" : (zoom >= 13 ? "poi_catalog_mid" : "poi_catalog_far");
        String dedupeKey = labelKey + "|" + String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
        if (dedupe.contains(dedupeKey)) {
          continue;
        }
        dedupe.add(dedupeKey);
        out.add(new SuggestionEntry(label, sourceTier, lat, lon, 0L));
        if (out.size() >= 420) {
          return out;
        }
      }
    }
    return out;
  }

  private static String unescapeJsonString(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(raw.length());
    boolean escaping = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (escaping) {
        switch (c) {
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          case '/':
            sb.append('/');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          default:
            sb.append(c);
            break;
        }
        escaping = false;
        continue;
      }
      if (c == '\\') {
        escaping = true;
      } else {
        sb.append(c);
      }
    }
    if (escaping) {
      sb.append('\\');
    }
    return sb.toString();
  }

  private static String shardTierFor(double candidateLat, double candidateLon, double biasLat, double biasLon) {
    ZhsCellData candidate = buildZhsCellData(candidateLat, candidateLon);
    ZhsCellData bias = buildZhsCellData(biasLat, biasLon);
    if (candidate.zZoom == bias.zZoom && candidate.zX == bias.zX && candidate.zY == bias.zY) {
      return "tile";
    }
    if (candidate.hResolution == bias.hResolution
        && candidate.hQ == bias.hQ
        && candidate.hR == bias.hR) {
      return "hex";
    }
    if (candidate.sLevel == bias.sLevel
        && candidate.sFace == bias.sFace
        && candidate.sI == bias.sI
        && candidate.sJ == bias.sJ) {
      return "s2";
    }
    return "global";
  }

  private static double shardScoreFor(
      double candidateLat, double candidateLon, double biasLat, double biasLon, boolean hasBias) {
    if (!hasBias) {
      return 0.0;
    }
    String tier = shardTierFor(candidateLat, candidateLon, biasLat, biasLon);
    switch (tier) {
      case "tile":
        return 900.0;
      case "hex":
        return 620.0;
      case "s2":
        return 420.0;
      default:
        double meters = haversineMeters(candidateLat, candidateLon, biasLat, biasLon);
        return Math.max(0.0, 260.0 / (1.0 + (meters / 700.0)));
    }
  }
  private static double nearbyDistanceScore(
      double candidateLat, double candidateLon, double biasLat, double biasLon, boolean hasBias) {
    if (!hasBias) {
      return 0.0;
    }
    double meters = haversineMeters(candidateLat, candidateLon, biasLat, biasLon);
    if (meters <= 1500.0) {
      return 320.0;
    }
    if (meters <= 7000.0) {
      return 180.0;
    }
    if (meters <= 20000.0) {
      return 80.0;
    }
    return Math.max(-120.0, -meters / 1400.0);
  }

  private static double suggestTextScore(String queryKey, String candidateKey) {
    if (queryKey.isEmpty() || candidateKey.isEmpty()) {
      return 0.0;
    }
    if (queryKey.equals(candidateKey)) {
      return 1300.0;
    }
    double score = 0.0;
    if (candidateKey.startsWith(queryKey)) {
      score += 520.0;
    } else if (candidateKey.contains(queryKey)) {
      score += 220.0;
    }
    if (queryKey.length() >= 3 && ZIP_PREFIX_PATTERN.matcher(queryKey.replace(" ", "")).matches()) {
      if (candidateKey.replace(" ", "").startsWith(queryKey.replace(" ", ""))) {
        score += 340.0;
      }
    }
    List<String> queryTokens = splitWords(queryKey);
    List<String> candidateTokens = splitWords(candidateKey);
    for (String qt : queryTokens) {
      for (String ct : candidateTokens) {
        if (ct.startsWith(qt)) {
          score += 55.0;
          break;
        }
        if (ct.contains(qt)) {
          score += 24.0;
          break;
        }
      }
    }
    score += catalogTokenOverlapScore(queryKey, candidateKey);
    return score;
  }

  private static double cityCatalogTextScore(String queryKey, String candidateKey) {
    if (queryKey.isEmpty() || candidateKey.isEmpty()) {
      return 0.0;
    }
    if (queryKey.equals(candidateKey)) {
      return 1800.0;
    }
    double score = 0.0;
    if (candidateKey.startsWith(queryKey)) {
      score += 650.0;
    } else if (candidateKey.contains(queryKey)) {
      score += 220.0;
    }
    score += catalogTokenOverlapScore(queryKey, candidateKey);
    List<String> queryTokens = splitWords(queryKey);
    List<String> candidateTokens = splitWords(candidateKey);
    for (String qt : queryTokens) {
      for (String ct : candidateTokens) {
        if (ct.equals(qt)) {
          score += 45.0;
          break;
        }
        if (ct.startsWith(qt)) {
          score += 18.0;
          break;
        }
      }
    }
    return score;
  }

  private static List<SuggestionEntry> parseGeocodeSuggestionEntries(String geocodeBody, int limit) {
    List<SuggestionEntry> out = new ArrayList<>();
    if (geocodeBody == null || geocodeBody.isBlank() || !looksLikeJson(geocodeBody)) {
      return out;
    }
    String body = geocodeBody.trim();
    if (!body.startsWith("[") || !body.endsWith("]")) {
      return out;
    }
    String arrayBody = body.substring(1, body.length() - 1);
    for (String objectJson : splitTopLevelObjects(arrayBody)) {
      String display = extractStringFieldByName(objectJson, "display_name", "").trim();
      double lat = extractDoubleField(objectJson, "lat", Double.NaN);
      double lon = extractDoubleField(objectJson, "lon", Double.NaN);
      if (display.isBlank() || !Double.isFinite(lat) || !Double.isFinite(lon)) {
        continue;
      }
      out.add(new SuggestionEntry(display, "geocode_fallback", lat, lon, 0L));
      if (out.size() >= limit) {
        break;
      }
    }
    return out;
  }

  private static String buildAddressCatalogSuggestJson(Map<String, String> query) {
    String q = query.getOrDefault("q", "").trim();
    String queryKey = normalizeSuggestToken(q);
    String houseNumber = extractLeadingHouseNumber(queryKey);
    String poiQueryKey = queryKey;
    if (queryKey.isEmpty()) {
      return "{\"error\":\"missing_query\"}";
    }
    int limit = parseIntOrDefault(query.getOrDefault("limit", ""), SUGGEST_DEFAULT_LIMIT);
    if (limit < 1) {
      limit = 1;
    }
    if (limit > SUGGEST_MAX_LIMIT) {
      limit = SUGGEST_MAX_LIMIT;
    }

    double biasLat = parseDouble(query.get("lat"), Double.NaN);
    double biasLon = parseDouble(query.get("lon"), Double.NaN);
    if (!Double.isFinite(biasLat) || !Double.isFinite(biasLon)) {
      GpsPoint latest = latestGpsPoint;
      if (latest != null) {
        biasLat = latest.lat;
        biasLon = latest.lon;
      }
    }
    boolean hasBias =
        Double.isFinite(biasLat)
            && Double.isFinite(biasLon)
            && Math.abs(biasLat) <= 90.0
            && Math.abs(biasLon) <= 180.0;
    double poiBiasLat = biasLat;
    double poiBiasLon = biasLon;
    boolean hasPoiBias = hasBias;
    String[] poiIntent = parsePoiSearchIntent(queryKey);
    boolean hasPoiIntent = poiIntent != null;
    if (poiIntent != null) {
      String poiTermKey = poiIntent[0];
      String cityHintKey = poiIntent[1];
      String cityHintRaw = toTitleCaseWords(cityHintKey);
      SuggestionEntry scopedCityCenter =
          resolvePoiCityScopeCenter(cityHintRaw, cityHintKey, biasLat, biasLon, hasBias);
      if (scopedCityCenter != null) {
        poiBiasLat = scopedCityCenter.lat;
        poiBiasLon = scopedCityCenter.lon;
        hasPoiBias = true;
        if (!poiTermKey.isBlank()) {
          poiQueryKey = poiTermKey;
        }
      }
    }
    List<Map.Entry<SuggestionEntry, Double>> scored = new ArrayList<>();

    SuggestionEntry coordinateQuerySuggestion = parseCoordinateSuggestion(q);
    if (coordinateQuerySuggestion != null) {
      double score =
          1600.0
              + shardScoreFor(
                  coordinateQuerySuggestion.lat,
                  coordinateQuerySuggestion.lon,
                  biasLat,
                  biasLon,
                  hasBias);
      scored.add(Map.entry(coordinateQuerySuggestion, score));
    }

    if (!hasPoiIntent && isCityStateAbbrevQuery(queryKey) && !ADDRESS_CATALOG.containsKey(queryKey)) {
      AddressCatalogEntry discovered =
          lookupNamedCommunityCityState(q, queryKey, biasLat, biasLon, hasBias);
      if (discovered != null) {
        ADDRESS_CATALOG.putIfAbsent(queryKey, discovered);
        persistAddressCatalogToDisk();
      }
    }

    if (!hasPoiIntent) {
      for (AddressCatalogEntry entry : buildCombinedAddressCatalogEntries()) {
        if (isCityCatalogEntry(entry)) {
          if (!queryKey.equals(entry.queryKey)) {
            continue;
          }
        } else if (!isSpecificAddressDisplayName(entry.displayName)) {
          continue;
        }
        SuggestionEntry candidate =
            new SuggestionEntry(entry.displayName, entry.source, entry.lat, entry.lon, entry.updatedAtMs);
        if (!houseNumber.isBlank()
            && !addressHasHouseNumberPrefix(normalizeSuggestToken(candidate.displayName), houseNumber)) {
          continue;
        }
        double score =
            suggestTextScore(queryKey, entry.queryKey)
                + shardScoreFor(entry.lat, entry.lon, biasLat, biasLon, hasBias)
                + nearbyDistanceScore(entry.lat, entry.lon, biasLat, biasLon, hasBias);
        if (score > 0.0) {
          scored.add(Map.entry(candidate, score));
        }
      }
    }

    if (!hasPoiIntent) {
      for (SuggestionEntry coordinateEntry :
          collectCoordinateCatalogCandidates(queryKey, biasLat, biasLon, hasBias)) {
        if (!houseNumber.isBlank()) {
          continue;
        }
        if (!isSpecificAddressDisplayName(coordinateEntry.displayName)) {
          continue;
        }
        String candidateKey = normalizeSuggestToken(coordinateEntry.displayName);
        String coordKey =
            normalizeSuggestToken(
                String.format(Locale.ROOT, "%.6f %.6f", coordinateEntry.lat, coordinateEntry.lon));
        double score =
            Math.max(suggestTextScore(queryKey, candidateKey), suggestTextScore(queryKey, coordKey))
                + shardScoreFor(coordinateEntry.lat, coordinateEntry.lon, biasLat, biasLon, hasBias)
                + nearbyDistanceScore(coordinateEntry.lat, coordinateEntry.lon, biasLat, biasLon, hasBias);
        if (score > 0.0) {
          scored.add(Map.entry(coordinateEntry, score));
        }
      }
    }

    if (hasPoiBias) {
      for (SuggestionEntry poi : collectPoiCatalogCandidates(poiBiasLat, poiBiasLon, poiQueryKey)) {
        if (!houseNumber.isBlank()
            && !addressHasHouseNumberPrefix(normalizeSuggestToken(poi.displayName), houseNumber)) {
          continue;
        }
        String candidateKey = normalizeSuggestToken(poi.displayName);
        double score =
            suggestTextScore(poiQueryKey, candidateKey)
                + shardScoreFor(poi.lat, poi.lon, poiBiasLat, poiBiasLon, hasPoiBias)
                + nearbyDistanceScore(poi.lat, poi.lon, poiBiasLat, poiBiasLon, hasPoiBias);
        if ("poi_catalog_near".equals(poi.source)) {
          score += 130.0;
        } else if ("poi_catalog_mid".equals(poi.source)) {
          score += 50.0;
        } else if ("poi_catalog_far".equals(poi.source)) {
          score -= 25.0;
        }
        if (score > 0.0) {
          scored.add(Map.entry(poi, score));
        }
      }
    }

    scored.sort(
        (a, b) -> {
          int byScore = Double.compare(b.getValue(), a.getValue());
          if (byScore != 0) {
            return byScore;
          }
          return Long.compare(b.getKey().updatedAtMs, a.getKey().updatedAtMs);
        });

    List<Map.Entry<SuggestionEntry, Double>> uniqueRanked = new ArrayList<>();
    Set<String> dedupe = new HashSet<>();
    for (Map.Entry<SuggestionEntry, Double> entry : scored) {
      SuggestionEntry s = entry.getKey();
      String dedupeKey =
          normalizeSuggestToken(s.displayName)
              + "|"
              + String.format(Locale.ROOT, "%.5f,%.5f", s.lat, s.lon);
      if (dedupe.contains(dedupeKey)) {
        continue;
      }
      dedupe.add(dedupeKey);
      uniqueRanked.add(entry);
      if (uniqueRanked.size() >= limit) {
        break;
      }
    }

    boolean usedFallback = false;
    if (uniqueRanked.size() < limit) {
      boolean allowFallback = queryKey.length() >= 3 || queryLooksLikeZipOrAddress(q, queryKey);
      if (allowFallback) {
        String baseUrl = NOMINATIM_SEARCH_URL + "?format=json&limit=5&q=" + urlEncode(q);
        String geocodeBody = null;
        boolean bounded = false;
        if (hasBias) {
          String viewbox =
              trimDouble(biasLon - GEOCODE_BIAS_RADIUS_DEGREES)
                  + ","
                  + trimDouble(biasLat - GEOCODE_BIAS_RADIUS_DEGREES)
                  + ","
                  + trimDouble(biasLon + GEOCODE_BIAS_RADIUS_DEGREES)
                  + ","
                  + trimDouble(biasLat + GEOCODE_BIAS_RADIUS_DEGREES);
          geocodeBody = httpGetExternal(baseUrl + "&viewbox=" + viewbox + "&bounded=1");
          bounded = geocodeBody != null && looksLikeJson(geocodeBody) && !"[]".equals(geocodeBody.trim());
        }
        if (!bounded) {
          geocodeBody = httpGetExternal(baseUrl);
        }
        List<SuggestionEntry> geocodeEntries =
            parseGeocodeSuggestionEntries(geocodeBody, limit - uniqueRanked.size());
        for (SuggestionEntry geocodeEntry : geocodeEntries) {
          if (!isAllowedRegionalSuggestionEntry(geocodeEntry)) {
            continue;
          }
          if (!houseNumber.isBlank()
              && !addressHasHouseNumberPrefix(
                  normalizeSuggestToken(geocodeEntry.displayName), houseNumber)) {
            continue;
          }
          if (!isSpecificAddressDisplayName(geocodeEntry.displayName)
              && !isCityStateAbbrevQuery(queryKey)) {
            continue;
          }
          SuggestionEntry candidateEntry = geocodeEntry;
          if (!hasPoiIntent
              && isCityStateAbbrevQuery(queryKey)
              && !isSpecificAddressDisplayName(geocodeEntry.displayName)) {
            String cityPart = cityPartFromCityStateQuery(queryKey);
            String stateAbbrev = extractTrailingStateAbbrev(queryKey);
            if (!cityPart.isBlank() && !stateAbbrev.isBlank()) {
              candidateEntry =
                  new SuggestionEntry(
                      toTitleCaseWords(cityPart) + ", " + stateAbbrev,
                      "city_catalog",
                      geocodeEntry.lat,
                      geocodeEntry.lon,
                      0L);
            }
          }
          String dedupeKey =
              normalizeSuggestToken(candidateEntry.displayName)
                  + "|"
                  + String.format(Locale.ROOT, "%.5f,%.5f", candidateEntry.lat, candidateEntry.lon);
          if (dedupe.contains(dedupeKey)) {
            continue;
          }
          dedupe.add(dedupeKey);
          double score =
              suggestTextScore(queryKey, normalizeSuggestToken(candidateEntry.displayName))
                  + shardScoreFor(candidateEntry.lat, candidateEntry.lon, biasLat, biasLon, hasBias)
                  + nearbyDistanceScore(candidateEntry.lat, candidateEntry.lon, biasLat, biasLon, hasBias)
                  - 60.0;
          uniqueRanked.add(Map.entry(candidateEntry, score));
          usedFallback = true;
          if (uniqueRanked.size() >= limit) {
            break;
          }
        }
      }
    }

    StringBuilder resultJson = new StringBuilder("[");
    for (int i = 0; i < uniqueRanked.size(); i++) {
      if (i > 0) {
        resultJson.append(",");
      }
      SuggestionEntry entry = uniqueRanked.get(i).getKey();
      String shardTier =
          hasBias ? shardTierFor(entry.lat, entry.lon, biasLat, biasLon) : "global";
      resultJson.append(suggestionEntryToJson(entry, uniqueRanked.get(i).getValue(), shardTier));
    }
    resultJson.append("]");

    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"query\":\"" + jsonEscape(q) + "\","
        + "\"normalized_query\":\"" + jsonEscape(queryKey) + "\","
        + "\"limit\":" + limit + ","
        + "\"bias_applied\":" + hasBias + ","
        + "\"fallback_used\":" + usedFallback + ","
        + "\"results\":" + resultJson
        + "}";
  }

  private static String buildAddressCatalogResolveJson(Map<String, String> query) {
    String q = query.getOrDefault("q", "").trim();
    String key = normalizeCatalogQuery(q);
    if (key.isEmpty()) {
      return "{\"error\":\"missing_query\"}";
    }
    double biasLat = parseDouble(query.get("lat"), Double.NaN);
    double biasLon = parseDouble(query.get("lon"), Double.NaN);
    boolean hasBias =
        Double.isFinite(biasLat)
            && Double.isFinite(biasLon)
            && Math.abs(biasLat) <= 90.0
            && Math.abs(biasLon) <= 180.0;
    List<Map.Entry<AddressCatalogEntry, Double>> scored = new ArrayList<>();
    for (AddressCatalogEntry entry : buildCombinedAddressCatalogEntries()) {
      if (isCityCatalogEntry(entry)) {
        if (!key.equals(entry.queryKey)) {
          continue;
        }
      } else if (!isSpecificAddressDisplayName(entry.displayName)) {
        continue;
      }
      double score = catalogMatchScore(key, entry, biasLat, biasLon, hasBias);
      if (score > 0.0) {
        scored.add(Map.entry(entry, score));
      }
    }
    if (scored.isEmpty() && isCityStateAbbrevQuery(key) && !ADDRESS_CATALOG.containsKey(key)) {
      AddressCatalogEntry discovered =
          lookupNamedCommunityCityState(q, key, biasLat, biasLon, hasBias);
      if (discovered != null) {
        ADDRESS_CATALOG.putIfAbsent(key, discovered);
        persistAddressCatalogToDisk();
        double score = catalogMatchScore(key, discovered, biasLat, biasLon, hasBias);
        if (score > 0.0) {
          scored.add(Map.entry(discovered, score));
        }
      }
    }
    if (scored.isEmpty()) {
      return "{"
          + "\"ts\":\"" + Instant.now().toString() + "\","
          + "\"status\":\"miss\","
          + "\"bias_applied\":" + hasBias + ","
          + "\"results\":[]"
          + "}";
    }
    scored.sort(
        (a, b) -> {
          int byScore = Double.compare(b.getValue(), a.getValue());
          if (byScore != 0) {
            return byScore;
          }
          return Long.compare(b.getKey().updatedAtMs, a.getKey().updatedAtMs);
        });
    if (scored.get(0).getValue() < 200.0) {
      return "{"
          + "\"ts\":\"" + Instant.now().toString() + "\","
          + "\"status\":\"miss\","
          + "\"bias_applied\":" + hasBias + ","
          + "\"results\":[]"
          + "}";
    }
    if (scored.size() > 1) {
      double top = scored.get(0).getValue();
      double second = scored.get(1).getValue();
      if (Math.abs(top - second) < 50.0) {
        return "{"
            + "\"ts\":\"" + Instant.now().toString() + "\","
            + "\"status\":\"ambiguous\","
            + "\"bias_applied\":" + hasBias + ","
            + "\"results\":[]"
            + "}";
      }
    }
    List<AddressCatalogEntry> results = new ArrayList<>();
    Set<String> dedupe = new HashSet<>();
    for (int i = 0; i < scored.size() && results.size() < 5; i++) {
      AddressCatalogEntry candidate = scored.get(i).getKey();
      String dedupeKey =
          normalizeCatalogQuery(candidate.displayName)
              + "|"
              + String.format(Locale.ROOT, "%.5f,%.5f", candidate.lat, candidate.lon);
      if (dedupe.contains(dedupeKey)) {
        continue;
      }
      dedupe.add(dedupeKey);
      results.add(candidate);
    }
    AddressCatalogEntry best = results.get(0);
    String matchType = key.equals(best.queryKey) ? "exact" : "fuzzy";
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"match\":\"" + matchType + "\","
        + "\"bias_applied\":" + hasBias + ","
        + "\"entry\":" + addressCatalogEntryToJson(best) + ","
        + "\"results\":" + addressCatalogEntriesToJson(results)
        + "}";
  }

  private static String buildAddressCatalogExportJson() {
    List<AddressCatalogEntry> entries = new ArrayList<>(ADDRESS_CATALOG.values());
    entries.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"catalog_size\":" + entries.size() + ","
        + "\"entries\":" + addressCatalogEntriesToJson(entries)
        + "}";
  }

  private static String buildAddressCatalogUpsertJson(Map<String, String> query, String body) {
    String queryRaw = extractStringFieldByName(body, "query", query.getOrDefault("query", ""));
    String display =
        extractStringFieldByName(
            body, "display_name", query.getOrDefault("display_name", queryRaw));
    String source =
        extractStringFieldByName(body, "source", query.getOrDefault("source", "unknown_client"));
    double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
    double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
    if (!Double.isFinite(lat)) {
      lat = extractDoubleField(body, "lat", Double.NaN);
    }
    if (!Double.isFinite(lon)) {
      lon = extractDoubleField(body, "lon", Double.NaN);
    }
    String key = normalizeCatalogQuery(queryRaw);
    if (key.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) {
      return "{\"error\":\"invalid_catalog_entry\"}";
    }
    if (display == null || display.isBlank()) {
      display = queryRaw;
    }
    AddressCatalogEntry entry =
        new AddressCatalogEntry(key, display.trim(), source.trim(), lat, lon, System.currentTimeMillis());
    if (!isAllowedRegionalCatalogEntry(entry)) {
      return "{\"error\":\"catalog_entry_out_of_allowed_regions\"}";
    }
    ADDRESS_CATALOG.put(key, entry);
    persistAddressCatalogToDisk();
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"catalog_size\":" + ADDRESS_CATALOG.size() + ","
        + "\"entry\":" + addressCatalogEntryToJson(entry)
        + "}";
  }

  private static String sanitizeErrorReportField(String value, int maxLen) {
    if (value == null) {
      return "";
    }
    String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
    if (normalized.length() <= maxLen) {
      return normalized;
    }
    return normalized.substring(0, Math.max(0, maxLen));
  }

  private static String normalizeSeverity(String raw) {
    String severity = sanitizeErrorReportField(raw, 16).toLowerCase(Locale.ROOT);
    if (severity.isBlank()) {
      return "error";
    }
    switch (severity) {
      case "debug":
      case "info":
      case "warn":
      case "warning":
      case "error":
      case "critical":
        return "warning".equals(severity) ? "warn" : severity;
      default:
        return "error";
    }
  }

  private static String errorReportEntryToJson(ErrorReportEntry entry) {
    return "{"
        + "\"id\":\"" + jsonEscape(entry.id) + "\","
        + "\"ts\":\"" + jsonEscape(entry.ts) + "\","
        + "\"created_at_ms\":" + entry.createdAtMs + ","
        + "\"user_id\":\"" + jsonEscape(entry.userId) + "\","
        + "\"client_id\":\"" + jsonEscape(entry.clientId) + "\","
        + "\"source\":\"" + jsonEscape(entry.source) + "\","
        + "\"severity\":\"" + jsonEscape(entry.severity) + "\","
        + "\"message\":\"" + jsonEscape(entry.message) + "\","
        + "\"details\":\"" + jsonEscape(entry.details) + "\""
        + "}";
  }

  private static String errorReportEntriesToJson(List<ErrorReportEntry> entries) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(errorReportEntryToJson(entries.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static void appendErrorReport(ErrorReportEntry entry) {
    synchronized (ERROR_REPORT_IO_LOCK) {
      ERROR_REPORTS.addLast(entry);
      while (ERROR_REPORTS.size() > ERROR_REPORT_MAX_ITEMS) {
        ERROR_REPORTS.removeFirst();
      }
      persistErrorReportsToDisk();
    }
  }

  private static String buildErrorReportSubmitJson(
      Map<String, String> query, String body, boolean analyticsOptOut) {
    if (analyticsOptOut) {
      return "{"
          + "\"ts\":\"" + Instant.now().toString() + "\","
          + "\"status\":\"skipped\","
          + "\"reason\":\"analytics_opt_out\""
          + "}";
    }
    String message =
        sanitizeErrorReportField(
            extractStringFieldByName(body, "message", query.getOrDefault("message", "")),
            800);
    if (message.isBlank()) {
      return "{\"error\":\"missing_report_message\"}";
    }
    String details =
        sanitizeErrorReportField(
            extractStringFieldByName(body, "details", query.getOrDefault("details", "")),
            4000);
    String source =
        sanitizeErrorReportField(
            extractStringFieldByName(body, "source", query.getOrDefault("source", "unknown_client")),
            64);
    if (source.isBlank()) {
      source = "unknown_client";
    }
    String userId =
        sanitizeErrorReportField(
            extractStringFieldByName(body, "user_id", query.getOrDefault("user_id", "unknown_user")),
            96);
    if (userId.isBlank()) {
      userId = "unknown_user";
    }
    String clientId =
        sanitizeErrorReportField(
            extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", "")),
            128);
    String severity =
        normalizeSeverity(
            extractStringFieldByName(body, "severity", query.getOrDefault("severity", "error")));
    long nowMs = System.currentTimeMillis();
    String id =
        "er-" + nowMs + "-" + Long.toString(ERROR_REPORT_SEQ.getAndIncrement(), 36);
    ErrorReportEntry entry =
        new ErrorReportEntry(
            id,
            Instant.ofEpochMilli(nowMs).toString(),
            userId,
            clientId,
            source,
            severity,
            message,
            details,
            nowMs);
    appendErrorReport(entry);
    int storedCount;
    synchronized (ERROR_REPORT_IO_LOCK) {
      storedCount = ERROR_REPORTS.size();
    }
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"stored_count\":" + storedCount + ","
        + "\"entry\":" + errorReportEntryToJson(entry)
        + "}";
  }

  private static String buildErrorReportRecentJson(Map<String, String> query) {
    int limit =
        parseIntOrDefault(
            query.getOrDefault("limit", String.valueOf(ERROR_REPORT_DEFAULT_LIMIT)),
            ERROR_REPORT_DEFAULT_LIMIT);
    if (limit < 1) {
      limit = 1;
    }
    if (limit > ERROR_REPORT_MAX_LIMIT) {
      limit = ERROR_REPORT_MAX_LIMIT;
    }
    long sinceMs =
        parseLongOrDefault(
            query.getOrDefault("since_ms", query.getOrDefault("since", "0")),
            0L);
    String sourceFilter = sanitizeErrorReportField(query.getOrDefault("source", ""), 64);
    String userFilter = sanitizeErrorReportField(query.getOrDefault("user_id", ""), 96);
    List<ErrorReportEntry> selected = new ArrayList<>();
    int totalStored;
    synchronized (ERROR_REPORT_IO_LOCK) {
      totalStored = ERROR_REPORTS.size();
      List<ErrorReportEntry> snapshot = new ArrayList<>(ERROR_REPORTS);
      for (int i = snapshot.size() - 1; i >= 0; i--) {
        ErrorReportEntry entry = snapshot.get(i);
        if (entry.createdAtMs < sinceMs) {
          continue;
        }
        if (!sourceFilter.isBlank() && !sourceFilter.equalsIgnoreCase(entry.source)) {
          continue;
        }
        if (!userFilter.isBlank() && !userFilter.equalsIgnoreCase(entry.userId)) {
          continue;
        }
        selected.add(entry);
        if (selected.size() >= limit) {
          break;
        }
      }
    }
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"limit\":" + limit + ","
        + "\"since_ms\":" + sinceMs + ","
        + "\"total_stored\":" + totalStored + ","
        + "\"count\":" + selected.size() + ","
        + "\"results\":" + errorReportEntriesToJson(selected)
        + "}";
  }

  private static void loadErrorReportsFromDisk() {
    synchronized (ERROR_REPORT_IO_LOCK) {
      try {
        if (!Files.exists(ERROR_REPORT_STORE_PATH)) {
          return;
        }
        List<String> lines = Files.readAllLines(ERROR_REPORT_STORE_PATH, StandardCharsets.UTF_8);
        long maxSeq = 0L;
        int loaded = 0;
        for (String line : lines) {
          if (line == null || line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] cols = line.split("\t", -1);
          if (cols.length < 9) {
            continue;
          }
          String id = decodeCatalogField(cols[0]).trim();
          String ts = decodeCatalogField(cols[1]).trim();
          String userId = decodeCatalogField(cols[2]).trim();
          String clientId = decodeCatalogField(cols[3]).trim();
          String source = decodeCatalogField(cols[4]).trim();
          String severity = normalizeSeverity(decodeCatalogField(cols[5]).trim());
          String message = sanitizeErrorReportField(decodeCatalogField(cols[6]), 800);
          String details = sanitizeErrorReportField(decodeCatalogField(cols[7]), 4000);
          long createdAtMs = parseLongOrDefault(cols[8], 0L);
          if (id.isBlank() || message.isBlank()) {
            continue;
          }
          if (createdAtMs <= 0L) {
            createdAtMs = System.currentTimeMillis();
          }
          ERROR_REPORTS.addLast(
              new ErrorReportEntry(
                  id,
                  ts.isBlank() ? Instant.ofEpochMilli(createdAtMs).toString() : ts,
                  userId.isBlank() ? "unknown_user" : userId,
                  clientId,
                  source.isBlank() ? "unknown_client" : source,
                  severity,
                  message,
                  details,
                  createdAtMs));
          while (ERROR_REPORTS.size() > ERROR_REPORT_MAX_ITEMS) {
            ERROR_REPORTS.removeFirst();
          }
          int dash = id.lastIndexOf('-');
          if (dash > 0 && dash < id.length() - 1) {
            try {
              maxSeq = Math.max(maxSeq, Long.parseLong(id.substring(dash + 1), 36));
            } catch (NumberFormatException ignored) {
            }
          }
          loaded++;
        }
        ERROR_REPORT_SEQ.set(Math.max(ERROR_REPORT_SEQ.get(), maxSeq + 1L));
        System.out.printf(
            Locale.ROOT,
            "[java-backend] error report store loaded: %d entries from %s%n",
            loaded,
            ERROR_REPORT_STORE_PATH);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] error report load failed (%s): %s%n",
            ERROR_REPORT_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static void persistErrorReportsToDisk() {
    synchronized (ERROR_REPORT_IO_LOCK) {
      try {
        Path parent = ERROR_REPORT_STORE_PATH.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# id\tts\tuser_id\tclient_id\tsource\tseverity\tmessage\tdetails\tcreated_at_ms\n");
        for (ErrorReportEntry entry : ERROR_REPORTS) {
          sb.append(encodeCatalogField(entry.id))
              .append('\t')
              .append(encodeCatalogField(entry.ts))
              .append('\t')
              .append(encodeCatalogField(entry.userId))
              .append('\t')
              .append(encodeCatalogField(entry.clientId))
              .append('\t')
              .append(encodeCatalogField(entry.source))
              .append('\t')
              .append(encodeCatalogField(entry.severity))
              .append('\t')
              .append(encodeCatalogField(entry.message))
              .append('\t')
              .append(encodeCatalogField(entry.details))
              .append('\t')
              .append(entry.createdAtMs)
              .append('\n');
        }
        Files.writeString(ERROR_REPORT_STORE_PATH, sb.toString(), StandardCharsets.UTF_8);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] error report persist failed (%s): %s%n",
            ERROR_REPORT_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static String encodeCatalogField(String value) {
    return urlEncode(value == null ? "" : value);
  }

  private static String decodeCatalogField(String value) {
    if (value == null) {
      return "";
    }
    try {
      return decodeComponent(value);
    } catch (Exception ex) {
      return "";
    }
  }

  private static String safeLogValue(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String cleaned = value.trim().replaceAll("\\s+", "_");
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  private static void logRequestRejection(
      String routeHint, HttpExchange exchange, String reason, int status, String phase) {
    try {
      String method = exchange == null ? "" : exchange.getRequestMethod();
      String remote = remoteAddressFromExchange(exchange);
      String path = routeHint;
      if (exchange != null && exchange.getRequestURI() != null && exchange.getRequestURI().getPath() != null) {
        String uriPath = exchange.getRequestURI().getPath().trim();
        if (!uriPath.isEmpty()) {
          path = uriPath;
        }
      }
      System.err.printf(
          Locale.ROOT,
          "[java-backend] request_rejected phase=%s reason=%s status=%d method=%s path=%s remote=%s%n",
          safeLogValue(phase, "unknown"),
          safeLogValue(reason, "invalid_request"),
          status,
          safeLogValue(method, "unknown"),
          safeLogValue(path, "unknown"),
          safeLogValue(remote, "unknown"));
    } catch (Exception ignored) {
    }
  }

  private static void loadAddressCatalogFromDisk() {
    synchronized (ADDRESS_CATALOG_IO_LOCK) {
      try {
        if (!Files.exists(ADDRESS_CATALOG_STORE_PATH)) {
          return;
        }
        List<String> lines = Files.readAllLines(ADDRESS_CATALOG_STORE_PATH, StandardCharsets.UTF_8);
        int loaded = 0;
        int skippedOutOfRegion = 0;
        for (String line : lines) {
          if (line == null || line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] cols = line.split("\t", -1);
          if (cols.length < 6) {
            continue;
          }
          String queryKey = normalizeCatalogQuery(decodeCatalogField(cols[0]));
          String displayName = decodeCatalogField(cols[1]).trim();
          String source = decodeCatalogField(cols[2]).trim();
          double lat = parseDouble(cols[3], Double.NaN);
          double lon = parseDouble(cols[4], Double.NaN);
          long updatedAtMs = parseLongOrDefault(cols[5], 0L);
          if (queryKey.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            continue;
          }
          if (displayName.isBlank()) {
            displayName = queryKey;
          }
          if (source.isBlank()) {
            source = "persisted";
          }
          AddressCatalogEntry loadedEntry =
              new AddressCatalogEntry(queryKey, displayName, source, lat, lon, updatedAtMs);
          if (!isAllowedRegionalCatalogEntry(loadedEntry)) {
            skippedOutOfRegion++;
            continue;
          }
          ADDRESS_CATALOG.put(queryKey, loadedEntry);
          loaded++;
        }
        System.out.printf(
            Locale.ROOT,
            "[java-backend] address catalog loaded: %d entries from %s (skipped_out_of_region=%d)%n",
            loaded,
            ADDRESS_CATALOG_STORE_PATH,
            skippedOutOfRegion);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] address catalog load failed (%s): %s%n",
            ADDRESS_CATALOG_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static void persistAddressCatalogToDisk() {
    synchronized (ADDRESS_CATALOG_IO_LOCK) {
      try {
        Path parent = ADDRESS_CATALOG_STORE_PATH.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        List<AddressCatalogEntry> entries = new ArrayList<>(ADDRESS_CATALOG.values());
        entries.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
        StringBuilder sb = new StringBuilder();
        sb.append("# query\tdisplay_name\tsource\tlat\tlon\tupdated_at_ms\n");
        for (AddressCatalogEntry entry : entries) {
          sb.append(encodeCatalogField(entry.queryKey))
              .append('\t')
              .append(encodeCatalogField(entry.displayName))
              .append('\t')
              .append(encodeCatalogField(entry.source))
              .append('\t')
              .append(trimDouble(entry.lat))
              .append('\t')
              .append(trimDouble(entry.lon))
              .append('\t')
              .append(entry.updatedAtMs)
              .append('\n');
        }
        Files.writeString(ADDRESS_CATALOG_STORE_PATH, sb.toString(), StandardCharsets.UTF_8);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] address catalog persist failed (%s): %s%n",
            ADDRESS_CATALOG_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static String fetchOsrmRouteAlternativesBody(
      double originLat, double originLon, double destLat, double destLon) {
    String cacheKey = osrmCacheKey(originLat, originLon, destLat, destLon);
    String cached = getCachedString(OSRM_ALT_CACHE, cacheKey);
    if (cached != null) {
      return cached;
    }
    String baseUrl =
        OSRM_ROUTE_BASE_URL
            + "/"
            + trimDouble(originLon)
            + ","
            + trimDouble(originLat)
            + ";"
            + trimDouble(destLon)
            + ","
            + trimDouble(destLat);
    String body =
        httpGetExternal(
            baseUrl
                + "?overview=full&geometries=geojson&alternatives=true&steps=true&annotations=distance,duration,maxspeed");
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      body =
          httpGetExternal(
              baseUrl
                  + "?overview=full&geometries=geojson&alternatives=true&steps=true&annotations=distance,duration");
    }
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      body =
          httpGetExternal(
              baseUrl
                  + "?overview=full&geometries=geojson&alternatives=true&steps=false");
    }
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      return null;
    }
    putCachedString(OSRM_ALT_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    putCachedString(OSRM_ROUTE_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    return body;
  }

  private static String fetchOsrmRouteViaWaypointBody(
      double originLat, double originLon, double waypointLat, double waypointLon, double destLat, double destLon) {
    String url =
        OSRM_ROUTE_BASE_URL
            + "/"
            + trimDouble(originLon)
            + ","
            + trimDouble(originLat)
            + ";"
            + trimDouble(waypointLon)
            + ","
            + trimDouble(waypointLat)
            + ";"
            + trimDouble(destLon)
            + ","
            + trimDouble(destLat)
            + "?overview=full&geometries=geojson&alternatives=false&steps=false";
    String body = httpGetExternal(url);
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      return null;
    }
    return body;
  }

  private static String osrmCacheKey(
      double originLat, double originLon, double destLat, double destLon) {
    return String.format(
        Locale.ROOT,
        "%.6f,%.6f->%.6f,%.6f",
        originLat,
        originLon,
        destLat,
        destLon);
  }

  private static String getCachedString(Map<String, TimedStringValue> cache, String key) {
    TimedStringValue value = cache.get(key);
    if (value == null) {
      return null;
    }
    long now = System.currentTimeMillis();
    if (now > value.expiresAtMs) {
      cache.remove(key, value);
      return null;
    }
    return value.value;
  }

  private static String getCachedValue(TimedStringValue value) {
    if (value == null) {
      return null;
    }
    long now = System.currentTimeMillis();
    if (now > value.expiresAtMs) {
      return null;
    }
    return value.value;
  }

  private static void putCachedString(
      Map<String, TimedStringValue> cache,
      String key,
      String value,
      long ttlMs,
      int maxEntries) {
    long now = System.currentTimeMillis();
    cache.put(key, new TimedStringValue(value, now + Math.max(1000L, ttlMs)));
    if (cache.size() <= maxEntries) {
      return;
    }
    int removed = 0;
    for (Map.Entry<String, TimedStringValue> entry : cache.entrySet()) {
      if (entry.getValue().expiresAtMs <= now || cache.size() > maxEntries) {
        cache.remove(entry.getKey());
        removed++;
      }
      if (cache.size() <= maxEntries || removed >= 64) {
        break;
      }
    }
  }

  private static String extractJsonArrayContent(String rawJson, String fieldName) {
    String key = "\"" + fieldName + "\"";
    int keyIndex = rawJson.indexOf(key);
    if (keyIndex < 0) {
      return null;
    }
    int arrayStart = rawJson.indexOf('[', keyIndex + key.length());
    if (arrayStart < 0) {
      return null;
    }
    int depth = 0;
    boolean inString = false;
    for (int i = arrayStart; i < rawJson.length(); i++) {
      char ch = rawJson.charAt(i);
      if (ch == '"' && (i == 0 || rawJson.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '[') {
        depth++;
      } else if (ch == ']') {
        depth--;
        if (depth == 0) {
          return rawJson.substring(arrayStart + 1, i);
        }
      }
    }
    return null;
  }

  private static String extractTopLevelObjectField(String jsonObject, String fieldName) {
    String needle = "\"" + fieldName + "\"";
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < jsonObject.length(); i++) {
      char ch = jsonObject.charAt(i);
      if (ch == '"' && (i == 0 || jsonObject.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        depth++;
      } else if (ch == '}') {
        depth--;
      }
      if (depth == 1 && jsonObject.startsWith(needle, i)) {
        int colon = jsonObject.indexOf(':', i + needle.length());
        if (colon < 0) {
          return null;
        }
        int objectStart = jsonObject.indexOf('{', colon + 1);
        if (objectStart < 0) {
          return null;
        }
        int objDepth = 0;
        boolean objInString = false;
        for (int j = objectStart; j < jsonObject.length(); j++) {
          char cj = jsonObject.charAt(j);
          if (cj == '"' && (j == 0 || jsonObject.charAt(j - 1) != '\\')) {
            objInString = !objInString;
          }
          if (objInString) {
            continue;
          }
          if (cj == '{') {
            objDepth++;
          } else if (cj == '}') {
            objDepth--;
            if (objDepth == 0) {
              return jsonObject.substring(objectStart, j + 1);
            }
          }
        }
        return null;
      }
    }
    return null;
  }

  private static Double parseTopLevelDoubleField(String jsonObject, String fieldName) {
    String needle = "\"" + fieldName + "\"";
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < jsonObject.length(); i++) {
      char ch = jsonObject.charAt(i);
      if (ch == '"' && (i == 0 || jsonObject.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        depth++;
      } else if (ch == '}') {
        depth--;
      }
      if (depth == 1 && jsonObject.startsWith(needle, i)) {
        int colon = jsonObject.indexOf(':', i + needle.length());
        if (colon < 0) {
          return null;
        }
        int start = colon + 1;
        while (start < jsonObject.length() && Character.isWhitespace(jsonObject.charAt(start))) {
          start++;
        }
        int end = start;
        while (end < jsonObject.length()) {
          char c = jsonObject.charAt(end);
          if ((c >= '0' && c <= '9') || c == '-' || c == '+'
              || c == '.' || c == 'e' || c == 'E') {
            end++;
          } else {
            break;
          }
        }
        if (end <= start) {
          return null;
        }
        try {
          return Double.parseDouble(jsonObject.substring(start, end));
        } catch (NumberFormatException ex) {
          return null;
        }
      }
    }
    return null;
  }

  private static List<RouteNode> parseOsrmRouteGeometryCoordinates(String routeObject) {
    int geometryObjectStart = routeObject.lastIndexOf("\"geometry\":{");
    if (geometryObjectStart < 0) {
      return parseOsrmCoordinates(routeObject);
    }
    int coordinatesStart = routeObject.indexOf("\"coordinates\":[[", geometryObjectStart);
    if (coordinatesStart < 0) {
      return parseOsrmCoordinates(routeObject);
    }
    int start = coordinatesStart + "\"coordinates\":[[".length();
    int end = routeObject.indexOf("]]", start);
    if (end < 0) {
      return parseOsrmCoordinates(routeObject);
    }
    String coords = routeObject.substring(start, end);
    String[] pairs = coords.split("\\],\\[");
    List<RouteNode> nodes = new ArrayList<>();
    for (String pair : pairs) {
      String[] parts = pair.split(",");
      if (parts.length < 2) {
        continue;
      }
      try {
        double lon = Double.parseDouble(parts[0].trim());
        double lat = Double.parseDouble(parts[1].trim());
        nodes.add(new RouteNode(lat, lon));
      } catch (NumberFormatException ignored) {
        // ignore malformed coordinate entry
      }
    }
    return nodes.size() >= 2 ? nodes : parseOsrmCoordinates(routeObject);
  }

  private static List<RouteNode> parsePrimaryRouteGeometryCoordinates(String osrmBody) {
    String routesArray = extractJsonArrayContent(osrmBody, "routes");
    List<String> routes = splitTopLevelObjects(routesArray);
    if (routes.isEmpty()) {
      return null;
    }
    return parseOsrmRouteGeometryCoordinates(routes.get(0));
  }

  private static Double parseLastDoubleField(String raw, Pattern pattern) {
    Matcher matcher = pattern.matcher(raw);
    Double value = null;
    while (matcher.find()) {
      try {
        value = Double.parseDouble(matcher.group(1));
      } catch (NumberFormatException ignored) {
        // continue scanning
      }
    }
    return value;
  }

  private static List<String> splitTopLevelObjects(String arrayBody) {
    List<String> out = new ArrayList<>();
    if (arrayBody == null || arrayBody.isBlank()) {
      return out;
    }
    int depth = 0;
    boolean inString = false;
    int objectStart = -1;
    for (int i = 0; i < arrayBody.length(); i++) {
      char ch = arrayBody.charAt(i);
      if (ch == '"' && (i == 0 || arrayBody.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        if (depth == 0) {
          objectStart = i;
        }
        depth++;
      } else if (ch == '}') {
        depth--;
        if (depth == 0 && objectStart >= 0) {
          out.add(arrayBody.substring(objectStart, i + 1));
          objectStart = -1;
        }
      }
    }
    return out;
  }

  private static List<RouteAlternative> parseOsrmAlternatives(String osrmBody) {
    String routesArray = extractJsonArrayContent(osrmBody, "routes");
    List<RouteAlternative> alternatives = new ArrayList<>();
    for (String routeObject : splitTopLevelObjects(routesArray)) {
      List<RouteNode> nodes = parseOsrmRouteGeometryCoordinates(routeObject);
      if (nodes == null || nodes.size() < 2) {
        continue;
      }
      Double dist = parseLastDoubleField(routeObject, OSRM_DISTANCE_PATTERN);
      Double duration = parseLastDoubleField(routeObject, OSRM_DURATION_PATTERN);
      double distanceMeters = dist != null ? dist : approximateRouteMeters(nodes);
      double durationSeconds = duration != null ? duration : 0.0;
      SpeedLimitEtaEstimate etaEstimate =
          estimateEtaFromMaxspeed(routeObject, distanceMeters, durationSeconds);
      alternatives.add(
          new RouteAlternative(
              nodes,
              distanceMeters,
              durationSeconds,
              etaEstimate.etaSeconds,
              etaEstimate.coverage,
              hasRouteClassHint(routeObject, OSRM_TOLL_CLASS_PATTERN),
              hasRouteClassHint(routeObject, OSRM_FERRY_CLASS_PATTERN)));
    }
    return alternatives;
  }

  private static boolean hasRouteClassHint(String routeObject, Pattern classPattern) {
    if (routeObject == null || routeObject.isBlank()) {
      return false;
    }
    return classPattern.matcher(routeObject).find();
  }

  private static Double parseOsrmDurationSeconds(String osrmBody) {
    Matcher matcher = OSRM_DURATION_PATTERN.matcher(osrmBody);
    if (matcher.find()) {
      try {
        return Double.parseDouble(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String routeAlternativesToJson(List<RouteAlternative> alternatives) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < alternatives.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      RouteAlternative alt = alternatives.get(i);
      sb.append("{")
          .append("\"index\":").append(i).append(",")
          .append("\"distance_m\":").append(trimDouble(alt.distanceMeters)).append(",")
          .append("\"duration_s\":").append(trimDouble(alt.durationSeconds)).append(",")
          .append("\"eta_speed_limit_s\":").append(trimDouble(alt.etaSpeedLimitSeconds)).append(",")
          .append("\"maxspeed_coverage\":").append(trimDouble(alt.speedLimitCoverage)).append(",")
          .append("\"has_toll_hint\":").append(alt.hasTollHint).append(",")
          .append("\"has_ferry_hint\":").append(alt.hasFerryHint).append(",")
          .append("\"route_points\":").append(routeNodesToJson(alt.nodes))
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }
  private static String alertClusterItemsToJson(List<AlertClusterItem> items) {
    if (items == null || items.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      AlertClusterItem item = items.get(i);
      sb.append("{")
          .append("\"ts\":\"").append(jsonEscape(item.ts)).append("\",")
          .append("\"alert\":\"").append(jsonEscape(item.alert)).append("\",")
          .append("\"transcript\":\"").append(jsonEscape(item.transcript)).append("\"")
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String fetchWazeHazardsJson(double originLat, double originLon, double destLat, double destLon) {
    if (WAZE_HAZARDS_API_URL.isBlank()) {
      return "{\"status\":\"unconfigured\",\"provider\":\"waze\",\"hazards\":[]}";
    }
    double minLat = Math.min(originLat, destLat);
    double maxLat = Math.max(originLat, destLat);
    double minLon = Math.min(originLon, destLon);
    double maxLon = Math.max(originLon, destLon);
    String url =
        WAZE_HAZARDS_API_URL
            + "?bbox="
            + trimDouble(minLon)
            + ","
            + trimDouble(minLat)
            + ","
            + trimDouble(maxLon)
            + ","
            + trimDouble(maxLat);
    Map<String, String> extraHeaders = new HashMap<>();
    if (!WAZE_HAZARDS_API_KEY.isBlank()) {
      String authHeader = WAZE_HAZARDS_API_AUTH_HEADER.isBlank() ? "Authorization" : WAZE_HAZARDS_API_AUTH_HEADER;
      String authPrefix = WAZE_HAZARDS_API_AUTH_PREFIX == null ? "" : WAZE_HAZARDS_API_AUTH_PREFIX;
      extraHeaders.put(authHeader, authPrefix + WAZE_HAZARDS_API_KEY);
    }
    String raw = httpGetExternal(url, extraHeaders);
    if (raw == null || !looksLikeJson(raw)) {
      return "{\"status\":\"unavailable\",\"provider\":\"waze\",\"hazards\":[]}";
    }
    return "{"
        + "\"status\":\"ok\","
        + "\"provider\":\"waze\","
        + "\"raw\":" + raw
        + "}";
  }

  private static String buildRouteOptionsJson(Map<String, String> query) {
    GpsPoint latest = latestGpsPoint;
    double originLat = parseDouble(query.getOrDefault("origin_lat", ""), Double.NaN);
    double originLon = parseDouble(query.getOrDefault("origin_lon", ""), Double.NaN);
    double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
    double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);
    if ((!Double.isFinite(originLat) || !Double.isFinite(originLon)) && latest != null) {
      originLat = latest.lat;
      originLon = latest.lon;
    }
    if ((!Double.isFinite(destLat) || !Double.isFinite(destLon)) && latest != null) {
      destLat = latest.lat;
      destLon = latest.lon;
    }
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)) {
      return "{\"error\":\"invalid_route_coordinates\"}";
    }
    List<RouteAlternative> alternatives = List.of();
    String osrmBody = fetchOsrmRouteAlternativesBody(originLat, originLon, destLat, destLon);
    if (osrmBody != null) {
      alternatives = parseOsrmAlternatives(osrmBody);
    }
    if (alternatives.size() < 2) {
      alternatives =
          augmentRouteAlternativesWithWaypointVariants(
              alternatives, originLat, originLon, destLat, destLon);
    }
    if (alternatives.isEmpty()) {
      RouteAlternative fallback = buildPrimaryRouteFallbackAlternative(originLat, originLon, destLat, destLon);
      if (fallback != null) {
        alternatives = List.of(fallback);
      }
    }
    if (alternatives.isEmpty()) {
      return "{\"error\":\"route_unavailable_no_roadway\"}";
    }
    String hazards = fetchWazeHazardsJson(originLat, originLon, destLat, destLon);
    Map<String, String> wazeRouteQuery = new HashMap<>();
    wazeRouteQuery.put("start", trimDouble(originLat) + "," + trimDouble(originLon));
    wazeRouteQuery.put("end", trimDouble(destLat) + "," + trimDouble(destLon));
    wazeRouteQuery.put("lat", trimDouble(destLat));
    wazeRouteQuery.put("lon", trimDouble(destLon));
    String wazeRoute = wazeRouteToJson(buildWazeRoute(wazeRouteQuery));
    Map<String, String> clusterQuery = new HashMap<>();
    // Route options typically span larger geographies than local incident maps;
    // use a coarser default grid so multi-state previews stay readable.
    clusterQuery.put("grid_deg", "1.00");
    String alertClusters = buildAlertClustersJson(clusterQuery);
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"origin\":{\"lat\":" + trimDouble(originLat) + ",\"lon\":" + trimDouble(originLon) + "},"
        + "\"destination\":{\"lat\":" + trimDouble(destLat) + ",\"lon\":" + trimDouble(destLon) + "},"
        + "\"alternatives\":" + routeAlternativesToJson(alternatives) + ","
        + "\"routes\":" + routeAlternativesToJson(alternatives) + ","
        + "\"waze_hazards\":" + hazards + ","
        + "\"waze_route\":" + wazeRoute + ","
        + "\"alert_clusters\":" + alertClusters
        + "}";
  }

  private static RouteAlternative buildPrimaryRouteFallbackAlternative(
      double originLat, double originLon, double destLat, double destLon) {
    String osrmBody = fetchOsrmRouteBody(originLat, originLon, destLat, destLon);
    if (osrmBody == null) {
      return null;
    }
    List<RouteNode> nodes = parsePrimaryRouteGeometryCoordinates(osrmBody);
    if (nodes == null || nodes.size() < 2) {
      return null;
    }
    Double dist = parseOsrmDistanceMeters(osrmBody);
    Double duration = parseOsrmDurationSeconds(osrmBody);
    double distanceMeters = dist != null ? dist : approximateRouteMeters(nodes);
    double durationSeconds = duration != null ? duration : 0.0;
    SpeedLimitEtaEstimate etaEstimate =
        estimateEtaFromMaxspeed(osrmBody, distanceMeters, durationSeconds);
    return new RouteAlternative(
        nodes,
        distanceMeters,
        durationSeconds,
        etaEstimate.etaSeconds,
        etaEstimate.coverage,
        hasRouteClassHint(osrmBody, OSRM_TOLL_CLASS_PATTERN),
        hasRouteClassHint(osrmBody, OSRM_FERRY_CLASS_PATTERN));
  }

  private static List<RouteAlternative> augmentRouteAlternativesWithWaypointVariants(
      List<RouteAlternative> existing,
      double originLat,
      double originLon,
      double destLat,
      double destLon) {
    List<RouteAlternative> out = new ArrayList<>(existing);
    double dLat = destLat - originLat;
    double dLon = destLon - originLon;
    double len = Math.hypot(dLat, dLon);
    if (!Double.isFinite(len) || len < 0.01) {
      return out;
    }
    double midLat = (originLat + destLat) / 2.0;
    double midLon = (originLon + destLon) / 2.0;
    double offset = Math.max(0.02, Math.min(0.10, len * 0.22));
    double perpLat = -dLon / len * offset;
    double perpLon = dLat / len * offset;
    double[] signs = new double[] {1.0, -1.0};
    for (double sign : signs) {
      double waypointLat = midLat + sign * perpLat;
      double waypointLon = midLon + sign * perpLon;
      if (Math.abs(waypointLat) > 90.0 || Math.abs(waypointLon) > 180.0) {
        continue;
      }
      String body =
          fetchOsrmRouteViaWaypointBody(
              originLat, originLon, waypointLat, waypointLon, destLat, destLon);
      if (body == null) {
        continue;
      }
      List<RouteAlternative> parsed = parseOsrmAlternatives(body);
      if (parsed.isEmpty()) {
        continue;
      }
      RouteAlternative candidate = parsed.get(0);
      if (isDistinctRouteAlternative(out, candidate)) {
        out.add(candidate);
      }
      if (out.size() >= 3) {
        break;
      }
    }
    return out;
  }

  private static boolean isDistinctRouteAlternative(
      List<RouteAlternative> existing, RouteAlternative candidate) {
    if (candidate == null || candidate.nodes == null || candidate.nodes.size() < 2) {
      return false;
    }
    for (RouteAlternative alt : existing) {
      if (alt == null || alt.nodes == null || alt.nodes.isEmpty()) {
        continue;
      }
      double distanceDelta = Math.abs(alt.distanceMeters - candidate.distanceMeters);
      if (distanceDelta < 2500.0) {
        RouteNode altMid = alt.nodes.get(alt.nodes.size() / 2);
        RouteNode candMid = candidate.nodes.get(candidate.nodes.size() / 2);
        double midDelta = haversineMeters(altMid.lat, altMid.lon, candMid.lat, candMid.lon);
        if (midDelta < 1200.0) {
          return false;
        }
      }
    }
    return true;
  }

  private static String buildAlertClustersJson(Map<String, String> query) {
    double gridDeg = parseDouble(query.getOrDefault("grid_deg", ""), 0.60);
    if (!Double.isFinite(gridDeg) || gridDeg <= 0.01) {
      gridDeg = 0.60;
    }
    if (gridDeg > 5.0) {
      gridDeg = 5.0;
    }
    Map<String, List<AlertClusterItem>> buckets = new HashMap<>();
    for (EventInfo event : readEventLines(RECENT_EVENT_LIMIT * 3)) {
      if (!"alert_triggered".equals(event.eventType)) {
        continue;
      }
      Matcher matcher = ALERT_COORD_PATTERN.matcher(event.rawJson);
      if (!matcher.find()) {
        continue;
      }
      double lat;
      double lon;
      try {
        lat = Double.parseDouble(matcher.group(1));
        lon = Double.parseDouble(matcher.group(2));
      } catch (NumberFormatException ex) {
        continue;
      }
      int latBucket = (int) Math.floor(lat / gridDeg);
      int lonBucket = (int) Math.floor(lon / gridDeg);
      String key = latBucket + ":" + lonBucket;
      List<AlertClusterItem> items = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
      String ts = extractStringField(event.rawJson, TS_PATTERN);
      String alert = extractStringField(event.rawJson, ALERT_PATTERN);
      String transcript = extractStringField(event.rawJson, TRANSCRIPT_PATTERN);
      items.add(new AlertClusterItem(ts, alert, transcript));
      if (items.size() > 5) {
        items.remove(0);
      }
    }
    StringBuilder clusters = new StringBuilder("[");
    int idx = 0;
    for (Map.Entry<String, List<AlertClusterItem>> e : buckets.entrySet()) {
      String[] parts = e.getKey().split(":");
      if (parts.length != 2) {
        continue;
      }
      int latBucket = parseIntOrDefault(parts[0], 0);
      int lonBucket = parseIntOrDefault(parts[1], 0);
      double centerLat = (latBucket + 0.5) * gridDeg;
      double centerLon = (lonBucket + 0.5) * gridDeg;
      if (idx++ > 0) {
        clusters.append(",");
      }
      clusters.append("{")
          .append("\"lat\":").append(trimDouble(centerLat)).append(",")
          .append("\"lon\":").append(trimDouble(centerLon)).append(",")
          .append("\"count\":").append(e.getValue().size()).append(",")
          .append("\"alerts\":").append(alertClusterItemsToJson(e.getValue()))
          .append("}");
    }
    clusters.append("]");
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"grid_deg\":" + trimDouble(gridDeg) + ","
        + "\"clusters\":" + clusters
        + "}";
  }

  private static String appendAlertClustersToSceneJson(String sceneJson, double radiusM) {
    if (sceneJson == null || sceneJson.isBlank()) {
      return sceneJson;
    }
    double gridDeg = Math.max(0.15, Math.min(2.5, radiusM / 220000.0));
    Map<String, String> query = new HashMap<>();
    query.put("grid_deg", trimDouble(gridDeg));
    String clustersPayload = buildAlertClustersJson(query);
    String clustersArray = extractJsonArrayContent(clustersPayload, "clusters");
    if (clustersArray == null) {
      clustersArray = "";
    }
    int insertAt = sceneJson.lastIndexOf('}');
    if (insertAt <= 0) {
      return sceneJson;
    }
    String prefix = sceneJson.substring(0, insertAt);
    String suffix = sceneJson.substring(insertAt);
    String separator = prefix.endsWith("{") ? "" : ",";
    return prefix + separator + "\"alert_clusters\":[" + clustersArray + "]" + suffix;
  }

  private static String buildWeatherJson(Map<String, String> query) {
    String start = query.getOrDefault("start", "");
    String end = query.getOrDefault("end", "");
    String provider = query.getOrDefault("provider", WEATHER_PROVIDER);
    String source;
    String notes;
    if ("mock".equalsIgnoreCase(provider)) {
      source = "mock";
      notes = "Mock weather forecast response.";
    } else {
      source = "mock_fallback";
      notes = "Provider '" + provider + "' is not wired yet; using mock fallback.";
    }

    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"start\":\"" + jsonEscape(start) + "\","
        + "\"end\":\"" + jsonEscape(end) + "\","
        + "\"provider\":\"" + jsonEscape(provider) + "\","
        + "\"source\":\"" + jsonEscape(source) + "\","
        + "\"notes\":\"" + jsonEscape(notes) + "\","
        + "\"forecast\":["
        + "{\"segment\":\"start\",\"time\":\"Now\",\"temp\":78,\"condition\":\"clear\"},"
        + "{\"segment\":\"segment-1\",\"time\":\"+20m\",\"temp\":79,\"condition\":\"partly_cloudy\"},"
        + "{\"segment\":\"segment-2\",\"time\":\"+40m\",\"temp\":80,\"condition\":\"windy\"},"
        + "{\"segment\":\"segment-3\",\"time\":\"+60m\",\"temp\":81,\"condition\":\"light_rain\"},"
        + "{\"segment\":\"destination\",\"time\":\"+80m\",\"temp\":79,\"condition\":\"cloudy\"}"
        + "]"
        + "}";
  }

  private static String providerStatusJson() {
    boolean hazardsConfigured = !WAZE_HAZARDS_API_URL.isBlank();
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"providers\":{"
        + "\"weather\":{"
        + "\"configured_provider\":\"" + jsonEscape(WEATHER_PROVIDER) + "\","
        + "\"ready\":" + ("mock".equalsIgnoreCase(WEATHER_PROVIDER)) + ","
        + "\"notes\":\"Set WEATHER_PROVIDER to desired provider and implement provider client in backend.\""
        + "},"
        + "\"waze\":{"
        + "\"deeplink_base_url\":\"" + jsonEscape(WAZE_DEEPLINK_BASE_URL) + "\","
        + "\"embed_base_url\":\"" + jsonEscape(WAZE_EMBED_BASE_URL) + "\","
        + "\"hazards_api_configured\":" + hazardsConfigured + ","
        + "\"ready\":true,"
        + "\"notes\":\"Waze route URLs are generated server-side for frontend consumption"
            + (hazardsConfigured ? "; hazards API configured." : "; hazards API URL not configured.") + "\""
        + "}"
        + "}"
        + "}";
  }

  private static String wazeRouteToJson(WazeRouteData route) {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"mode\":\"" + jsonEscape(route.mode) + "\","
        + "\"start\":\"" + jsonEscape(route.start) + "\","
        + "\"end\":\"" + jsonEscape(route.end) + "\","
        + "\"lat\":" + route.lat + ","
        + "\"lon\":" + route.lon + ","
        + "\"app_url\":\"" + jsonEscape(route.appUrl) + "\","
        + "\"embed_url\":\"" + jsonEscape(route.embedUrl) + "\""
        + "}";
  }
  private static final class BroadcastifyCatalogHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String catalogJson = runBroadcastifyCatalog(query);
      writeJson(exchange, helperResponseStatus(catalogJson), catalogJson);
    }
  }
  private static String runBroadcastifySelector(Map<String, String> query) {
    String lat = query.getOrDefault("lat", "");
    String lon = query.getOrDefault("lon", "");
    if (lat.isBlank() || lon.isBlank()) {
      // Route selection from the streaming device's GPS (posted via
      // /api/gps/update) instead of server-side defaults whenever a device
      // fix is available.
      GpsPoint deviceGps = latestGpsPoint;
      if (deviceGps != null) {
        lat = trimDouble(deviceGps.lat);
        lon = trimDouble(deviceGps.lon);
      }
    }
    boolean hasCoords = !lat.isBlank() && !lon.isBlank();
    String city = query.getOrDefault("city", "").trim();
    String county = query.getOrDefault("county", "").trim();
    String state = query.getOrDefault("state", "").trim();
    if (!hasCoords) {
      if (city.isBlank()) {
        city = BROADCASTIFY_SELECTOR_CITY;
      }
      if (county.isBlank()) {
        county = BROADCASTIFY_SELECTOR_COUNTY;
      }
    }
    if (state.isBlank() && hasCoords) {
      double latNum = parseDouble(lat, Double.NaN);
      double lonNum = parseDouble(lon, Double.NaN);
      if (Double.isFinite(latNum) && Double.isFinite(lonNum)) {
        String inferredState = MapModel.stateFor(latNum, lonNum);
        if (inferredState != null && !inferredState.isBlank() && !"XX".equalsIgnoreCase(inferredState)) {
          state = inferredState;
        }
      }
    }
    if (state.isBlank() && (BROADCASTIFY_SELECTOR_LOCK_STATE || !hasCoords)) {
      state = BROADCASTIFY_SELECTOR_STATE;
    }
    List<String> cmd = new ArrayList<>();
    cmd.add(SELECTOR_PYTHON_BIN);
    cmd.add(SELECTOR_SCRIPT_PATH);
    cmd.add("--channels-file");
    cmd.add(BROADCASTIFY_CHANNELS_FILE);
    if (!lat.isBlank()) {
      cmd.add("--lat");
      cmd.add(lat);
    }
    if (!lon.isBlank()) {
      cmd.add("--lon");
      cmd.add(lon);
    }
    if (!city.isBlank()) {
      cmd.add("--city");
      cmd.add(city);
    }
    if (!county.isBlank()) {
      cmd.add("--county");
      cmd.add(county);
    }
    if (!state.isBlank()) {
      cmd.add("--state");
      cmd.add(state);
    }
    cmd.add("--desired-types");
    cmd.add(BROADCASTIFY_SELECTOR_DESIRED_TYPES);
    cmd.add("--top-k");
    cmd.add(String.valueOf(BROADCASTIFY_SELECTOR_TOP_K));
    cmd.add("--print-top");
    cmd.add(String.valueOf(BROADCASTIFY_SELECTOR_PRINT_TOP));
    cmd.add("--output-format");
    cmd.add("json");
    if ("true".equalsIgnoreCase(BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK)) {
      cmd.add("--use-ollama-rerank");
      cmd.add("--ollama-model");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_MODEL);
      cmd.add("--ollama-url");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_URL);
      cmd.add("--ollama-timeout");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT);
      cmd.add("--ollama-weight");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT);
    } else {
      cmd.add("--no-use-ollama-rerank");
    }

    return runHelperCommand(cmd, "selector");
  }
  private static String runBroadcastifyCatalog(Map<String, String> query) {
    String region = query.getOrDefault("region", "").trim();
    List<String> cmd = new ArrayList<>();
    cmd.add(SELECTOR_PYTHON_BIN);
    cmd.add(BROADCASTIFY_CATALOG_SCRIPT_PATH);
    cmd.add("--manifest");
    cmd.add(BROADCASTIFY_CHANNELS_FILE);
    cmd.add("--output-format");
    cmd.add("json");
    if (!region.isBlank()) {
      cmd.add("--region");
      cmd.add(region);
    }

    return runHelperCommand(cmd, "catalog");
  }

  private static String runHelperCommand(List<String> cmd, String label) {
    return runHelperCommand(cmd, label, HELPER_PROCESS_TIMEOUT_SECONDS);
  }

  private static String runHelperCommand(List<String> cmd, String label, int timeoutSeconds) {
    Path outputPath = null;
    long started = System.nanoTime();
    boolean success = false;
    try {
      outputPath = Files.createTempFile("scanner-backend-" + label + "-", ".out");
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      pb.redirectOutput(outputPath.toFile());
      Process process = pb.start();
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        Files.deleteIfExists(outputPath);
        success = false;
        return "{"
            + "\"error\":\"" + jsonEscape(label) + "_timeout\","
            + "\"timeout_seconds\":" + timeoutSeconds
            + "}";
      }
      String output = Files.readString(outputPath, StandardCharsets.UTF_8).trim();
      int exitCode = process.exitValue();
      Files.deleteIfExists(outputPath);
      if (exitCode != 0) {
        success = false;
        return helperErrorJson(label + "_exit_nonzero", output, exitCode);
      }
      if (output.isBlank()) {
        success = false;
        return helperErrorJson(label + "_empty_output", "", null);
      }
      if (!looksLikeJson(output)) {
        success = false;
        return helperErrorJson(label + "_invalid_json_output", output, null);
      }
      success = true;
      return output;
    } catch (Exception ex) {
      if (outputPath != null) {
        try {
          Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
        }
      }
      return "{"
          + "\"error\":\"" + jsonEscape(label) + "_execution_failed\","
          + "\"details\":\"" + jsonEscape(ex.toString()) + "\""
          + "}";
    } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      HELPER_STATS.computeIfAbsent(label, k -> new TimingStats()).record(durationMs, success);
    }
  }

  private static String trimForJsonOutput(String raw, int maxChars) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.length() <= maxChars) {
      return trimmed;
    }
    return trimmed.substring(0, Math.max(0, maxChars)) + "\n...(truncated)";
  }

  private static String runStackManageCommand(String action) {
    if (!STACK_MANAGE_ALLOWED_ACTIONS.contains(action)) {
      return "{\"error\":\"invalid_action\"}";
    }
    Path outputPath = null;
    long started = System.nanoTime();
    boolean success = false;
    try {
      Path scriptPath = Path.of(STACK_MANAGE_SCRIPT_PATH);
      if (!Files.exists(scriptPath)) {
        return "{\"error\":\"stack_script_not_found\"}";
      }
      if (!Files.isExecutable(scriptPath)) {
        return "{\"error\":\"stack_script_not_executable\"}";
      }
      outputPath = Files.createTempFile("stack-manage-", ".out");
      ProcessBuilder pb = new ProcessBuilder(scriptPath.toString(), action);
      pb.redirectErrorStream(true);
      pb.redirectOutput(outputPath.toFile());
      Process process = pb.start();
      boolean finished = process.waitFor(STACK_MANAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        Files.deleteIfExists(outputPath);
        return "{"
            + "\"error\":\"stack_manage_timeout\","
            + "\"timeout_seconds\":" + STACK_MANAGE_TIMEOUT_SECONDS
            + "}";
      }
      int exitCode = process.exitValue();
      String output = Files.readString(outputPath, StandardCharsets.UTF_8);
      Files.deleteIfExists(outputPath);
      success = exitCode == 0;
      return "{"
          + "\"status\":\"" + (success ? "ok" : "error") + "\","
          + "\"action\":\"" + jsonEscape(action) + "\","
          + "\"exit_code\":" + exitCode + ","
          + "\"output\":\"" + jsonEscape(trimForJsonOutput(output, STACK_MANAGE_OUTPUT_MAX_CHARS)) + "\""
          + "}";
    } catch (Exception ex) {
      if (outputPath != null) {
        try {
          Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
        }
      }
      return "{"
          + "\"error\":\"stack_manage_execution_failed\","
          + "\"details\":\"" + jsonEscape(ex.toString()) + "\""
          + "}";
    } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      HELPER_STATS.computeIfAbsent("stack_manage", k -> new TimingStats()).record(durationMs, success);
    }
  }

  private static String buildStackManageJson(Map<String, String> query, String body) {
    String action =
        sanitizeErrorReportField(
                extractStringFieldByName(body, "action", query.getOrDefault("action", "")),
                24)
            .toLowerCase(Locale.ROOT);
    if (action.isBlank()) {
      return "{\"error\":\"missing_action\"}";
    }
    return runStackManageCommand(action);
  }

  private static void streamEventsFromLog(OutputStream os, boolean mobileCompact, String clientId) throws IOException {
    long offset = Files.exists(LOG_PATH) ? Files.size(LOG_PATH) : 0L;
    while (true) {
      if (clientId != null && !clientId.isBlank()) {
        flushClientMailboxToStream(os, clientId);
      }
      if (!Files.exists(LOG_PATH)) {
        sleepQuietly(STREAM_POLL_MILLIS);
        continue;
      }
      try (RandomAccessFile raf = new RandomAccessFile(LOG_PATH.toFile(), "r")) {
        long size = raf.length();
        if (size < offset) {
          offset = 0L;
        }
        if (size == offset) {
          sleepQuietly(STREAM_POLL_MILLIS);
          continue;
        }
        raf.seek(offset);
        String line;
        while ((line = raf.readLine()) != null) {
          String decodedLine = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
          for (String payload : extractEventPayloads(decodedLine)) {
            String eventJson = payload;
            if (mobileCompact) {
              String eventType = extractStringField(payload, EVENT_TYPE_PATTERN);
              String kind = extractStringField(payload, KIND_PATTERN);
              eventJson = compactMobileEventJson(new EventInfo(payload, eventType, kind));
            }
            os.write(("data: " + eventJson + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        }
        offset = raf.getFilePointer();
      }
      if (clientId != null && !clientId.isBlank()) {
        flushClientMailboxToStream(os, clientId);
      }
    }
  }

  private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    applyCorsHeaders(headers, exchange);
    exchange.sendResponseHeaders(statusCode, payload.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(payload);
    }
  }

  private static void writeHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/html; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    exchange.sendResponseHeaders(statusCode, payload.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(payload);
    }
  }

  private static void writeBinary(HttpExchange exchange, int statusCode, byte[] payload, String contentType)
      throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    applyCorsHeaders(headers, exchange);
    exchange.sendResponseHeaders(statusCode, payload.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(payload);
    }
  }

  private static void writeTextEventStreamHeaders(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
    headers.set("Connection", "keep-alive");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    applyCorsHeaders(headers, exchange);
    exchange.sendResponseHeaders(200, 0);
  }

  private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
    if (!"OPTIONS".equals(exchange.getRequestMethod())) {
      return false;
    }
    Headers headers = exchange.getResponseHeaders();
    applyCorsHeaders(headers, exchange);
    headers.set("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, DELETE, OPTIONS");
    headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
    headers.set("Access-Control-Max-Age", "600");
    exchange.sendResponseHeaders(204, -1);
    return true;
  }

  private static void applyCorsHeaders(Headers headers, HttpExchange exchange) {
    headers.set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
  }

  private static boolean isGet(HttpExchange exchange) {
    return "GET".equals(exchange.getRequestMethod());
  }

  private static final class HealthHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      String body =
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"bind_host\":\"" + jsonEscape(HOST) + "\","
              + "\"bind_port\":" + PORT + ","
              + "\"log_exists\":" + Files.exists(LOG_PATH) + ","
              + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\","
              + "\"weather_provider\":\"" + jsonEscape(WEATHER_PROVIDER) + "\","
              + "\"metrics\":{"
              + "\"request_timing\":" + timingStatsToJson(REQUEST_STATS) + ","
              + "\"helper_timing\":" + timingStatsToJson(HELPER_STATS)
              + "}"
              + "}";
      writeJson(exchange, 200, body);
    }
  }

  private static final class SnapshotHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      SnapshotData data = buildSnapshotData();
      writeJson(exchange, 200, snapshotToJson(data));
    }
  }

  private static final class WeatherHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      writeJson(exchange, 200, buildWeatherJson(query));
    }
  }

  private static final class PlatformWeatherHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      writeJson(exchange, 200, buildWeatherJson(query));
    }
  }

  private static final class WazeRouteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      WazeRouteData route = buildWazeRoute(query);
      writeJson(exchange, 200, wazeRouteToJson(route));
    }
  }
  private static final class LocalRouteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String routeJson = buildStandaloneLocalRouteJson(query);
      writeJson(exchange, helperResponseStatus(routeJson), routeJson);
    }
  }

  private static final class RouteOptionsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildRouteOptionsJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class ShareEtaCreateHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String payload = buildShareEtaCreateJson(query, body, exchange);
      writeJson(exchange, shareEtaCreateResponseStatus(payload), payload);
    }
  }

  private static final class ShareEtaPublicHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String token = query.getOrDefault("token", "").trim();
      ShareEtaTokenData tokenData = decodeShareEtaToken(token);
      if (tokenData == null) {
        writeJson(exchange, 403, "{\"error\":\"invalid_or_expired_share_token\"}");
        return;
      }
      String format = query.getOrDefault("format", "html").trim().toLowerCase(Locale.ROOT);
      String payload = buildShareEtaSnapshotJson(tokenData);
      if ("json".equals(format)) {
        writeJson(exchange, 200, payload);
        return;
      }
      writeHtml(exchange, 200, buildShareEtaHtml(payload));
    }
  }

  private static final class AddressCatalogResolveHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildAddressCatalogResolveJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogSuggestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildAddressCatalogSuggestJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogUpsertHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String payload = buildAddressCatalogUpsertJson(query, body);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogExportHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, buildAddressCatalogExportJson());
    }
  }

  private static final class ErrorReportSubmitHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", ""));
      boolean analyticsOptOut = extractAnalyticsOptOut(exchange, query, body, clientId);
      String payload = buildErrorReportSubmitJson(query, body, analyticsOptOut);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class ErrorReportRecentHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildErrorReportRecentJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AlertClustersHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildAlertClustersJson(query);
      writeJson(exchange, 200, payload);
    }
  }

  private static boolean looksRecursiveAssistantQuery(String userQuery, String transcript) {
    String text = ((userQuery == null ? "" : userQuery) + " " + (transcript == null ? "" : transcript))
        .toLowerCase(Locale.ROOT);
    if (text.isBlank()) {
      return false;
    }
    return text.contains("ask scout to ask scout")
        || text.contains("ask yourself")
        || text.contains("query yourself")
        || text.contains("/api/platform/assistant/chat")
        || text.contains("assistant chat endpoint");
  }

  private static final class GeocodeHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String q = query.getOrDefault("q", "").trim();
      if (q.isEmpty()) {
        writeJson(exchange, 400, "{\"error\":\"missing_query\"}");
        return;
      }
      // Optional caller position (lat/lon) biases results: first try a bounded
      // viewbox search near the caller, then fall back to a global search.
      double biasLat = parseDouble(query.get("lat"), Double.NaN);
      double biasLon = parseDouble(query.get("lon"), Double.NaN);
      boolean hasBias =
          Double.isFinite(biasLat)
              && Double.isFinite(biasLon)
              && Math.abs(biasLat) <= 90.0
              && Math.abs(biasLon) <= 180.0;
      String baseUrl = NOMINATIM_SEARCH_URL + "?format=json&limit=5&q=" + urlEncode(q);
      String body = null;
      boolean bounded = false;
      if (hasBias) {
        // Nominatim viewbox is lon,lat pairs: lonMin,latMin,lonMax,latMax.
        String viewbox =
            trimDouble(biasLon - GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLat - GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLon + GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLat + GEOCODE_BIAS_RADIUS_DEGREES);
        body = httpGetExternal(baseUrl + "&viewbox=" + viewbox + "&bounded=1");
        bounded = body != null && looksLikeJson(body) && !"[]".equals(body.trim());
      }
      if (!bounded) {
        body = httpGetExternal(baseUrl);
      }
      if (body == null || !looksLikeJson(body)) {
        writeJson(
            exchange,
            502,
            "{\"status\":\"error\",\"error\":\"geocode_unavailable\",\"provider\":\"nominatim\"}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"status\":\"ok\","
              + "\"provider\":\"nominatim\","
              + "\"query\":\"" + jsonEscape(q) + "\","
              + "\"bounded\":" + bounded + ","
              + "\"results\":" + body
              + "}");
    }
  }

  private static final class LlmStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, llmStatusJson());
    }
  }

  private static String llmStatusJson() {
    String cachedStatus = getCachedValue(llmStatusCache);
    if (cachedStatus != null) {
      return cachedStatus;
    }
    String body = httpGetExternal(OLLAMA_TAGS_URL);
    boolean ollamaUp = body != null && looksLikeJson(body);
    java.util.Set<String> installed = new java.util.HashSet<>();
    if (ollamaUp) {
      Matcher matcher = MODEL_NAME_PATTERN.matcher(body);
      while (matcher.find()) {
        String name = matcher.group(1);
        installed.add(name);
        if (name.endsWith(":latest")) {
          installed.add(name.substring(0, name.length() - ":latest".length()));
        }
      }
    }
    StringBuilder models = new StringBuilder("{");
    boolean complete = true;
    for (int i = 0; i < SCOUT_MODELS.length; i++) {
      boolean present = installed.contains(SCOUT_MODELS[i]);
      complete = complete && present;
      if (i > 0) {
        models.append(",");
      }
      models.append("\"").append(SCOUT_MODELS[i]).append("\":").append(present);
    }
    boolean corePresent = installed.contains("scout-core1.0.8");
    boolean vetPresent = installed.contains("scout-vet1.0.8");
    boolean rankPresent = installed.contains("scout-rank");
    models.append(",\"scout-alert\":").append(vetPresent);
    models.append(",\"scout-intel\":").append(vetPresent);
    models.append(",\"scout-vet\":").append(vetPresent);
    models.append(",\"scout-nav\":").append(corePresent);
    models.append(",\"scout-chat\":").append(corePresent);
    models.append(",\"scout-rank\":").append(rankPresent);
    models.append("}");
    String payload = "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"ollama_up\":" + ollamaUp + ","
        + "\"tags_url\":\"" + jsonEscape(OLLAMA_TAGS_URL) + "\","
        + "\"base_model\":\"" + jsonEscape(LLM_BASE_MODEL) + "\","
        + "\"base_model_installed\":" + installed.contains(LLM_BASE_MODEL) + ","
        + "\"models\":" + models + ","
        + "\"complete\":" + (ollamaUp && complete)
        + "}";
    llmStatusCache = new TimedStringValue(payload, System.currentTimeMillis() + LLM_STATUS_CACHE_TTL_MS);
    return payload;
  }

  private static String httpGetExternal(String urlString) {
    return httpGetExternal(urlString, null);
  }

  private static String httpGetExternal(String urlString, Map<String, String> extraHeaders) {
    java.net.HttpURLConnection connection = null;
    try {
      connection = (java.net.HttpURLConnection) new java.net.URL(urlString).openConnection();
      connection.setConnectTimeout(EXTERNAL_HTTP_TIMEOUT_MS);
      connection.setReadTimeout(EXTERNAL_HTTP_TIMEOUT_MS);
      connection.setRequestProperty("User-Agent", EXTERNAL_HTTP_USER_AGENT);
      connection.setRequestProperty("Accept", "application/json");
      if (extraHeaders != null) {
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
          if (header.getKey() == null || header.getKey().isBlank() || header.getValue() == null || header.getValue().isBlank()) {
            continue;
          }
          connection.setRequestProperty(header.getKey(), header.getValue());
        }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return null;
      }
      try (java.io.InputStream in = connection.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (Exception ex) {
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static String fetchOsrmRouteBody(
      double originLat, double originLon, double destLat, double destLon) {
    String cacheKey = osrmCacheKey(originLat, originLon, destLat, destLon);
    String cached = getCachedString(OSRM_ROUTE_CACHE, cacheKey);
    if (cached != null) {
      return cached;
    }
    String url =
        OSRM_ROUTE_BASE_URL
            + "/"
            + trimDouble(originLon)
            + ","
            + trimDouble(originLat)
            + ";"
            + trimDouble(destLon)
            + ","
            + trimDouble(destLat)
            + "?overview=full&geometries=geojson&alternatives=false&steps=false";
    String body = httpGetExternal(url);
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      return null;
    }
    putCachedString(OSRM_ROUTE_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    return body;
  }

  private static List<RouteNode> parseOsrmCoordinates(String osrmBody) {
    int keyIdx = osrmBody.indexOf("\"coordinates\":[[");
    if (keyIdx < 0) {
      return null;
    }
    int start = keyIdx + "\"coordinates\":[[".length();
    int end = osrmBody.indexOf("]]", start);
    if (end < 0) {
      return null;
    }
    String coords = osrmBody.substring(start, end);
    String[] pairs = coords.split("\\],\\[");
    List<RouteNode> nodes = new ArrayList<>();
    for (String pair : pairs) {
      String[] parts = pair.split(",");
      if (parts.length < 2) {
        continue;
      }
      try {
        double lon = Double.parseDouble(parts[0].trim());
        double lat = Double.parseDouble(parts[1].trim());
        nodes.add(new RouteNode(lat, lon));
      } catch (NumberFormatException ignored) {
        // skip malformed coordinate pair
      }
    }
    return nodes.size() >= 2 ? nodes : null;
  }

  private static Double parseOsrmDistanceMeters(String osrmBody) {
    Matcher matcher = OSRM_DISTANCE_PATTERN.matcher(osrmBody);
    if (matcher.find()) {
      try {
        return Double.parseDouble(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
  private static boolean looksLikeJson(String raw) {
    if (raw == null) {
      return false;
    }
    String trimmed = raw.trim();
    return (trimmed.startsWith("{") && trimmed.endsWith("}"))
        || (trimmed.startsWith("[") && trimmed.endsWith("]"));
  }

  private static String helperErrorJson(String errorCode, String details, Integer exitCode) {
    StringBuilder sb = new StringBuilder("{")
        .append("\"error\":\"").append(jsonEscape(errorCode)).append("\"");
    if (exitCode != null) {
      sb.append(",\"exit_code\":").append(exitCode.intValue());
    }
    if (details != null && !details.isBlank()) {
      sb.append(",\"details\":\"").append(jsonEscape(details)).append("\"");
    }
    sb.append("}");
    return sb.toString();
  }

  private static int helperResponseStatus(String payload) {
    if (payload == null || payload.isBlank()) {
      return 502;
    }
    String trimmed = payload.trim();
    if (trimmed.startsWith("{\"error\"") || trimmed.startsWith("{ \"error\"")) {
      return 502;
    }
    return 200;
  }

  private static int shareEtaCreateResponseStatus(String payload) {
    if (payload == null || payload.isBlank()) {
      return 502;
    }
    String trimmed = payload.trim();
    if (!trimmed.startsWith("{\"error\"") && !trimmed.startsWith("{ \"error\"")) {
      return 200;
    }
    if (trimmed.contains("\"missing_share_route_coordinates\"")) {
      return 400;
    }
    if (trimmed.contains("\"share_public_https_required\"")) {
      return 400;
    }
    return 502;
  }

  private static void pruneStaleClientRoutes() {
    long now = System.currentTimeMillis();
    synchronized (CLIENT_ROUTE_LOCK) {
      for (Map.Entry<String, ClientRoute> entry : CLIENT_ROUTES.entrySet()) {
        if (now - entry.getValue().lastSeenMs > CLIENT_ROUTE_TTL_MS) {
          CLIENT_ROUTES.remove(entry.getKey());
        }
      }
    }
  }

  private static String remoteAddressFromExchange(HttpExchange exchange) {
    try {
      if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
        return "";
      }
      return exchange.getRemoteAddress().getAddress().getHostAddress();
    } catch (Exception ex) {
      return "";
    }
  }

  private static ClientRoute upsertClientRoute(
      String clientId,
      String userId,
      String source,
      String sessionId,
      String remoteAddr,
      boolean rotatePullToken,
      Boolean analyticsOptOutOverride) {
    long now = System.currentTimeMillis();
    ClientRoute existing = CLIENT_ROUTES.get(clientId);
    boolean analyticsOptOut =
        analyticsOptOutOverride != null
            ? analyticsOptOutOverride.booleanValue()
            : (existing != null && existing.analyticsOptOut);
    String pullToken =
        rotatePullToken || existing == null || existing.pullToken == null || existing.pullToken.isBlank()
            ? UUID.randomUUID().toString().replace("-", "")
            : existing.pullToken;
    long tokenIssuedAtMs =
        rotatePullToken || existing == null || existing.pullTokenIssuedAtMs <= 0
            ? now
            : existing.pullTokenIssuedAtMs;
    ClientRoute route =
        new ClientRoute(
            clientId,
            userId == null || userId.isBlank() ? "unknown_user" : userId,
            source == null || source.isBlank() ? "unknown_source" : source,
            sessionId == null ? "" : sessionId,
            remoteAddr == null ? "" : remoteAddr,
            analyticsOptOut,
            pullToken,
            tokenIssuedAtMs,
            now);
    CLIENT_ROUTES.put(clientId, route);
    return route;
  }

  private static String extractClientPullToken(HttpExchange exchange, Map<String, String> query) {
    String token = query.getOrDefault("pull_token", "").trim();
    if (!token.isBlank()) {
      return token;
    }
    String headerToken = exchange.getRequestHeaders().getFirst(CLIENT_PULL_TOKEN_HEADER);
    return headerToken == null ? "" : headerToken.trim();
  }

  private static boolean parseFlexibleBoolean(String raw, boolean fallback) {
    if (raw == null) {
      return fallback;
    }
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty()) {
      return fallback;
    }
    if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "on".equals(value)) {
      return true;
    }
    if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "off".equals(value)) {
      return false;
    }
    return fallback;
  }

  private static boolean extractAnalyticsOptOut(
      HttpExchange exchange, Map<String, String> query, String body, String clientId) {
    boolean fromHeader =
        parseFlexibleBoolean(exchange.getRequestHeaders().getFirst(ANALYTICS_OPT_OUT_HEADER), false);
    boolean fromQuery = parseFlexibleBoolean(query.getOrDefault("analytics_opt_out", ""), fromHeader);
    boolean fromBody = extractBooleanField(body, "analytics_opt_out", fromQuery);
    if (clientId != null && !clientId.isBlank()) {
      ClientRoute route = CLIENT_ROUTES.get(clientId);
      if (route != null && route.analyticsOptOut) {
        return true;
      }
    }
    return fromBody;
  }

  private static boolean isAuthorizedClientPull(String clientId, String pullToken) {
    if (clientId == null || clientId.isBlank() || pullToken == null || pullToken.isBlank()) {
      return false;
    }
    ClientRoute route = CLIENT_ROUTES.get(clientId);
    if (route == null || route.pullToken == null || route.pullToken.isBlank()) {
      return false;
    }
    return route.pullToken.equals(pullToken);
  }

  private static void enqueueClientMessage(String clientId, String payloadJson) {
    Deque<ClientMessage> mailbox = CLIENT_MAILBOX.computeIfAbsent(clientId, key -> new ArrayDeque<>());
    synchronized (mailbox) {
      mailbox.addLast(new ClientMessage(payloadJson, System.currentTimeMillis()));
      while (mailbox.size() > CLIENT_MAILBOX_MAX_MESSAGES) {
        mailbox.removeFirst();
      }
    }
  }

  private static List<ClientMessage> drainClientMailbox(String clientId, int limit) {
    Deque<ClientMessage> mailbox = CLIENT_MAILBOX.get(clientId);
    if (mailbox == null || limit <= 0) {
      return List.of();
    }
    List<ClientMessage> out = new ArrayList<>();
    synchronized (mailbox) {
      while (out.size() < limit && !mailbox.isEmpty()) {
        out.add(mailbox.removeFirst());
      }
    }
    return out;
  }

  private static String clientMessagesToJson(List<ClientMessage> messages) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(messages.get(i).payloadJson);
    }
    sb.append("]");
    return sb.toString();
  }

  private static String clientRouteToJson(ClientRoute route) {
    return "{"
        + "\"client_id\":\"" + jsonEscape(route.clientId) + "\","
        + "\"user_id\":\"" + jsonEscape(route.userId) + "\","
        + "\"source\":\"" + jsonEscape(route.source) + "\","
        + "\"session_id\":\"" + jsonEscape(route.sessionId) + "\","
        + "\"remote_addr\":\"" + jsonEscape(route.remoteAddr) + "\","
        + "\"analytics_opt_out\":" + (route.analyticsOptOut ? "true" : "false") + ","
        + "\"last_seen_ms\":" + route.lastSeenMs
        + "}";
  }

  private static String allClientRoutesJson() {
    pruneStaleClientRoutes();
    List<ClientRoute> routes = new ArrayList<>(CLIENT_ROUTES.values());
    routes.sort(Comparator.comparing(route -> route.clientId));
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < routes.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(clientRouteToJson(routes.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static String relayMessagePayloadJson(String from, String kind, String message) {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"event_type\":\"server_relay\","
        + "\"from\":\"" + jsonEscape(from == null ? "server" : from) + "\","
        + "\"kind\":\"" + jsonEscape(kind == null || kind.isBlank() ? "notice" : kind) + "\","
        + "\"message\":\"" + jsonEscape(message == null ? "" : message) + "\""
        + "}";
  }

  private static void flushClientMailboxToStream(OutputStream os, String clientId) throws IOException {
    if (clientId == null || clientId.isBlank()) {
      return;
    }
    List<ClientMessage> messages = drainClientMailbox(clientId, CLIENT_PULL_MAX_LIMIT);
    for (ClientMessage msg : messages) {
      os.write(("data: " + msg.payloadJson + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
  }
  private static final class BroadcastifySelectHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String selectorJson = runBroadcastifySelector(query);
      writeJson(exchange, helperResponseStatus(selectorJson), selectorJson);
    }
  }

  private static final class ProviderStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, providerStatusJson());
    }
  }

  private static final class AssistantChatHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String userQuery = extractStringFieldByName(body, "query", query.getOrDefault("query", "")).trim();
      String transcript = extractStringFieldByName(body, "transcript", query.getOrDefault("transcript", "")).trim();
      if (userQuery.isBlank() && transcript.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_query\"}");
        return;
      }
      if (userQuery.isBlank()) {
        userQuery = transcript;
      }
      if (looksRecursiveAssistantQuery(userQuery, transcript)) {
        String safePayload =
            "{"
                + "\"ts\":\"" + Instant.now().toString() + "\","
                + "\"status\":\"ok\","
                + "\"query\":\"" + jsonEscape(userQuery) + "\","
                + "\"route_context\":\"recursive_intent_blocked\","
                + "\"llm_result\":{\"response\":\"Recursive self-query blocked. Ask for weather, route, hazards, or broadcastify context directly.\",\"confidence\":\"high\",\"missing_data\":[],\"evidence\":[\"recursion_guard\"]}"
                + "}";
        writeJson(exchange, 200, safePayload);
        return;
      }
      String routeContext = buildAssistantRouteContext(userQuery, transcript, query, body);
      String llmPayload = runScoutAssistantChat(routeContext);
      int llmStatus = helperResponseStatus(llmPayload);
      if (llmStatus != 200) {
        writeJson(exchange, llmStatus, llmPayload);
        return;
      }
      String payload =
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"status\":\"ok\","
              + "\"query\":\"" + jsonEscape(userQuery) + "\","
              + "\"route_context\":"
              + (ASSISTANT_INCLUDE_ROUTE_CONTEXT
                  ? ("\"" + jsonEscape(routeContext) + "\"")
                  : "\"suppressed\"")
              + ","
              + "\"llm_result\":" + llmPayload
              + "}";
      writeJson(exchange, 200, payload);
    }
  }

  private static final class DevStackManageHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String payload = buildStackManageJson(query, body);
      int status = 200;
      if (payload.startsWith("{\"error\"")) {
        if (payload.contains("\"missing_action\"") || payload.contains("\"invalid_action\"")) {
          status = 400;
        } else {
          status = 502;
        }
      }
      writeJson(exchange, status, payload);
    }
  }

  private static final class MobileBootstrapHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, mobileBootstrapJson());
    }
  }

  private static final class MobileSnapshotHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, mobileSnapshotJson());
    }
  }

  private static final class MobileClientRegisterHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", "")).trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      String userId = extractStringFieldByName(body, "user_id", query.getOrDefault("user_id", ""));
      String source = extractStringFieldByName(body, "source", query.getOrDefault("source", "mobile_client"));
      String sessionId = extractStringFieldByName(body, "session_id", query.getOrDefault("session_id", ""));
      boolean analyticsOptOut = extractAnalyticsOptOut(exchange, query, body, clientId);
      ClientRoute route =
          upsertClientRoute(
              clientId,
              userId,
              source,
              sessionId,
              remoteAddressFromExchange(exchange),
              true,
              analyticsOptOut);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"route\":" + clientRouteToJson(route) + ","
              + "\"analytics_opt_out\":" + (route.analyticsOptOut ? "true" : "false") + ","
              + "\"pull_token\":\"" + jsonEscape(route.pullToken) + "\","
              + "\"pull_endpoint\":\"/api/mobile/client/pull?client_id="
              + urlEncode(clientId)
              + "&pull_token="
              + urlEncode(route.pullToken)
              + "\""
              + "}");
    }
  }

  private static final class MobileClientSendHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", "")).trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      String from = extractStringFieldByName(body, "from", query.getOrDefault("from", "server"));
      String kind = extractStringFieldByName(body, "kind", query.getOrDefault("kind", "notice"));
      String message = extractStringFieldByName(body, "message", query.getOrDefault("message", ""));
      if (message.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_message\"}");
        return;
      }
      String payload = relayMessagePayloadJson(from, kind, message);
      enqueueClientMessage(clientId, payload);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"queued\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"client_id\":\"" + jsonEscape(clientId) + "\""
              + "}");
    }
  }

  private static final class MobileClientPullHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      String pullToken = extractClientPullToken(exchange, query);
      if (!isAuthorizedClientPull(clientId, pullToken)) {
        logRequestRejection(
            "/api/mobile/client/pull", exchange, "invalid_client_pull_token", 403, "handler_token_check");
        writeJson(exchange, 403, "{\"error\":\"invalid_client_pull_token\"}");
        return;
      }
      int limit = parseIntOrDefault(query.getOrDefault("limit", String.valueOf(CLIENT_PULL_DEFAULT_LIMIT)), CLIENT_PULL_DEFAULT_LIMIT);
      if (limit < 1) {
        limit = 1;
      }
      if (limit > CLIENT_PULL_MAX_LIMIT) {
        limit = CLIENT_PULL_MAX_LIMIT;
      }
      List<ClientMessage> messages = drainClientMailbox(clientId, limit);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"client_id\":\"" + jsonEscape(clientId) + "\","
              + "\"count\":" + messages.size() + ","
              + "\"messages\":" + clientMessagesToJson(messages)
              + "}");
    }
  }

  private static final class MobileClientsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"ttl_ms\":" + CLIENT_ROUTE_TTL_MS + ","
              + "\"clients\":" + allClientRoutesJson()
              + "}");
    }
  }

  private static final class GpsUpdateHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", ""));
      if (extractAnalyticsOptOut(exchange, query, body, clientId)) {
        writeJson(
            exchange,
            200,
            "{"
                + "\"status\":\"skipped\","
                + "\"reason\":\"analytics_opt_out\","
                + "\"received_at\":\"" + Instant.now().toString() + "\""
                + "}");
        return;
      }
      GpsPoint point = gpsPointFromInputs(query, body);
      if (point == null) {
        writeJson(exchange, 400, "{\"error\":\"invalid_gps_payload\"}");
        return;
      }
      appendGpsPoint(point);
      List<GpsPoint> recent = copyRecentTrack(40);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"received_at\":\"" + Instant.now().toString() + "\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"point\":" + gpsPointToJson(point) + ","
              + "\"track\":" + gpsTrackToJson(recent)
              + "}");
    }
  }

  private static final class MapSceneHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
      double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        GpsPoint latest = latestGpsPoint;
        if (latest == null) {
          writeJson(exchange, 400, "{\"error\":\"missing_coordinates\"}");
          return;
        }
        lat = latest.lat;
        lon = latest.lon;
      }
      double radiusM = parseDouble(query.getOrDefault("radius_m", ""), 700.0);
      int zoom = parseIntOrDefault(query.getOrDefault("zoom", ""), 0); // 0 = auto (resolution filter)
      String sceneJson = ProprietaryMapEngine.sceneJson(lat, lon, radiusM, zoom);
      writeJson(exchange, 200, appendAlertClustersToSceneJson(sceneJson, radiusM));
    }
  }

  private static final class MapRenderHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
      double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        GpsPoint latest = latestGpsPoint;
        if (latest == null) {
          writeJson(exchange, 400, "{\"error\":\"missing_coordinates\"}");
          return;
        }
        lat = latest.lat;
        lon = latest.lon;
      }
      double mpp = parseDouble(query.getOrDefault("mpp", ""), 35.0);
      double heading = parseDouble(query.getOrDefault("heading", ""), 0.0);
      double tilt = parseDouble(query.getOrDefault("tilt", ""), 45.0);
      int w = parseIntOrDefault(query.getOrDefault("w", ""), 720);
      int h = parseIntOrDefault(query.getOrDefault("h", ""), 1280);
      double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
      double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);
      double[] routePts = null;
      Double destLatBox = null;
      Double destLonBox = null;
      if (Double.isFinite(destLat) && Double.isFinite(destLon)) {
        destLatBox = destLat;
        destLonBox = destLon;
        String osrmBody = fetchOsrmRouteBody(lat, lon, destLat, destLon);
        if (osrmBody != null) {
          List<RouteNode> nodes = parseOsrmCoordinates(osrmBody);
          if (nodes != null) {
            routePts = new double[nodes.size() * 2];
            for (int i = 0; i < nodes.size(); i++) {
              routePts[i * 2] = nodes.get(i).lat;
              routePts[i * 2 + 1] = nodes.get(i).lon;
            }
          }
        }
      }
      byte[] png = ProprietaryMapEngine.renderPng(lat, lon, mpp, heading, tilt, w, h, routePts, destLatBox, destLonBox);
      writeBinary(exchange, 200, png, "image/png");
    }
  }

  private static final class MapStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, ProprietaryMapEngine.statusJson());
    }
  }

  private static final class MapShardHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      if (parseFlexibleBoolean(query.getOrDefault("status", ""), false)) {
        writeJson(
            exchange,
            200,
            "{"
                + "\"status\":\"ok\","
                + "\"ts\":\"" + Instant.now().toString() + "\","
                + "\"prefetch\":" + ProprietaryMapEngine.prefetchStatusJson()
                + "}");
        return;
      }
      String state = query.getOrDefault("state", "").trim();
      int maxTiles =
          parseIntOrDefault(
              query.getOrDefault("max_tiles", query.getOrDefault("maxTiles", "")), 0);
      if (state.isEmpty()) {
        writeJson(exchange, 400, "{\"error\":\"missing_state\"}");
        return;
      }
      String payload = ProprietaryMapEngine.startShardPrefetch(state, maxTiles);
      int statusCode =
          payload.contains("\"status\":\"error\"") && payload.contains("\"unknown_state\"") ? 400 : 200;
      writeJson(exchange, statusCode, payload);
    }
  }

  private static final class GpsLatestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      GpsPoint latest = latestGpsPoint;
      if (latest == null) {
        writeJson(exchange, 200, "{\"status\":\"empty\",\"active_users\":0}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"point\":" + gpsPointToJson(latest)
              + "}");
    }
  }

  private static final class GpsTrackHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      int limit = parseIntOrDefault(query.getOrDefault("limit", "120"), 120);
      if (limit < 1) {
        limit = 1;
      }
      if (limit > GPS_TRACK_MAX_POINTS) {
        limit = GPS_TRACK_MAX_POINTS;
      }
      List<GpsPoint> recent = copyRecentTrack(limit);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"count\":" + recent.size() + ","
              + "\"points\":" + gpsTrackToJson(recent)
              + "}");
    }
  }

  private static final class GpsTriangulationHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      List<GpsPoint> points = new ArrayList<>(GPS_BY_USER.values());
      points.sort(Comparator.comparing(p -> p.userId));
      if (points.size() < 2) {
        writeJson(
            exchange,
            200,
            "{"
                + "\"status\":\"insufficient_users\","
                + "\"active_users\":" + points.size() + ","
                + "\"required_users\":2"
                + "}");
        return;
      }
      double latSum = 0.0;
      double lonSum = 0.0;
      double accuracySum = 0.0;
      for (GpsPoint p : points) {
        latSum += p.lat;
        lonSum += p.lon;
        accuracySum += p.accuracy;
      }
      double estLat = latSum / points.size();
      double estLon = lonSum / points.size();
      double avgAccuracy = accuracySum / points.size();
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"method\":\"multi_user_centroid_seed\","
              + "\"active_users\":" + points.size() + ","
              + "\"estimated_lat\":" + trimDouble(estLat) + ","
              + "\"estimated_lon\":" + trimDouble(estLon) + ","
              + "\"average_accuracy_m\":" + trimDouble(avgAccuracy) + ","
              + "\"contributors\":" + gpsTrackToJson(points)
              + "}");
    }
  }

  private static final class StreamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (!clientId.isBlank()) {
        String pullToken = extractClientPullToken(exchange, query);
        if (!isAuthorizedClientPull(clientId, pullToken)) {
          logRequestRejection(
              "/api/platform/stream", exchange, "invalid_client_pull_token", 403, "handler_token_check");
          writeJson(exchange, 403, "{\"error\":\"invalid_client_pull_token\"}");
          return;
        }
        String userId = query.getOrDefault("user_id", "");
        String source = query.getOrDefault("source", "pipeline_stream");
        String sessionId = query.getOrDefault("session_id", "");
        upsertClientRoute(
            clientId,
            userId,
            source,
            sessionId,
            remoteAddressFromExchange(exchange),
            false,
            null);
      }
      writeTextEventStreamHeaders(exchange);
      OutputStream os = exchange.getResponseBody();
      String heartbeat =
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"event_type\":\"server_heartbeat\","
              + "\"source\":\"java_backend\","
              + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\""
              + "}";
      os.write(("data: " + heartbeat + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();

      if (!Files.exists(LOG_PATH)) {
        String warn =
            "{"
                + "\"ts\":\"" + Instant.now().toString() + "\","
                + "\"event_type\":\"server_warning\","
                + "\"message\":\"log file not found: " + jsonEscape(LOG_PATH.toString()) + "\""
                + "}";
        os.write(("data: " + warn + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
      }

      try {
        streamEventsFromLog(os, false, clientId);
      } catch (IOException ignored) {
      } finally {
        os.close();
      }
    }
  }

  private static final class MobileStreamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (!clientId.isBlank()) {
        String pullToken = extractClientPullToken(exchange, query);
        if (!isAuthorizedClientPull(clientId, pullToken)) {
          logRequestRejection(
              "/api/mobile/stream", exchange, "invalid_client_pull_token", 403, "handler_token_check");
          writeJson(exchange, 403, "{\"error\":\"invalid_client_pull_token\"}");
          return;
        }
        String userId = query.getOrDefault("user_id", "");
        String source = query.getOrDefault("source", "mobile_stream");
        String sessionId = query.getOrDefault("session_id", "");
        upsertClientRoute(
            clientId,
            userId,
            source,
            sessionId,
            remoteAddressFromExchange(exchange),
            false,
            null);
      }
      writeTextEventStreamHeaders(exchange);
      OutputStream os = exchange.getResponseBody();
      String hello =
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"event_type\":\"mobile_stream_ready\","
              + "\"source\":\"java_backend\""
              + "}";
      os.write(("data: " + hello + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
      try {
        streamEventsFromLog(os, true, clientId);
      } catch (IOException ignored) {
      } finally {
        os.close();
      }
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static String readRequestBody(HttpExchange exchange) throws IOException {
    try (InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int read;
      int total = 0;
      while ((read = in.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        total += read;
        if (total > MAX_REQUEST_BODY_BYTES) {
          throw new IllegalArgumentException("body_too_large");
        }
        out.write(buffer, 0, read);
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  private static GpsPoint gpsPointFromInputs(Map<String, String> query, String body) {
    double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
    double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
    if (!Double.isFinite(lat)) {
      lat = extractDoubleField(body, "lat", Double.NaN);
    }
    if (!Double.isFinite(lon)) {
      lon = extractDoubleField(body, "lon", Double.NaN);
    }
    if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
      return null;
    }
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      return null;
    }

    String ts = extractStringFieldByName(body, "ts", Instant.now().toString());
    String source = extractStringFieldByName(body, "source", query.getOrDefault("source", "frontend_browser"));
    String userId = extractStringFieldByName(body, "user_id", query.getOrDefault("user_id", "default"));
    long seq = extractLongField(body, "seq", parseLongOrDefault(query.getOrDefault("seq", "0"), 0L));
    double accuracy = extractDoubleField(body, "accuracy", parseDouble(query.getOrDefault("accuracy", ""), 0.0));
    double speed = extractDoubleField(body, "speed", parseDouble(query.getOrDefault("speed", ""), 0.0));
    double heading = extractDoubleField(body, "heading", parseDouble(query.getOrDefault("heading", ""), 0.0));
    if (!Double.isFinite(accuracy) || accuracy < 0) accuracy = 0.0;
    if (!Double.isFinite(speed) || speed < 0) speed = 0.0;
    if (!Double.isFinite(heading)) heading = 0.0;
    if (userId == null || userId.isBlank()) userId = "default";
    if (source == null || source.isBlank()) source = "unknown";

    return new GpsPoint(
        ts,
        userId,
        source,
        seq,
        lat,
        lon,
        accuracy,
        speed,
        heading,
        System.currentTimeMillis());
  }

  private static void appendGpsPoint(GpsPoint point) {
    latestGpsPoint = point;
    GPS_BY_USER.put(point.userId, point);
    ProprietaryMapEngine.updateGps(point.lat, point.lon);
    synchronized (GPS_LOCK) {
      GPS_TRACK.addLast(point);
      while (GPS_TRACK.size() > GPS_TRACK_MAX_POINTS) {
        GPS_TRACK.removeFirst();
      }
    }
  }

  private static List<GpsPoint> copyRecentTrack(int limit) {
    List<GpsPoint> out = new ArrayList<>();
    synchronized (GPS_LOCK) {
      int skip = Math.max(0, GPS_TRACK.size() - limit);
      int idx = 0;
      for (GpsPoint p : GPS_TRACK) {
        if (idx++ < skip) continue;
        out.add(p);
      }
    }
    return out;
  }

  private static String gpsPointToJson(GpsPoint p) {
    return "{"
        + "\"ts\":\"" + jsonEscape(p.ts) + "\","
        + "\"user_id\":\"" + jsonEscape(p.userId) + "\","
        + "\"source\":\"" + jsonEscape(p.source) + "\","
        + "\"seq\":" + p.seq + ","
        + "\"lat\":" + trimDouble(p.lat) + ","
        + "\"lon\":" + trimDouble(p.lon) + ","
        + "\"accuracy\":" + trimDouble(p.accuracy) + ","
        + "\"speed\":" + trimDouble(p.speed) + ","
        + "\"heading\":" + trimDouble(p.heading) + ","
        + "\"received_at_ms\":" + p.receivedAtMs
        + "}";
  }

  private static String gpsTrackToJson(List<GpsPoint> points) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < points.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append(gpsPointToJson(points.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static long clampLong(long value, long min, long max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
  }

  private static String initShareEtaSigningSecret(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  private static String hmacSha256Base64Url(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SHARE_ETA_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception ex) {
      return "";
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    int max = Math.max(a.length(), b.length());
    int diff = a.length() ^ b.length();
    for (int i = 0; i < max; i++) {
      char ca = i < a.length() ? a.charAt(i) : 0;
      char cb = i < b.length() ? b.charAt(i) : 0;
      diff |= ca ^ cb;
    }
    return diff == 0;
  }

  private static String sanitizeShareDestinationLabel(String rawLabel) {
    if (rawLabel == null || rawLabel.isBlank()) {
      return "Destination";
    }
    String compact = rawLabel.trim().replaceAll("\\s+", " ");
    if (compact.length() > SHARE_ETA_DEST_LABEL_MAX_CHARS) {
      return compact.substring(0, SHARE_ETA_DEST_LABEL_MAX_CHARS);
    }
    return compact;
  }

  private static String encodeShareEtaToken(ShareEtaTokenData data) {
    String payload =
        "v1|"
            + data.expiresAtEpochSeconds
            + "|"
            + trimDouble(data.originLat)
            + "|"
            + trimDouble(data.originLon)
            + "|"
            + trimDouble(data.destLat)
            + "|"
            + trimDouble(data.destLon)
            + "|"
            + urlEncode(data.destinationLabel);
    String payloadB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    String signature = hmacSha256Base64Url(payloadB64);
    return payloadB64 + "." + signature;
  }

  private static ShareEtaTokenData decodeShareEtaToken(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String[] tokenParts = token.split("\\.", 2);
    if (tokenParts.length != 2) {
      return null;
    }
    String payloadB64 = tokenParts[0].trim();
    String signature = tokenParts[1].trim();
    String expectedSignature = hmacSha256Base64Url(payloadB64);
    if (!constantTimeEquals(expectedSignature, signature)) {
      return null;
    }
    byte[] payloadBytes;
    try {
      payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
    } catch (IllegalArgumentException ex) {
      return null;
    }
    String payload = new String(payloadBytes, StandardCharsets.UTF_8);
    String[] fields = payload.split("\\|", 7);
    if (fields.length != 7 || !"v1".equals(fields[0])) {
      return null;
    }
    long expiresAt = parseLongOrDefault(fields[1], 0L);
    if (expiresAt <= Instant.now().getEpochSecond()) {
      return null;
    }
    double originLat = parseDouble(fields[2], Double.NaN);
    double originLon = parseDouble(fields[3], Double.NaN);
    double destLat = parseDouble(fields[4], Double.NaN);
    double destLon = parseDouble(fields[5], Double.NaN);
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)
        || Math.abs(originLat) > 90.0
        || Math.abs(destLat) > 90.0
        || Math.abs(originLon) > 180.0
        || Math.abs(destLon) > 180.0) {
      return null;
    }
    String destinationLabel;
    try {
      destinationLabel = URLDecoder.decode(fields[6], StandardCharsets.UTF_8);
    } catch (Exception ex) {
      destinationLabel = "Destination";
    }
    destinationLabel = sanitizeShareDestinationLabel(destinationLabel);
    return new ShareEtaTokenData(expiresAt, originLat, originLon, destLat, destLon, destinationLabel);
  }

  private static String resolvedSharePublicBaseUrl(HttpExchange exchange) {
    if (!SHARE_ETA_PUBLIC_BASE_URL.isBlank()) {
      String normalized = SHARE_ETA_PUBLIC_BASE_URL;
      if (normalized.endsWith("/")) {
        normalized = normalized.substring(0, normalized.length() - 1);
      }
      return normalized;
    }
    String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
    String scheme = "https".equalsIgnoreCase(forwardedProto) ? "https" : "http";
    String host = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
    if (host == null || host.isBlank()) {
      host = exchange.getRequestHeaders().getFirst("Host");
    }
    if (host == null || host.isBlank()) {
      host = HOST + ":" + PORT;
    }
    return scheme + "://" + host;
  }

  private static String buildShareEtaCreateJson(
      Map<String, String> query, String body, HttpExchange exchange) {
    String originLatRaw =
        extractStringFieldByName(body, "origin_lat", query.getOrDefault("origin_lat", "")).trim();
    String originLonRaw =
        extractStringFieldByName(body, "origin_lon", query.getOrDefault("origin_lon", "")).trim();
    String destLatRaw =
        extractStringFieldByName(body, "dest_lat", query.getOrDefault("dest_lat", "")).trim();
    String destLonRaw =
        extractStringFieldByName(body, "dest_lon", query.getOrDefault("dest_lon", "")).trim();
    double originLat = parseDouble(originLatRaw, Double.NaN);
    double originLon = parseDouble(originLonRaw, Double.NaN);
    double destLat = parseDouble(destLatRaw, Double.NaN);
    double destLon = parseDouble(destLonRaw, Double.NaN);
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)) {
      return "{\"error\":\"missing_share_route_coordinates\"}";
    }
    String destinationLabel =
        sanitizeShareDestinationLabel(
            extractStringFieldByName(
                body, "destination_label", query.getOrDefault("destination_label", "Destination")));
    long nowEpochSeconds = Instant.now().getEpochSecond();
    ShareEtaTokenData tokenData =
        new ShareEtaTokenData(
            nowEpochSeconds + SHARE_ETA_TOKEN_TTL_SECONDS,
            originLat,
            originLon,
            destLat,
            destLon,
            destinationLabel);
    String token = encodeShareEtaToken(tokenData);
    String sharePath = "/api/public/share/eta?token=" + urlEncode(token);
    String shareUrl = resolvedSharePublicBaseUrl(exchange) + sharePath;
    boolean publicTransportReady = shareUrl.startsWith("https://");
    return "{"
        + "\"ts\":\""
        + Instant.now().toString()
        + "\","
        + "\"status\":\"ok\","
        + "\"share_url\":\""
        + jsonEscape(shareUrl)
        + "\","
        + "\"expires_at\":\""
        + Instant.ofEpochSecond(tokenData.expiresAtEpochSeconds).toString()
        + "\","
        + "\"security\":{"
        + "\"token\":\"hmac_sha256_signed\","
        + "\"expires\":true,"
        + "\"public_transport_recommended\":\"https\","
        + "\"public_transport_ready\":"
        + publicTransportReady
        + "}"
        + "}";
  }

  private static String buildShareRouteSummary(String routeOptionsJson, String destinationLabel) {
    Matcher distanceMatcher = SHARE_ROUTE_DISTANCE_PATTERN.matcher(routeOptionsJson);
    Matcher durationMatcher = SHARE_ROUTE_DURATION_PATTERN.matcher(routeOptionsJson);
    if (!distanceMatcher.find() || !durationMatcher.find()) {
      return "Route summary unavailable for " + destinationLabel + ".";
    }
    double distanceMeters = parseDouble(distanceMatcher.group(1), 0.0);
    double durationSeconds = parseDouble(durationMatcher.group(1), 0.0);
    double distanceMiles = distanceMeters / 1609.344;
    String scaledDuration = formatScaledEtaDuration(durationSeconds);
    return String.format(
        Locale.ROOT,
        "Route to %s: %.1f mi, %s estimated travel time.",
        destinationLabel,
        distanceMiles,
        scaledDuration);
  }

  private static String normalizeWeatherConditionLabel(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String[] parts = raw.trim().split("_");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isBlank()) {
        continue;
      }
      if (out.length() > 0) {
        out.append(" ");
      }
      out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
      out.append(part.substring(1).toLowerCase(Locale.ROOT));
    }
    return out.length() == 0 ? "unknown" : out.toString();
  }

  private static String buildShareWeatherSummary(String weatherJson) {
    Matcher tempMatcher = SHARE_WEATHER_TEMP_PATTERN.matcher(weatherJson);
    Matcher conditionMatcher = SHARE_WEATHER_CONDITION_PATTERN.matcher(weatherJson);
    if (!tempMatcher.find() || !conditionMatcher.find()) {
      return "Current weather unavailable.";
    }
    String temp = tempMatcher.group(1);
    String condition = normalizeWeatherConditionLabel(conditionMatcher.group(1));
    return "Current weather: " + temp + "°F, " + condition + ".";
  }

  private static List<ShareAlertSnippet> collectNearbyShareAlerts(
      double originLat, double originLon, double destLat, double destLon) {
    List<ShareAlertSnippet> snippets = new ArrayList<>();
    double radiusMeters = Math.max(1000.0, SHARE_ETA_NEARBY_RADIUS_METERS);
    for (EventInfo event : readEventLines(RECENT_EVENT_LIMIT * 4)) {
      if (!"alert_triggered".equals(event.eventType)) {
        continue;
      }
      Matcher matcher = ALERT_COORD_PATTERN.matcher(event.rawJson);
      if (!matcher.find()) {
        continue;
      }
      double lat = parseDouble(matcher.group(1), Double.NaN);
      double lon = parseDouble(matcher.group(2), Double.NaN);
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        continue;
      }
      double distanceFromOrigin = haversineMeters(originLat, originLon, lat, lon);
      double distanceFromDestination = haversineMeters(destLat, destLon, lat, lon);
      double nearestDistance = Math.min(distanceFromOrigin, distanceFromDestination);
      if (nearestDistance > radiusMeters) {
        continue;
      }
      snippets.add(
          new ShareAlertSnippet(
              extractStringField(event.rawJson, TS_PATTERN),
              extractStringField(event.rawJson, ALERT_PATTERN),
              extractStringField(event.rawJson, TRANSCRIPT_PATTERN),
              lat,
              lon,
              nearestDistance));
    }
    snippets.sort(
        (a, b) -> {
          int byDistance = Double.compare(a.distanceMeters, b.distanceMeters);
          if (byDistance != 0) {
            return byDistance;
          }
          return String.valueOf(b.ts).compareTo(String.valueOf(a.ts));
        });
    if (snippets.size() <= SHARE_ETA_MAX_ALERT_ITEMS) {
      return snippets;
    }
    return new ArrayList<>(snippets.subList(0, SHARE_ETA_MAX_ALERT_ITEMS));
  }

  private static String summarizeNearbyAlerts(List<ShareAlertSnippet> alerts) {
    if (alerts.isEmpty()) {
      return "No recent nearby alerts were found.";
    }
    List<String> parts = new ArrayList<>();
    for (ShareAlertSnippet alert : alerts) {
      String headline = alert.alert == null || alert.alert.isBlank() ? alert.transcript : alert.alert;
      if (headline == null || headline.isBlank()) {
        headline = "Scanner alert";
      }
      if (headline.length() > 110) {
        headline = headline.substring(0, 107) + "…";
      }
      parts.add(
          String.format(
              Locale.ROOT,
              "%s (%.1f mi)",
              headline,
              alert.distanceMeters / 1609.344));
    }
    return String.join("; ", parts);
  }

  private static String shareNearbyAlertsToJson(List<ShareAlertSnippet> alerts) {
    if (alerts.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < alerts.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      ShareAlertSnippet alert = alerts.get(i);
      sb.append("{")
          .append("\"ts\":\"")
          .append(jsonEscape(alert.ts == null ? "" : alert.ts))
          .append("\",")
          .append("\"distance_mi\":")
          .append(trimDouble(alert.distanceMeters / 1609.344))
          .append(",")
          .append("\"lat\":")
          .append(trimDouble(alert.lat))
          .append(",")
          .append("\"lon\":")
          .append(trimDouble(alert.lon))
          .append(",")
          .append("\"alert\":\"")
          .append(jsonEscape(alert.alert == null ? "" : alert.alert))
          .append("\",")
          .append("\"transcript\":\"")
          .append(jsonEscape(alert.transcript == null ? "" : alert.transcript))
          .append("\"")
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String buildShareEtaSnapshotJson(ShareEtaTokenData tokenData) {
    Map<String, String> routeQuery = new HashMap<>();
    routeQuery.put("origin_lat", trimDouble(tokenData.originLat));
    routeQuery.put("origin_lon", trimDouble(tokenData.originLon));
    routeQuery.put("dest_lat", trimDouble(tokenData.destLat));
    routeQuery.put("dest_lon", trimDouble(tokenData.destLon));
    String routeOptionsJson = buildRouteOptionsJson(routeQuery);
    Map<String, String> weatherQuery = new HashMap<>();
    weatherQuery.put("lat", trimDouble(tokenData.originLat));
    weatherQuery.put("lon", trimDouble(tokenData.originLon));
    String weatherJson = buildWeatherJson(weatherQuery);
    List<ShareAlertSnippet> nearbyAlerts =
        collectNearbyShareAlerts(
            tokenData.originLat,
            tokenData.originLon,
            tokenData.destLat,
            tokenData.destLon);
    String nearbyAlertSummary = summarizeNearbyAlerts(nearbyAlerts);
    return "{"
        + "\"ts\":\""
        + Instant.now().toString()
        + "\","
        + "\"status\":\"ok\","
        + "\"expires_at\":\""
        + Instant.ofEpochSecond(tokenData.expiresAtEpochSeconds).toString()
        + "\","
        + "\"destination_label\":\""
        + jsonEscape(tokenData.destinationLabel)
        + "\","
        + "\"route_summary\":\""
        + jsonEscape(buildShareRouteSummary(routeOptionsJson, tokenData.destinationLabel))
        + "\","
        + "\"weather_summary\":\""
        + jsonEscape(buildShareWeatherSummary(weatherJson))
        + "\","
        + "\"nearby_alert_summary\":\""
        + jsonEscape(nearbyAlertSummary)
        + "\","
        + "\"nearby_alert_count\":"
        + nearbyAlerts.size()
        + ","
        + "\"nearby_alerts\":"
        + shareNearbyAlertsToJson(nearbyAlerts)
        + ","
        + "\"route_data\":"
        + routeOptionsJson
        + ","
        + "\"weather_data\":"
        + weatherJson
        + "}";
  }

  private static String escapeHtml(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String buildShareEtaHtml(String shareJson) {
    String destinationLabel = extractStringFieldByName(shareJson, "destination_label", "Destination");
    String routeSummary = extractStringFieldByName(shareJson, "route_summary", "Route summary unavailable.");
    String weatherSummary =
        extractStringFieldByName(shareJson, "weather_summary", "Current weather unavailable.");
    String alertSummary =
        extractStringFieldByName(shareJson, "nearby_alert_summary", "No nearby alert summary.");
    String expiresAt = extractStringFieldByName(shareJson, "expires_at", "");
    return "<!doctype html><html><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        + "<title>Scout ETA Share</title>"
        + "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#101217;color:#f2f4f8;padding:20px;line-height:1.45}"
        + ".card{max-width:760px;margin:0 auto;background:#1a1f2b;border-radius:12px;padding:18px}"
        + "h1{margin:0 0 8px;font-size:22px}h2{margin:18px 0 6px;font-size:16px;color:#9ec7ff}p{margin:6px 0}"
        + ".muted{color:#9aa4b2;font-size:13px}</style></head><body><div class=\"card\">"
        + "<h1>Scout ETA Share</h1>"
        + "<p class=\"muted\">Destination: "
        + escapeHtml(destinationLabel)
        + "</p>"
        + "<h2>Route</h2><p>"
        + escapeHtml(routeSummary)
        + "</p>"
        + "<h2>Current Weather</h2><p>"
        + escapeHtml(weatherSummary)
        + "</p>"
        + "<h2>Nearby Alerts</h2><p>"
        + escapeHtml(alertSummary)
        + "</p>"
        + "<p class=\"muted\">Link expires: "
        + escapeHtml(expiresAt)
        + "</p>"
        + "</div></body></html>";
  }

  private static long parseLongOrDefault(String raw, long fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static long extractLongField(String json, String fieldName, long fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (!matcher.find()) return fallback;
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static double extractDoubleField(String json, String fieldName, double fallback) {
    Pattern pattern =
        Pattern.compile(
            "\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"?(-?\\d+(?:\\.\\d+)?)\\\"?");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (!matcher.find()) return fallback;
    try {
      return Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static String extractStringFieldByName(String json, String fieldName, String fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return fallback;
  }

  private static String trimDouble(double value) {
    if (!Double.isFinite(value)) {
      return "0";
    }
    return String.format(Locale.ROOT, "%.7f", value);
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> out = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return out;
    }
    validateRawQuery(rawQuery);
    String[] parts = rawQuery.split("&");
    int accepted = 0;
    for (String part : parts) {
      if (part == null || part.isEmpty()) {
        continue;
      }
      if (accepted >= MAX_QUERY_PARAMS) {
        break;
      }
      int eq = part.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = decodeComponent(part.substring(0, eq));
      String val = decodeComponent(part.substring(eq + 1));
      if (key.length() > MAX_QUERY_KEY_LENGTH) {
        throw new IllegalArgumentException("query_key_too_long");
      }
      if (val.length() > MAX_QUERY_VALUE_LENGTH) {
        throw new IllegalArgumentException("query_value_too_long");
      }
      out.put(key, val);
      accepted++;
    }
    return out;
  }

  private static String decodeComponent(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid_query_encoding");
    }
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static double parseDouble(String value, double fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}