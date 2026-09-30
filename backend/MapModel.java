import java.util.ArrayList;
import java.util.List;

/**
 * Proprietary map data model shared by the planet tile store and the map engine.
 * Coordinates are stored as interleaved [lat0, lon0, lat1, lon1, ...] arrays.
 */
final class MapModel {
  private MapModel() {}

  static final class Road {
    String clazz = "residential"; // motorway|primary|secondary|tertiary|residential|service|path|rail
    String name = "";
    boolean oneway = false;
    double[] pts;
  }

  static final class Building {
    double heightM = 6.0;
    double[] pts;
  }

  static final class Area {
    String kind = "other"; // park|water|sand|lot|civic
    double[] pts;
  }

  static final class Poi {
    String name = "";
    String kind = "";
    double lat;
    double lon;
  }

  static final class CellData {
    final int zoom;
    final int x;
    final int y;
    String source = "unknown"; // planet | overpass
    final List<Road> roads = new ArrayList<>();
    final List<Building> buildings = new ArrayList<>();
    final List<Area> areas = new ArrayList<>();
    final List<Poi> pois = new ArrayList<>();

    CellData(int zoom, int x, int y) {
      this.zoom = zoom;
      this.x = x;
      this.y = y;
    }

    int featureCount() {
      return roads.size() + buildings.size() + areas.size() + pois.size();
    }
  }

  // ---- Web mercator tile math ----

  static double tileToLon(double x, int z) {
    return x / Math.pow(2.0, z) * 360.0 - 180.0;
  }

  static double tileToLat(double y, int z) {
    double n = Math.PI - 2.0 * Math.PI * y / Math.pow(2.0, z);
    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }

  static int lonToTileX(double lon, int z) {
    int n = 1 << z;
    int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
    return Math.max(0, Math.min(n - 1, x));
  }

  static int latToTileY(double lat, int z) {
    int n = 1 << z;
    double latRad = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
    int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
    return Math.max(0, Math.min(n - 1, y));
  }

  // ---- Jurisdiction (state) bounds: mirrors the channel-selector state sharding ----
  // {code, minLat, minLon, maxLat, maxLon}; approximate bounding boxes used only to
  // organize the on-disk shard cache per jurisdiction. Smallest containing box wins.

  private static final Object[][] STATE_BOUNDS = {
    {"AL", 30.14, -88.47, 35.01, -84.89},
    {"AK", 51.21, -179.15, 71.44, -129.97},
    {"AZ", 31.33, -114.82, 37.00, -109.05},
    {"AR", 33.00, -94.62, 36.50, -89.64},
    {"CA", 32.53, -124.41, 42.01, -114.13},
    {"CO", 36.99, -109.06, 41.00, -102.04},
    {"CT", 40.98, -73.73, 42.05, -71.79},
    {"DE", 38.45, -75.79, 39.84, -75.05},
    {"DC", 38.79, -77.12, 39.00, -76.91},
    {"FL", 24.40, -87.63, 31.00, -80.03},
    {"GA", 30.36, -85.61, 35.00, -80.84},
    {"HI", 18.90, -160.25, 22.24, -154.80},
    {"ID", 41.99, -117.24, 49.00, -111.04},
    {"IL", 36.97, -91.51, 42.51, -87.02},
    {"IN", 37.77, -88.10, 41.76, -84.78},
    {"IA", 40.38, -96.64, 43.50, -90.14},
    {"KS", 36.99, -102.05, 40.00, -94.59},
    {"KY", 36.50, -89.57, 39.15, -81.96},
    {"LA", 28.93, -94.04, 33.02, -88.82},
    {"ME", 42.98, -71.08, 47.46, -66.95},
    {"MD", 37.91, -79.49, 39.72, -75.05},
    {"MA", 41.24, -73.51, 42.89, -69.93},
    {"MI", 41.70, -90.42, 48.30, -82.12},
    {"MN", 43.50, -97.24, 49.38, -89.49},
    {"MS", 30.17, -91.65, 35.00, -88.10},
    {"MO", 35.99, -95.77, 40.61, -89.10},
    {"MT", 44.36, -116.05, 49.00, -104.04},
    {"NE", 39.99, -104.05, 43.00, -95.31},
    {"NV", 35.00, -120.00, 42.00, -114.04},
    {"NH", 42.70, -72.56, 45.31, -70.61},
    {"NJ", 38.93, -75.56, 41.36, -73.89},
    {"NM", 31.33, -109.05, 37.00, -103.00},
    {"NY", 40.50, -79.76, 45.02, -71.86},
    {"NC", 33.84, -84.32, 36.59, -75.46},
    {"ND", 45.94, -104.05, 49.00, -96.55},
    {"OH", 38.40, -84.82, 41.98, -80.52},
    {"OK", 33.62, -103.00, 37.00, -94.43},
    {"OR", 41.99, -124.57, 46.29, -116.46},
    {"PA", 39.72, -80.52, 42.27, -74.69},
    {"RI", 41.15, -71.86, 42.02, -71.12},
    {"SC", 32.03, -83.35, 35.22, -78.54},
    {"SD", 42.48, -104.06, 45.95, -96.44},
    {"TN", 34.98, -90.31, 36.68, -81.65},
    {"TX", 25.84, -106.65, 36.50, -93.51},
    {"UT", 36.99, -114.05, 42.00, -109.04},
    {"VT", 42.73, -73.44, 45.02, -71.46},
    {"VA", 36.54, -83.68, 39.47, -75.24},
    {"WA", 45.54, -124.85, 49.00, -116.92},
    {"WV", 37.20, -82.65, 40.64, -77.72},
    {"WI", 42.49, -92.89, 47.08, -86.81},
    {"WY", 40.99, -111.06, 45.01, -104.05},
    {"PR", 17.88, -67.95, 18.52, -65.22},
  };

  /** Returns the two-letter jurisdiction code containing the point, or "XX" when unknown. */
  static String stateFor(double lat, double lon) {
    String best = "XX";
    double bestArea = Double.MAX_VALUE;
    for (Object[] row : STATE_BOUNDS) {
      double minLat = (Double) row[1];
      double minLon = (Double) row[2];
      double maxLat = (Double) row[3];
      double maxLon = (Double) row[4];
      if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
        continue;
      }
      double area = (maxLat - minLat) * (maxLon - minLon);
      if (area < bestArea) {
        bestArea = area;
        best = (String) row[0];
      }
    }
    return best;
  }

  /** Returns {minLat, minLon, maxLat, maxLon} for a jurisdiction code, or null. */
  static double[] stateBounds(String code) {
    if (code == null) {
      return null;
    }
    String up = code.trim().toUpperCase(java.util.Locale.ROOT);
    for (Object[] row : STATE_BOUNDS) {
      if (row[0].equals(up)) {
        return new double[] {(Double) row[1], (Double) row[2], (Double) row[3], (Double) row[4]};
      }
    }
    return null;
  }
}
