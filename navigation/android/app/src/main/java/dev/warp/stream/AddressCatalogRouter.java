package dev.warp.stream;

import android.net.Uri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Callback;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Client-side address lookup funnel:
 * 1) ask homelab catalog endpoint(s)
 * 2) fallback to existing homelab OSM geocode endpoint
 * 3) upsert successful fallback results into catalog (best effort)
 */
final class AddressCatalogRouter {
  interface ResolveCallback {
    void onResolved(AddressCandidate candidate, boolean fromCatalog);

    void onFailure(String message);
  }

  static final class AddressCandidate {
    final double lat;
    final double lon;
    final String displayName;

    AddressCandidate(double lat, double lon, String displayName) {
      this.lat = lat;
      this.lon = lon;
      this.displayName = displayName;
    }
  }

  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.get("application/json; charset=utf-8");

  private final Call.Factory client;

  AddressCatalogRouter(Call.Factory client) {
    this.client = client;
  }


  interface SuggestCallback {
    void onSuggestions(List<AddressCandidate> suggestions);

    void onFailure(String message);
  }
  void resolve(
      String baseUrl, String query, Double biasLat, Double biasLon, ResolveCallback callback) {
    String catalogUrl =
        baseUrl
            + "/api/platform/address-catalog/resolve?q="
            + Uri.encode(query)
            + biasParams(biasLat, biasLon);
    Request request = new Request.Builder().url(catalogUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                fallbackToGeocode(baseUrl, query, biasLat, biasLon, callback);
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    fallbackToGeocode(baseUrl, query, biasLat, biasLon, callback);
                    return;
                  }
                  AddressCandidate candidate = parseCatalogPayload(response.body().string(), query);
                  if (candidate == null) {
                    fallbackToGeocode(baseUrl, query, biasLat, biasLon, callback);
                    return;
                  }
                  callback.onResolved(candidate, true);
                } catch (Exception ignored) {
                  fallbackToGeocode(baseUrl, query, biasLat, biasLon, callback);
                }
              }
            });
  }

  private void fallbackToGeocode(
      String baseUrl, String query, Double biasLat, Double biasLon, ResolveCallback callback) {
    String geocodeUrl =
        baseUrl + "/api/platform/geocode?q=" + Uri.encode(query) + biasParams(biasLat, biasLon);
    Request request = new Request.Builder().url(geocodeUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                callback.onFailure("geocode failed: " + e.getMessage());
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure("geocode unavailable (HTTP " + response.code() + ")");
                    return;
                  }
                  AddressCandidate candidate = parseGeocodePayload(response.body().string(), query);
                  if (candidate == null) {
                    callback.onFailure("no OSM matches for: " + query);
                    return;
                  }
                  callback.onResolved(candidate, false);
                  upsertCatalogEntry(baseUrl, query, candidate, biasLat, biasLon);
                } catch (Exception e) {
                  callback.onFailure("geocode parse error: " + e.getMessage());
                }
              }
            });
  }

  private String biasParams(Double biasLat, Double biasLon) {
    if (biasLat == null || biasLon == null) {
      return "";
    }
    return String.format(Locale.ROOT, "&lat=%.6f&lon=%.6f", biasLat, biasLon);
  }

  private AddressCandidate parseCatalogPayload(String body, String query) throws Exception {
    JSONObject json = new JSONObject(body);
    JSONObject candidate =
        firstObject(
            json.optJSONObject("entry"),
            json.optJSONObject("result"),
            json.optJSONObject("address"),
            firstArrayObject(json.optJSONArray("results")),
            firstArrayObject(json.optJSONArray("entries")));
    if (candidate == null) {
      return null;
    }
    return parseLatLonCandidate(candidate, query);
  }


  void suggest(
      String baseUrl,
      String query,
      Double biasLat,
      Double biasLon,
      int limit,
      SuggestCallback callback) {
    String suggestUrl =
        baseUrl
            + "/api/platform/address-catalog/suggest?q="
            + Uri.encode(query)
            + "&limit="
            + Math.max(1, limit)
            + biasParams(biasLat, biasLon);
    Request request = new Request.Builder().url(suggestUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                callback.onFailure("suggest failed: " + e.getMessage());
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure("suggest unavailable (HTTP " + response.code() + ")");
                    return;
                  }
                  List<AddressCandidate> suggestions = parseSuggestions(response.body().string(), query);
                  callback.onSuggestions(suggestions);
                } catch (Exception e) {
                  callback.onFailure("suggest parse error: " + e.getMessage());
                }
              }
            });
  }
  private AddressCandidate parseGeocodePayload(String body, String query) throws Exception {
    JSONObject json = new JSONObject(body);
    JSONArray results = json.optJSONArray("results");
    if (results == null || results.length() == 0) {
      return null;
    }
    JSONObject first = results.optJSONObject(0);
    if (first == null) {
      return null;
    }
    return parseLatLonCandidate(first, query);
  }

  private List<AddressCandidate> parseSuggestions(String body, String fallbackName) throws Exception {
    List<AddressCandidate> out = new ArrayList<>();
    JSONObject json = new JSONObject(body);
    JSONArray results = json.optJSONArray("results");
    if (results == null) {
      return out;
    }
    for (int i = 0; i < results.length(); i++) {
      JSONObject obj = results.optJSONObject(i);
      if (obj == null) {
        continue;
      }
      AddressCandidate candidate = parseLatLonCandidate(obj, fallbackName);
      if (candidate == null) {
        continue;
      }
      out.add(candidate);
    }
    return out;
  }

  private AddressCandidate parseLatLonCandidate(JSONObject candidate, String fallbackName) {
    Double lat = asDouble(candidate.opt("lat"));
    Double lon = asDouble(candidate.opt("lon"));
    if (lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
      return null;
    }
    String displayName = candidate.optString("display_name", "").trim();
    if (displayName.isEmpty()) {
      displayName = candidate.optString("name", "").trim();
    }
    if (displayName.isEmpty()) {
      displayName = fallbackName;
    }
    return new AddressCandidate(lat, lon, displayName);
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

  private Double asDouble(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (Exception ignored) {
      return null;
    }
  }

  private void upsertCatalogEntry(
      String baseUrl, String query, AddressCandidate candidate, Double biasLat, Double biasLon) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("query", query);
      payload.put("display_name", candidate.displayName);
      payload.put("lat", candidate.lat);
      payload.put("lon", candidate.lon);
      payload.put("source", "android_stream_client");
      if (biasLat != null && biasLon != null) {
        payload.put("bias_lat", biasLat);
        payload.put("bias_lon", biasLon);
      }
      Request request =
          new Request.Builder()
              .url(baseUrl + "/api/platform/address-catalog/upsert")
              .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
              .build();
      client.newCall(request)
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  // best effort catalog warmup
                }

                @Override
                public void onResponse(Call call, Response response) {
                  response.close();
                }
              });
    } catch (Exception ignored) {
      // best effort catalog warmup
    }
  }
}
