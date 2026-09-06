import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Pure-JDK reader for a planet-scale OSM database extract in PMTiles v3 format
 * (Protomaps basemap build). The archive is never downloaded in full: tiles are
 * pulled with HTTP range requests (or local file seeks when PLANET_PMTILES_URL
 * points at a .pmtiles file on disk) so the server only ever holds the tiles it
 * has actually touched. Includes a minimal MVT (Mapbox Vector Tile) decoder that
 * assimilates tile layers into the proprietary MapModel.
 *
 * Data (c) OpenStreetMap contributors, ODbL. Basemap build by Protomaps.
 */
final class PlanetTileStore {
  private PlanetTileStore() {}

  static final String PLANET_PMTILES_URL =
      System.getenv().getOrDefault("PLANET_PMTILES_URL", "https://build.protomaps.com/20260811.pmtiles");
  private static final String USER_AGENT =
      System.getenv().getOrDefault("EXTERNAL_HTTP_USER_AGENT", "scanner-stream-backend/0.1 (self-hosted)");
  private static final int RANGE_TIMEOUT_MS = 20000;
  private static final int MAX_DIRECTORY_DEPTH = 4;
  private static final int DEFAULT_LEAF_DIR_CACHE_SIZE = 24;
  private static final long INIT_RETRY_BACKOFF_MS = 30000L;

  private static final Object INIT_LOCK = new Object();
  private static volatile Header header;
  private static volatile Directory rootDir;
  private static volatile String lastError = "";

  static final AtomicLong rangeRequestCount = new AtomicLong();
  static final AtomicLong tileFetchCount = new AtomicLong();
  static final AtomicLong tileFetchBytes = new AtomicLong();
  static final AtomicLong tileMissCount = new AtomicLong();
  static final AtomicLong failureCount = new AtomicLong();

  // ---- Dynamic Shard Allocation ----

  static final class ShardConfig {
    final String shardId;
    final String url;
    final int minZoom;
    final int maxZoom;
    final double minLat;
    final double minLon;
    final double maxLat;
    final double maxLon;
    final int leafCacheSize;

    ShardConfig(String shardId, String url, int minZoom, int maxZoom,
                double minLat, double minLon, double maxLat, double maxLon,
                int leafCacheSize) {
      this.shardId = shardId;
      this.url = url;
      this.minZoom = minZoom;
      this.maxZoom = maxZoom;
      this.minLat = minLat;
      this.minLon = minLon;
      this.maxLat = maxLat;
      this.maxLon = maxLon;
      this.leafCacheSize = leafCacheSize;
    }

    boolean containsZoom(int z) {
      return z >= minZoom && z <= maxZoom;
    }

    boolean containsCoords(double lat, double lon) {
      return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    }
  }

  static final class ShardState {
    final ShardConfig config;
    volatile Header header;
    volatile Directory rootDir;
    volatile long lastInitFailMs;
    volatile String lastError;
    final LinkedHashMap<Long, Directory> leafCache;
    final AtomicLong tileFetchCount;
    final AtomicLong tileMissCount;
    final AtomicLong failureCount;
    final AtomicLong tileFetchBytes;

    ShardState(ShardConfig config) {
      this.config = config;
      this.lastInitFailMs = 0L;
      this.lastError = "";
      this.leafCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Directory> eldest) {
          return size() > config.leafCacheSize;
        }
      };
      this.tileFetchCount = new AtomicLong();
      this.tileMissCount = new AtomicLong();
      this.failureCount = new AtomicLong();
      this.tileFetchBytes = new AtomicLong();
    }
  }

  private static final ConcurrentHashMap<String, ShardState> SHARD_REGISTRY = new ConcurrentHashMap<>();
  private static volatile String defaultShardId = null;

  private static final class Header {
    long rootOff;
    long rootLen;
    long leafOff;
    long tileOff;
    int internalComp;
    int tileComp;
    int minZoom;
    int maxZoom;
  }

  private static final class Directory {
    long[] ids;
    long[] runs;
    long[] lens;
    long[] offs;
  }

  static boolean isLocalFile() {
    return !(PLANET_PMTILES_URL.startsWith("http://") || PLANET_PMTILES_URL.startsWith("https://"));
  }

  static boolean ready() {
    ensureInit();
    return header != null;
  }

  static String lastErrorMessage() {
    return defaultShardId != null && SHARD_REGISTRY.get(defaultShardId) != null
        ? SHARD_REGISTRY.get(defaultShardId).lastError
        : lastError;
  }

  /** Registers a shard, replacing any shard with the same id. Returns prior config or null. */
  static ShardConfig registerShard(ShardConfig config) {
    if (config == null || config.shardId == null || config.shardId.isBlank()) {
      throw new IllegalArgumentException("shard requires a non-empty shardId");
    }
    ShardState prior = SHARD_REGISTRY.put(config.shardId, new ShardState(config));
    if (defaultShardId == null) {
      defaultShardId = config.shardId;
    }
    return prior != null ? prior.config : null;
  }

  /** Removes a shard. The default shard (first registered) cannot be removed. */
  static boolean unregisterShard(String shardId) {
    if (shardId == null || shardId.equals(defaultShardId)) {
      return false;
    }
    return SHARD_REGISTRY.remove(shardId) != null;
  }

  static Set<String> registeredShardIds() {
    return SHARD_REGISTRY.keySet();
  }

  private static ShardState resolveShardState(int z, double lat, double lon) {
    if (SHARD_REGISTRY.isEmpty()) {
      return null;
    }
    List<ShardState> candidates = new ArrayList<>();
    for (ShardState state : SHARD_REGISTRY.values()) {
      ShardConfig cfg = state.config;
      if (cfg.containsZoom(z) && cfg.containsCoords(lat, lon)) {
        candidates.add(state);
      }
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      ShardState def = SHARD_REGISTRY.get(defaultShardId);
      return def != null && def.config.containsZoom(z) ? def : null;
    }
    // Multiple candidates: smallest area (most specific jurisdiction) wins.
    ShardState best = null;
    double bestArea = Double.MAX_VALUE;
    for (ShardState state : candidates) {
      ShardConfig cfg = state.config;
      double area = (cfg.maxLat - cfg.minLat) * (cfg.maxLon - cfg.minLon);
      if (area < bestArea) {
        bestArea = area;
        best = state;
      }
    }
    return best;
  }

  // Convert tile + z to lat/lon of the tile's center (Web Mercator inverse). Used
  // for dynamic shard dispatch so shards can be partitioned by jurisdiction.
  static double tileCenterLat(int y, int z) {
    return MapModel.tileToLat(y + 0.5, z);
  }

  static double tileCenterLon(int x, int z) {
    return MapModel.tileToLon(x + 0.5, z);
  }

  /**
   * Fetches one tile from the planet extract. That shard is selected
   * dynamically from the zoom level and the tile's center coordinates; the
   * default shard is used when no jurisdiction shard matches. Returns the tile
   * bytes exactly as stored in the archive (gzip-compressed MVT for Protomaps
   * builds), or null if the tile does not exist or the source is unreachable.
   */
  static byte[] fetchTile(int z, int x, int y) {
    ensureInit();
    ShardState shard = resolveShardState(z, tileCenterLat(y, z), tileCenterLon(x, z));
    if (shard == null) {
      return null;
    }
    ShardConfig cfg = shard.config;
    if (!cfg.containsZoom(z)) {
      return null;
    }
    long tileId = tileId(z, x, y);
    try {
      if (shard.header == null) {
        ensureShardInit(shard);
      }
      if (shard.header == null) {
        return null;
      }
      Header h = shard.header;
      Directory dir = shard.rootDir;
      for (int depth = 0; depth < MAX_DIRECTORY_DEPTH && dir != null; depth++) {
        int idx = findEntry(dir, tileId);
        if (idx < 0) {
          shard.tileMissCount.incrementAndGet();
          tileMissCount.incrementAndGet();
          return null;
        }
        if (dir.runs[idx] == 0) {
          dir = loadLeafDirectory(shard, h, dir.offs[idx], (int) dir.lens[idx]);
          continue;
        }
        if (tileId < dir.ids[idx] + dir.runs[idx]) {
          byte[] tile = readRange(shard, h.tileOff + dir.offs[idx], (int) dir.lens[idx]);
          shard.tileFetchCount.incrementAndGet();
          shard.tileFetchBytes.addAndGet(tile.length);
          tileFetchCount.incrementAndGet();
          tileFetchBytes.addAndGet(tile.length);
          return tile;
        }
        shard.tileMissCount.incrementAndGet();
        tileMissCount.incrementAndGet();
        return null;
      }
      shard.tileMissCount.incrementAndGet();
      tileMissCount.incrementAndGet();
      return null;
    } catch (Exception ex) {
      shard.failureCount.incrementAndGet();
      failureCount.incrementAndGet();
      shard.lastError = "tile_fetch_failed: " + ex.getMessage();
      lastError = shard.lastError;
      return null;
    }
  }

  /** Decodes stored tile bytes (gzip or raw MVT, detected by magic) into a cell. */
  static MapModel.CellData decodeTile(byte[] stored, int z, int x, int y) {
    try {
      byte[] mvt = maybeGunzip(stored);
      MapModel.CellData cell = new MapModel.CellData(z, x, y);
      cell.source = "planet";
      decodeMvtInto(cell, mvt, z, x, y);
      return cell;
    } catch (Exception ex) {
      failureCount.incrementAndGet();
      lastError = "tile_decode_failed: " + ex.getMessage();
      return null;
    }
  }

  static String statusJson() {
    Header h = header;
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"url\":\"").append(escape(PLANET_PMTILES_URL)).append("\",");
    sb.append("\"mode\":\"").append(isLocalFile() ? "local_file" : "http_range").append("\",");
    sb.append("\"ready\":").append(h != null).append(",");
    sb.append("\"upstream_source_read_only\":true,");
    sb.append("\"upstream_mutation\":false,");
    if (h != null) {
      sb.append("\"min_zoom\":").append(h.minZoom).append(",");
      sb.append("\"max_zoom\":").append(h.maxZoom).append(",");
    }
    sb.append("\"range_requests\":").append(rangeRequestCount.get()).append(",");
    sb.append("\"tiles_fetched\":").append(tileFetchCount.get()).append(",");
    sb.append("\"tile_bytes_fetched\":").append(tileFetchBytes.get()).append(",");
    sb.append("\"tile_misses\":").append(tileMissCount.get()).append(",");
    sb.append("\"failures\":").append(failureCount.get()).append(",");
    sb.append("\"dynamic_sharding\":true,");
    sb.append("\"default_shard\":\"").append(escape(defaultShardId == null ? "" : defaultShardId)).append("\",");
    sb.append("\"shards\":").append(shardsJson());
    sb.append(",\"last_error\":\"").append(escape(lastError)).append("\"");
    sb.append("}");
    return sb.toString();
  }

  private static String shardsJson() {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (ShardState state : SHARD_REGISTRY.values()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      ShardConfig cfg = state.config;
      boolean ready = state.header != null;
      sb.append("{");
      sb.append("\"shard_id\":\"").append(escape(cfg.shardId)).append("\",");
      sb.append("\"url\":\"").append(escape(cfg.url)).append("\",");
      sb.append("\"min_zoom\":").append(cfg.minZoom).append(",");
      sb.append("\"max_zoom\":").append(cfg.maxZoom).append(",");
      sb.append("\"bounds\":[").append(cfg.minLat).append(",").append(cfg.minLon).append(",")
          .append(cfg.maxLat).append(",").append(cfg.maxLon).append("],");
      sb.append("\"leaf_cache_size\":").append(cfg.leafCacheSize).append(",");
      sb.append("\"ready\":").append(ready).append(",");
      sb.append("\"tiles_fetched\":").append(state.tileFetchCount.get()).append(",");
      sb.append("\"tile_bytes_fetched\":").append(state.tileFetchBytes.get()).append(",");
      sb.append("\"tile_misses\":").append(state.tileMissCount.get()).append(",");
      sb.append("\"failures\":").append(state.failureCount.get()).append(",");
      sb.append("\"last_error\":\"").append(escape(state.lastError)).append("\"");
      sb.append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  // ---- PMTiles container ----

  private static boolean ensureInit() {
    if (header != null) {
      return true;
    }
    synchronized (INIT_LOCK) {
      if (header != null) {
        return true;
      }
      if (defaultShardId == null) {
        registerShard(
            new ShardConfig(
                "default",
                PLANET_PMTILES_URL,
                0,
                15,
                -85.0511,
                -180.0,
                85.0511,
                180.0,
                DEFAULT_LEAF_DIR_CACHE_SIZE));
      }
      ShardState def = SHARD_REGISTRY.get(defaultShardId);
      if (def == null) {
        lastError = "init_failed: default shard not registered";
        return false;
      }
      if (def.header != null) {
        header = def.header;
        rootDir = def.rootDir;
        return true;
      }
      if (System.currentTimeMillis() - def.lastInitFailMs < INIT_RETRY_BACKOFF_MS) {
        return false;
      }
      boolean ok = ensureShardInit(def);
      if (ok) {
        header = def.header;
        rootDir = def.rootDir;
      }
      return ok;
    }
  }

  private static boolean ensureShardInit(ShardState shard) {
    if (shard.header != null) {
      return true;
    }
    synchronized (shard) {
      if (shard.header != null) {
        return true;
      }
      if (System.currentTimeMillis() - shard.lastInitFailMs < INIT_RETRY_BACKOFF_MS) {
        return false;
      }
      try {
        byte[] head = readRange(shard, 0, 16384);
        if (head.length < 127
            || head[0] != 'P' || head[1] != 'M' || head[2] != 'T'
            || head[3] != 'i' || head[4] != 'l' || head[5] != 'e' || head[6] != 's') {
          throw new IOException("not a PMTiles archive");
        }
        if ((head[7] & 0xFF) != 3) {
          throw new IOException("unsupported PMTiles version " + (head[7] & 0xFF));
        }
        Header h = new Header();
        h.rootOff = readLongLe(head, 8);
        h.rootLen = readLongLe(head, 16);
        h.leafOff = readLongLe(head, 40);
        h.tileOff = readLongLe(head, 56);
        h.internalComp = head[97] & 0xFF;
        h.tileComp = head[98] & 0xFF;
        h.minZoom = head[100] & 0xFF;
        h.maxZoom = head[101] & 0xFF;
        if (h.internalComp != 1 && h.internalComp != 2) {
          throw new IOException("unsupported internal compression " + h.internalComp);
        }
        if (h.rootOff + h.rootLen > head.length) {
          throw new IOException("root directory outside first 16KB");
        }
        byte[] rootRaw = new byte[(int) h.rootLen];
        System.arraycopy(head, (int) h.rootOff, rootRaw, 0, (int) h.rootLen);
        Directory root = parseDirectory(h.internalComp == 2 ? gunzip(rootRaw) : rootRaw);
        shard.header = h;
        shard.rootDir = root;
        shard.lastError = "";
        return true;
      } catch (Exception ex) {
        shard.lastInitFailMs = System.currentTimeMillis();
        shard.failureCount.incrementAndGet();
        shard.lastError = "init_failed: " + ex.getMessage();
        return false;
      }
    }
  }

  private static Directory loadLeafDirectory(ShardState shard, Header h, long off, int len) throws IOException {
    Long key = off;
    synchronized (shard.leafCache) {
      Directory cached = shard.leafCache.get(key);
      if (cached != null) {
        return cached;
      }
    }
    byte[] raw = readRange(shard, h.leafOff + off, len);
    Directory dir = parseDirectory(h.internalComp == 2 ? gunzip(raw) : raw);
    synchronized (shard.leafCache) {
      shard.leafCache.put(key, dir);
    }
    return dir;
  }

  private static Directory parseDirectory(byte[] data) throws IOException {
    int[] pos = {0};
    long n = readVarint(data, pos);
    if (n < 0 || n > 10_000_000L) {
      throw new IOException("directory entry count out of range: " + n);
    }
    int count = (int) n;
    Directory dir = new Directory();
    dir.ids = new long[count];
    dir.runs = new long[count];
    dir.lens = new long[count];
    dir.offs = new long[count];
    long last = 0;
    for (int i = 0; i < count; i++) {
      last += readVarint(data, pos);
      dir.ids[i] = last;
    }
    for (int i = 0; i < count; i++) {
      dir.runs[i] = readVarint(data, pos);
    }
    for (int i = 0; i < count; i++) {
      dir.lens[i] = readVarint(data, pos);
    }
    for (int i = 0; i < count; i++) {
      long v = readVarint(data, pos);
      if (v == 0 && i > 0) {
        dir.offs[i] = dir.offs[i - 1] + dir.lens[i - 1];
      } else {
        dir.offs[i] = v - 1;
      }
    }
    return dir;
  }

  /** Index of the last entry with id <= tileId, or -1. */
  private static int findEntry(Directory dir, long tileId) {
    int lo = 0;
    int hi = dir.ids.length - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (dir.ids[mid] <= tileId) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  /** PMTiles tile id: cumulative tile count below z, plus Hilbert index at z. */
  static long tileId(int z, int x, int y) {
    long acc = ((1L << (2 * z)) - 1) / 3;
    long tx = x;
    long ty = y;
    long d = 0;
    for (long s = (1L << z) >> 1; s > 0; s >>= 1) {
      long rx = (tx & s) > 0 ? 1 : 0;
      long ry = (ty & s) > 0 ? 1 : 0;
      d += s * s * ((3 * rx) ^ ry);
      if (ry == 0) {
        if (rx == 1) {
          tx = s - 1 - tx;
          ty = s - 1 - ty;
        }
        long tmp = tx;
        tx = ty;
        ty = tmp;
      }
    }
    return acc + d;
  }

  // ---- Byte range access ----

  private static byte[] readRange(ShardState shard, long off, int len) throws IOException {
    rangeRequestCount.incrementAndGet();
    String url = shard != null ? shard.config.url : PLANET_PMTILES_URL;
    boolean local = !(url.startsWith("http://") || url.startsWith("https://"));
    if (local) {
      try (RandomAccessFile raf = new RandomAccessFile(url, "r")) {
        long avail = raf.length() - off;
        int toRead = (int) Math.min(len, Math.max(0, avail));
        byte[] out = new byte[toRead];
        raf.seek(off);
        raf.readFully(out);
        return out;
      }
    }
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(RANGE_TIMEOUT_MS);
      conn.setReadTimeout(RANGE_TIMEOUT_MS);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Range", "bytes=" + off + "-" + (off + len - 1));
      int status = conn.getResponseCode();
      if (status != 206 && !(status == 200 && off == 0)) {
        throw new IOException("range request failed with HTTP " + status);
      }
      try (InputStream in = conn.getInputStream()) {
        byte[] out = new byte[len];
        int total = 0;
        while (total < len) {
          int read = in.read(out, total, len - total);
          if (read < 0) {
            break;
          }
          total += read;
        }
        if (total < len) {
          byte[] trimmed = new byte[total];
          System.arraycopy(out, 0, trimmed, 0, total);
          return trimmed;
        }
        return out;
      }
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  // ---- MVT decoding (protobuf wire format, no dependencies) ----

  private static void decodeMvtInto(MapModel.CellData cell, byte[] mvt, int z, int x, int y) {
    Pb tile = new Pb(mvt, 0, mvt.length);
    while (tile.hasMore()) {
      int tag = (int) tile.varint();
      int field = tag >>> 3;
      int wire = tag & 7;
      if (field == 3 && wire == 2) {
        decodeLayer(tile.sub(), cell, z, x, y);
      } else {
        tile.skip(wire);
      }
    }
  }

  private static void decodeLayer(Pb layer, MapModel.CellData cell, int z, int x, int y) {
    String name = "";
    int extent = 4096;
    List<String> keys = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    List<Pb> features = new ArrayList<>();
    while (layer.hasMore()) {
      int tag = (int) layer.varint();
      int field = tag >>> 3;
      int wire = tag & 7;
      switch (field) {
        case 1:
          name = layer.string();
          break;
        case 2:
          features.add(layer.sub());
          break;
        case 3:
          keys.add(layer.string());
          break;
        case 4:
          values.add(decodeValue(layer.sub()));
          break;
        case 5:
          extent = (int) layer.varint();
          break;
        default:
          layer.skip(wire);
      }
    }
    boolean isRoads = "roads".equals(name);
    boolean isBuildings = "buildings".equals(name);
    boolean isWater = "water".equals(name);
    boolean isLanduse = "landuse".equals(name);
    boolean isPois = "pois".equals(name);
    boolean isPlaces = "places".equals(name);
    if (!isRoads && !isBuildings && !isWater && !isLanduse && !isPois && !isPlaces) {
      return;
    }
    for (Pb feature : features) {
      decodeFeature(
          feature, cell, keys, values, extent, z, x, y,
          isRoads, isBuildings, isWater, isLanduse, isPois, isPlaces);
    }
  }

  private static Object decodeValue(Pb value) {
    Object out = null;
    while (value.hasMore()) {
      int tag = (int) value.varint();
      int field = tag >>> 3;
      int wire = tag & 7;
      switch (field) {
        case 1:
          out = value.string();
          break;
        case 2:
          out = (double) Float.intBitsToFloat((int) value.fixed32());
          break;
        case 3:
          out = Double.longBitsToDouble(value.fixed64());
          break;
        case 4:
        case 5:
          out = value.varint();
          break;
        case 6:
          out = zigzag(value.varint());
          break;
        case 7:
          out = value.varint() != 0;
          break;
        default:
          value.skip(wire);
      }
    }
    return out;
  }

  private static void decodeFeature(
      Pb feature,
      MapModel.CellData cell,
      List<String> keys,
      List<Object> values,
      int extent,
      int z,
      int x,
      int y,
      boolean isRoads,
      boolean isBuildings,
      boolean isWater,
      boolean isLanduse,
      boolean isPois,
      boolean isPlaces) {
    int geomType = 0;
    long[] tags = null;
    long[] geometry = null;
    while (feature.hasMore()) {
      int tag = (int) feature.varint();
      int field = tag >>> 3;
      int wire = tag & 7;
      switch (field) {
        case 2:
          tags = feature.packedVarints();
          break;
        case 3:
          geomType = (int) feature.varint();
          break;
        case 4:
          geometry = feature.packedVarints();
          break;
        default:
          feature.skip(wire);
      }
    }
    if (geometry == null) {
      return;
    }
    Map<String, Object> attrs = new java.util.HashMap<>();
    if (tags != null) {
      for (int i = 0; i + 1 < tags.length; i += 2) {
        int ki = (int) tags[i];
        int vi = (int) tags[i + 1];
        if (ki >= 0 && ki < keys.size() && vi >= 0 && vi < values.size()) {
          attrs.put(keys.get(ki), values.get(vi));
        }
      }
    }
    if (isRoads && geomType == 2) {
      String clazz = roadClass(attrs);
      if (clazz == null) {
        return;
      }
      String roadName = str(attrs, "name");
      if (roadName.isEmpty()) {
        roadName = str(attrs, "ref");
      }
      boolean oneway = "1".equals(str(attrs, "oneway")) || "yes".equals(str(attrs, "oneway"));
      for (double[] part : decodeGeomParts(geometry, geomType, extent, z, x, y)) {
        if (part.length < 4) {
          continue;
        }
        MapModel.Road road = new MapModel.Road();
        road.clazz = clazz;
        road.name = roadName;
        road.oneway = oneway;
        road.pts = part;
        cell.roads.add(road);
      }
    } else if (isBuildings && geomType == 3) {
      if ("address".equals(str(attrs, "kind"))) {
        return;
      }
      double height = num(attrs, "height", 6.0);
      if (height <= 0 || height > 700) {
        height = 6.0;
      }
      for (double[] ring : decodeGeomParts(geometry, geomType, extent, z, x, y)) {
        if (ring.length < 6) {
          continue;
        }
        MapModel.Building b = new MapModel.Building();
        b.heightM = height;
        b.pts = ring;
        cell.buildings.add(b);
      }
    } else if ((isWater || isLanduse) && geomType == 3) {
      String kind = isWater ? "water" : areaKind(str(attrs, "kind"));
      if (kind == null) {
        return;
      }
      for (double[] ring : decodeGeomParts(geometry, geomType, extent, z, x, y)) {
        if (ring.length < 6) {
          continue;
        }
        MapModel.Area a = new MapModel.Area();
        a.kind = kind;
        a.pts = ring;
        cell.areas.add(a);
      }
    } else if ((isPois || isPlaces) && geomType == 1) {
      String poiName = str(attrs, "name");
      if (poiName.isEmpty()) {
        return;
      }
      String kind;
      if (isPlaces) {
        // Place labels (city/town/region names) power the zoomed-out map.
        String detail = str(attrs, "kind_detail");
        kind = "place_" + (detail.isEmpty() ? str(attrs, "kind") : detail);
      } else {
        kind = str(attrs, "kind");
      }
      for (double[] pt : decodeGeomParts(geometry, geomType, extent, z, x, y)) {
        if (pt.length < 2) {
          continue;
        }
        MapModel.Poi poi = new MapModel.Poi();
        poi.name = poiName;
        poi.kind = kind;
        poi.lat = pt[0];
        poi.lon = pt[1];
        cell.pois.add(poi);
        break; // one point per POI feature
      }
    }
  }

  private static String roadClass(Map<String, Object> attrs) {
    String kd = str(attrs, "kind_detail");
    String kind = str(attrs, "kind");
    switch (kd) {
      case "motorway":
      case "motorway_link":
      case "trunk":
      case "trunk_link":
        return "motorway";
      case "primary":
      case "primary_link":
        return "primary";
      case "secondary":
      case "secondary_link":
        return "secondary";
      case "tertiary":
      case "tertiary_link":
        return "tertiary";
      case "residential":
      case "unclassified":
      case "living_street":
      case "road":
      case "pedestrian":
        return "residential";
      case "service":
      case "driveway":
      case "parking_aisle":
      case "alley":
      case "drive-through":
        return "service";
      case "path":
      case "footway":
      case "cycleway":
      case "track":
      case "steps":
      case "sidewalk":
      case "crossing":
      case "bridleway":
      case "corridor":
        return "path";
      default:
        break;
    }
    switch (kind) {
      case "highway":
        return "motorway";
      case "major_road":
        return "primary";
      case "medium_road":
        return "secondary";
      case "minor_road":
        return "residential";
      case "path":
        return "path";
      case "rail":
        return "rail";
      default:
        return null; // ferry, aerialway, pier, aeroway: not rendered
    }
  }

  private static String areaKind(String kind) {
    switch (kind) {
      case "park":
      case "garden":
      case "grass":
      case "forest":
      case "wood":
      case "meadow":
      case "cemetery":
      case "golf_course":
      case "nature_reserve":
      case "national_park":
      case "protected_area":
      case "recreation_ground":
      case "dog_park":
      case "playground":
      case "pitch":
      case "farmland":
      case "farmyard":
      case "orchard":
      case "scrub":
      case "wetland":
      case "zoo":
      case "camp_site":
        return "park";
      case "beach":
      case "sand":
        return "sand";
      case "commercial":
      case "industrial":
      case "retail":
      case "aerodrome":
      case "runway":
      case "taxiway":
      case "military":
      case "pier":
        return "lot";
      case "school":
      case "college":
      case "university":
      case "hospital":
      case "kindergarten":
      case "library":
      case "townhall":
      case "stadium":
      case "post_office":
        return "civic";
      default:
        return null;
    }
  }

  /**
   * Decodes an MVT geometry command stream into parts. For polygons, only
   * exterior rings (positive signed area, y-down) are kept. Returned arrays are
   * interleaved [lat, lon, ...].
   */
  private static List<double[]> decodeGeomParts(long[] geom, int geomType, int extent, int z, int x, int y) {
    List<double[]> parts = new ArrayList<>();
    List<double[]> currentTile = new ArrayList<>(); // tile-space points for area check
    long cx = 0;
    long cy = 0;
    int i = 0;
    while (i < geom.length) {
      long cmdInt = geom[i++];
      int cmd = (int) (cmdInt & 0x7);
      int count = (int) (cmdInt >>> 3);
      if (cmd == 1) { // MoveTo
        for (int k = 0; k < count && i + 1 < geom.length + 1; k++) {
          if (i + 1 > geom.length) {
            break;
          }
          cx += zigzag(geom[i++]);
          cy += zigzag(geom[i++]);
          if (geomType == 1) {
            parts.add(new double[] {tileLat(cy, extent, y, z), tileLon(cx, extent, x, z)});
          } else {
            flushPart(parts, currentTile, geomType, extent, x, y, z);
            currentTile.add(new double[] {cx, cy});
          }
        }
      } else if (cmd == 2) { // LineTo
        for (int k = 0; k < count; k++) {
          if (i + 1 > geom.length) {
            break;
          }
          cx += zigzag(geom[i++]);
          cy += zigzag(geom[i++]);
          currentTile.add(new double[] {cx, cy});
        }
      } else if (cmd == 7) { // ClosePath
        flushPart(parts, currentTile, geomType, extent, x, y, z);
      } else {
        break;
      }
    }
    flushPart(parts, currentTile, geomType, extent, x, y, z);
    return parts;
  }

  private static void flushPart(
      List<double[]> parts, List<double[]> currentTile, int geomType, int extent, int x, int y, int z) {
    if (currentTile.isEmpty()) {
      return;
    }
    if (geomType == 3) {
      // Signed area in tile coords (y down): exterior rings are positive per MVT spec.
      double area = 0;
      int n = currentTile.size();
      for (int k = 0; k < n; k++) {
        double[] p1 = currentTile.get(k);
        double[] p2 = currentTile.get((k + 1) % n);
        area += p1[0] * p2[1] - p2[0] * p1[1];
      }
      if (area <= 0) {
        currentTile.clear();
        return; // drop interior rings (holes)
      }
    }
    double[] out = new double[currentTile.size() * 2];
    for (int k = 0; k < currentTile.size(); k++) {
      double[] p = currentTile.get(k);
      out[k * 2] = tileLat(p[1], extent, y, z);
      out[k * 2 + 1] = tileLon(p[0], extent, x, z);
    }
    parts.add(out);
    currentTile.clear();
  }

  private static double tileLon(double px, int extent, int x, int z) {
    return MapModel.tileToLon(x + px / extent, z);
  }

  private static double tileLat(double py, int extent, int y, int z) {
    return MapModel.tileToLat(y + py / extent, z);
  }

  private static String str(Map<String, Object> attrs, String key) {
    Object v = attrs.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  private static double num(Map<String, Object> attrs, String key, double fallback) {
    Object v = attrs.get(key);
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    if (v instanceof String) {
      try {
        return Double.parseDouble(((String) v).replace("m", "").trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  // ---- Low-level helpers ----

  private static final class Pb {
    private final byte[] b;
    private int pos;
    private final int end;

    Pb(byte[] b, int pos, int end) {
      this.b = b;
      this.pos = pos;
      this.end = end;
    }

    boolean hasMore() {
      return pos < end;
    }

    long varint() {
      long val = 0;
      int shift = 0;
      while (pos < end) {
        byte cur = b[pos++];
        val |= (long) (cur & 0x7F) << shift;
        if ((cur & 0x80) == 0) {
          return val;
        }
        shift += 7;
      }
      return val;
    }

    long fixed32() {
      long v = (b[pos] & 0xFFL)
          | (b[pos + 1] & 0xFFL) << 8
          | (b[pos + 2] & 0xFFL) << 16
          | (b[pos + 3] & 0xFFL) << 24;
      pos += 4;
      return v;
    }

    long fixed64() {
      long v = 0;
      for (int i = 0; i < 8; i++) {
        v |= (b[pos + i] & 0xFFL) << (8 * i);
      }
      pos += 8;
      return v;
    }

    Pb sub() {
      int len = (int) varint();
      Pb s = new Pb(b, pos, Math.min(end, pos + len));
      pos += len;
      return s;
    }

    String string() {
      int len = (int) varint();
      String s = new String(b, pos, Math.min(len, end - pos), StandardCharsets.UTF_8);
      pos += len;
      return s;
    }

    long[] packedVarints() {
      int len = (int) varint();
      int stop = Math.min(end, pos + len);
      List<Long> vals = new ArrayList<>();
      while (pos < stop) {
        vals.add(varint());
      }
      long[] out = new long[vals.size()];
      for (int i = 0; i < out.length; i++) {
        out[i] = vals.get(i);
      }
      return out;
    }

    void skip(int wire) {
      switch (wire) {
        case 0:
          varint();
          break;
        case 1:
          pos += 8;
          break;
        case 2:
          pos += (int) varint();
          break;
        case 5:
          pos += 4;
          break;
        default:
          pos = end;
      }
    }
  }

  private static long zigzag(long v) {
    return (v >>> 1) ^ -(v & 1);
  }

  private static long readVarint(byte[] data, int[] pos) throws IOException {
    long val = 0;
    int shift = 0;
    while (pos[0] < data.length) {
      byte cur = data[pos[0]++];
      val |= (long) (cur & 0x7F) << shift;
      if ((cur & 0x80) == 0) {
        return val;
      }
      shift += 7;
      if (shift > 63) {
        throw new IOException("varint too long");
      }
    }
    throw new IOException("truncated varint");
  }

  private static long readLongLe(byte[] data, int off) {
    long v = 0;
    for (int i = 0; i < 8; i++) {
      v |= (data[off + i] & 0xFFL) << (8 * i);
    }
    return v;
  }

  static byte[] maybeGunzip(byte[] data) throws IOException {
    if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
      return gunzip(data);
    }
    return data;
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4))) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = in.read(buf)) >= 0) {
        out.write(buf, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
