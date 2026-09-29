package dev.warp.stream.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import dev.warp.stream.BuildConfig;

/** Persistence for Scout Mesh VPN profile and connection preference. */
public final class MeshPrefs {
  private static final String PREFS = "scout_mesh_prefs";
  private static final String KEY_PROFILE_JSON = "profile_json";
  private static final String KEY_ENABLED = "mesh_enabled";
  private static final String KEY_ENROLL_HOST = "enroll_host";
  private static final String KEY_ENTRY_TOKEN = "entry_token";
  private static final String KEY_SUBSCRIPTION_TOKEN = "subscription_token";
  private static final String KEY_VPN_CONSENT_VERSION = "vpn_consent_version";

  private MeshPrefs() {}

  private static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static void saveProfile(Context context, MeshProfile profile) {
    if (!BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT) {
      throw new IllegalStateException("Legacy profile storage is disabled");
    }
    if (profile == null) {
      prefs(context).edit().remove(KEY_PROFILE_JSON).apply();
      return;
    }
    prefs(context).edit().putString(KEY_PROFILE_JSON, profile.toStorageJson()).apply();
  }

  public static MeshProfile loadProfile(Context context) {
    if (!BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT) {
      return null;
    }
    String raw = prefs(context).getString(KEY_PROFILE_JSON, null);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return MeshProfile.fromStoredJson(raw);
    } catch (Exception ex) {
      return null;
    }
  }

  public static boolean hasProfile(Context context) {
    return loadProfile(context) != null;
  }

  public static void setMeshEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
  }

  public static boolean isMeshEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ENABLED, false);
  }

  public static void saveEnrollHost(Context context, String host) {
    prefs(context).edit().putString(KEY_ENROLL_HOST, host == null ? "" : host.trim()).apply();
  }

  public static String enrollHost(Context context) {
    return prefs(context).getString(KEY_ENROLL_HOST, "");
  }

  public static void saveEntryToken(Context context, String token) {
    // An enrollment token must not remain in persistent preferences.
    prefs(context).edit().remove(KEY_ENTRY_TOKEN).apply();
  }

  public static String entryToken(Context context) {
    return "";
  }

  public static void saveSubscriptionToken(Context context, String token) {
    prefs(context)
        .edit()
        .putString(KEY_SUBSCRIPTION_TOKEN, token == null ? "" : token.trim())
        .apply();
  }

  public static String subscriptionToken(Context context) {
    return prefs(context).getString(KEY_SUBSCRIPTION_TOKEN, "");
  }
  public static boolean hasVpnConsent(Context context) {
    return prefs(context).getInt(KEY_VPN_CONSENT_VERSION, 0) == 1;
  }

  public static void setVpnConsent(Context context, boolean accepted) {
    prefs(context).edit().putInt(KEY_VPN_CONSENT_VERSION, accepted ? 1 : 0).apply();
  }

  public static void clear(Context context) {
    prefs(context).edit().clear().apply();
  }
}
