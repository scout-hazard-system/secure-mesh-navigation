package dev.warp.stream.mesh;

import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Keep remotely supplied profiles within the scope of the Scout-only mesh consent. */
public final class MeshTunnelConfig {
  private MeshTunnelConfig() {}

  public static Config build(MeshProfile profile, String applicationId) throws Exception {
    if (profile == null || applicationId == null
        || !applicationId.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")
        || !"10.66.0.0/16".equals(profile.cidr)
        || !isMeshNetwork(profile.clientAddress, true)
        || profile.allowedIps == null || profile.allowedIps.isEmpty()
        || profile.allowedIps.size() > 32
        || profile.persistentKeepalive < 0 || profile.persistentKeepalive > 65535) {
      throw new IllegalArgumentException("Invalid mesh profile");
    }
    Key.fromBase64(profile.clientPrivateKey);
    Key.fromBase64(profile.serverPublicKey);
    for (String route : profile.allowedIps) {
      if (!isMeshNetwork(route, false)) {
        throw new IllegalArgumentException("Route outside Scout mesh");
      }
    }
    if (profile.endpoint == null || profile.endpoint.length() > 300
        || !profile.endpoint.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
      throw new IllegalArgumentException("Invalid mesh endpoint");
    }
    byte[] config = profile.toWireGuardConf()
        .replace("[Interface]\n", "[Interface]\nIncludedApplications = " + applicationId + "\nMTU = 1280\n")
        .getBytes(StandardCharsets.UTF_8);
    try (ByteArrayInputStream input = new ByteArrayInputStream(config)) {
      return Config.parse(input);
    } finally {
      Arrays.fill(config, (byte) 0);
    }
  }

  private static boolean isMeshNetwork(String value, boolean host) {
    if (value == null || !value.matches("10\\.66\\.[0-9]{1,3}\\.[0-9]{1,3}/[0-9]{1,2}")) {
      return false;
    }
    String[] parts = value.split("[./]");
    int third = Integer.parseInt(parts[2]);
    int fourth = Integer.parseInt(parts[3]);
    int prefix = Integer.parseInt(parts[4]);
    return third <= 255 && fourth <= 255 && prefix >= 16 && prefix <= 32
        && (!host || prefix == 32);
  }
}
