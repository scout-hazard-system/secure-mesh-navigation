package dev.warp.stream.mesh;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.os.Build;
import android.text.TextUtils;
import dev.warp.stream.BuildConfig;
import dev.warp.stream.EndpointPolicy;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the public enrollment host (internet) to obtain a WireGuard peer
 * profile. This must work *before* the mesh tunnel is up.
 */
public final class MeshEnroller {
  public interface Listener {
    void onSuccess(MeshProfile profile);

    void onError(String message);
  }

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final OkHttpClient CLIENT =
      new OkHttpClient.Builder()
          .connectTimeout(12, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private MeshEnroller() {}

  public static String defaultDeviceId(Context context) {
    String androidId = "";
    try {
      androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    } catch (Exception ignored) {
      androidId = "";
    }
    if (TextUtils.isEmpty(androidId)) {
      androidId = "unknown";
    }
    return ("android-" + Build.MODEL + "-" + androidId)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9._-]", "_");
  }

  public static void enroll(
      Context context, String enrollBaseUrl, String entryToken, Listener listener) {
    if (!BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT) {
      MAIN.post(() -> listener.onError("Secure enrollment is not yet available in this build"));
      return;
    }
    String base = normalizeBase(enrollBaseUrl);
    if (base == null) {
      MAIN.post(() -> listener.onError("Invalid enrollment origin"));
      return;
    }
    if (TextUtils.isEmpty(entryToken)) {
      MAIN.post(() -> listener.onError("entry token required"));
      return;
    }
    String deviceId = defaultDeviceId(context);
    String payload;
    try {
      payload =
          new JSONObject()
              .put("entry_token", entryToken.trim())
              .put("device_id", deviceId)
              .put("platform", "android")
              .toString();
    } catch (Exception ex) {
      MAIN.post(() -> listener.onError("payload_error"));
      return;
    }
    Request request =
        new Request.Builder()
            .url(base + "/api/mesh/enroll")
            .post(RequestBody.create(payload, JSON))
            .build();
    CLIENT
        .newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                MAIN.post(() -> listener.onError("Enrollment network request failed"));
              }

              @Override
              public void onResponse(Call call, Response response) {
                try (response) {
                  if (!response.isSuccessful()) {
                    String message = "Enrollment failed (HTTP " + response.code() + ")";
                    MAIN.post(() -> listener.onError(message));
                    return;
                  }
                  if (response.body() == null) {
                    throw new IOException("Missing response");
                  }
                  okio.BufferedSource source = response.body().source();
                  if (source.request(65537)) {
                    throw new IOException("Response too large");
                  }
                  String body = source.readUtf8();
                  MeshProfile profile = MeshProfile.fromEnrollResponse(body);
                  MeshTunnelConfig.build(profile, context.getPackageName());
                  if (EndpointPolicy.normalize(profile.backendBaseUrl, BuildConfig.ALLOW_CLEARTEXT) == null) {
                    throw new IOException("Invalid backend origin");
                  }
                  MeshPrefs.saveProfile(context, profile);
                  MeshPrefs.saveEnrollHost(context, base);
                  MeshPrefs.saveEntryToken(context, entryToken);
                  MAIN.post(() -> listener.onSuccess(profile));
                } catch (Exception ex) {
                  MAIN.post(
                      () ->
                          listener.onError(
                              "Enrollment response was not a valid Scout Mesh profile"));
                }
              }
            });
  }

  private static String normalizeBase(String raw) {
    return EndpointPolicy.normalize(raw, BuildConfig.ALLOW_CLEARTEXT);
  }
}
