package dev.warp.stream.mesh;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** WireGuard peer profile issued by /api/mesh/enroll. */
public final class MeshProfile {
  public final String cidr;
  public final String clientAddress;
  public final String endpoint;
  public final String serverPublicKey;
  public final String clientPrivateKey;
  public final String clientPublicKey;
  public final List<String> allowedIps;
  public final int persistentKeepalive;
  public final String backendBaseUrl;
  public final String interfaceName;
  public final String subscriptionHeader;
  public final boolean subscriptionRequired;
  public final String deviceId;

  public MeshProfile(
      String cidr,
      String clientAddress,
      String endpoint,
      String serverPublicKey,
      String clientPrivateKey,
      String clientPublicKey,
      List<String> allowedIps,
      int persistentKeepalive,
      String backendBaseUrl,
      String interfaceName,
      String subscriptionHeader,
      boolean subscriptionRequired,
      String deviceId) {
    this.cidr = cidr;
    this.clientAddress = clientAddress;
    this.endpoint = endpoint;
    this.serverPublicKey = serverPublicKey;
    this.clientPrivateKey = clientPrivateKey;
    this.clientPublicKey = clientPublicKey;
    this.allowedIps = allowedIps == null ? List.of() : List.copyOf(allowedIps);
    this.persistentKeepalive = persistentKeepalive;
    this.backendBaseUrl = backendBaseUrl;
    this.interfaceName = interfaceName;
    this.subscriptionHeader = subscriptionHeader;
    this.subscriptionRequired = subscriptionRequired;
    this.deviceId = deviceId;
  }

  public static MeshProfile fromEnrollResponse(String json) throws Exception {
    JSONObject root = new JSONObject(json);
    if (!"ok".equalsIgnoreCase(root.optString("status", ""))) {
      throw new IllegalStateException(root.optString("error", "enroll_failed"));
    }
    JSONObject mesh = root.getJSONObject("mesh");
    JSONObject sub = root.optJSONObject("subscription");
    List<String> allowed = new ArrayList<>();
    JSONArray arr = mesh.optJSONArray("allowed_ips");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String v = arr.optString(i, "").trim();
        if (!v.isEmpty()) {
          allowed.add(v);
        }
      }
    }
    if (allowed.isEmpty()) {
      allowed.add(mesh.optString("cidr", "10.66.0.0/16"));
    }
    String priv = mesh.optString("client_private_key", "");
    if (priv == null || priv.isBlank() || "null".equalsIgnoreCase(priv)) {
      throw new IllegalStateException("missing_client_private_key");
    }
    return new MeshProfile(
        mesh.optString("cidr", "10.66.0.0/16"),
        mesh.getString("client_address"),
        mesh.getString("endpoint"),
        mesh.getString("server_public_key"),
        priv,
        mesh.optString("client_public_key", ""),
        allowed,
        mesh.optInt("persistent_keepalive", 25),
        mesh.optString("backend_base_url", "http://10.66.0.1:18080"),
        mesh.optString("interface_name", "scoutwg0"),
        sub != null ? sub.optString("header", "X-Scout-Subscription") : "X-Scout-Subscription",
        sub == null || sub.optBoolean("required", true),
        root.optString("device_id", ""));
  }

  public static MeshProfile fromStoredJson(String json) throws Exception {
    JSONObject mesh = new JSONObject(json);
    // Support both full enroll blob and mesh-only blob.
    if (mesh.has("mesh")) {
      return fromEnrollResponse(json);
    }
    List<String> allowed = new ArrayList<>();
    JSONArray arr = mesh.optJSONArray("allowed_ips");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String v = arr.optString(i, "").trim();
        if (!v.isEmpty()) {
          allowed.add(v);
        }
      }
    }
    if (allowed.isEmpty()) {
      allowed.add(mesh.optString("cidr", "10.66.0.0/16"));
    }
    return new MeshProfile(
        mesh.optString("cidr", "10.66.0.0/16"),
        mesh.getString("client_address"),
        mesh.getString("endpoint"),
        mesh.getString("server_public_key"),
        mesh.getString("client_private_key"),
        mesh.optString("client_public_key", ""),
        allowed,
        mesh.optInt("persistent_keepalive", 25),
        mesh.optString("backend_base_url", "http://10.66.0.1:18080"),
        mesh.optString("interface_name", "scoutwg0"),
        mesh.optString("subscription_header", "X-Scout-Subscription"),
        mesh.optBoolean("subscription_required", true),
        mesh.optString("device_id", ""));
  }

  public String toStorageJson() {
    try {
      JSONObject mesh = new JSONObject();
      mesh.put("cidr", cidr);
      mesh.put("client_address", clientAddress);
      mesh.put("endpoint", endpoint);
      mesh.put("server_public_key", serverPublicKey);
      mesh.put("client_private_key", clientPrivateKey);
      mesh.put("client_public_key", clientPublicKey);
      JSONArray ips = new JSONArray();
      for (String ip : allowedIps) {
        ips.put(ip);
      }
      mesh.put("allowed_ips", ips);
      mesh.put("persistent_keepalive", persistentKeepalive);
      mesh.put("backend_base_url", backendBaseUrl);
      mesh.put("interface_name", interfaceName);
      mesh.put("subscription_header", subscriptionHeader);
      mesh.put("subscription_required", subscriptionRequired);
      mesh.put("device_id", deviceId);
      return mesh.toString();
    } catch (Exception ex) {
      return "{}";
    }
  }

  /** wg-quick style conf used by the embedded tunnel backend. */
  public String toWireGuardConf() {
    StringBuilder sb = new StringBuilder();
    sb.append("[Interface]\n");
    sb.append("PrivateKey = ").append(clientPrivateKey).append('\n');
    sb.append("Address = ").append(clientAddress).append('\n');
    sb.append('\n');
    sb.append("[Peer]\n");
    sb.append("PublicKey = ").append(serverPublicKey).append('\n');
    sb.append("Endpoint = ").append(endpoint).append('\n');
    sb.append("AllowedIPs = ").append(String.join(", ", allowedIps)).append('\n');
    if (persistentKeepalive > 0) {
      sb.append("PersistentKeepalive = ").append(persistentKeepalive).append('\n');
    }
    return sb.toString();
  }

  public String clientHost() {
    if (clientAddress == null) {
      return "";
    }
    int slash = clientAddress.indexOf('/');
    return slash > 0 ? clientAddress.substring(0, slash) : clientAddress;
  }

  public int clientPrefix() {
    if (clientAddress == null) {
      return 32;
    }
    int slash = clientAddress.indexOf('/');
    if (slash < 0) {
      return 32;
    }
    try {
      return Integer.parseInt(clientAddress.substring(slash + 1));
    } catch (NumberFormatException ex) {
      return 32;
    }
  }

  public String summary() {
    return String.format(
        Locale.US,
        "mesh %s → %s (backend %s)",
        clientAddress,
        endpoint,
        backendBaseUrl);
  }
}
