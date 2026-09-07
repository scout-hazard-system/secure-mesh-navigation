package dev.warp.stream.mesh;

import static org.junit.Assert.*;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;
import java.util.List;
import org.junit.Test;

public class MeshTunnelConfigTest {
  private MeshProfile profile(String address, String endpoint, List<String> routes) {
    KeyPair client = new KeyPair();
    KeyPair server = new KeyPair();
    return new MeshProfile("10.66.0.0/16", address, endpoint,
        server.getPublicKey().toBase64(), client.getPrivateKey().toBase64(),
        client.getPublicKey().toBase64(), routes, 25, "https://mesh.example.com",
        "scoutwg0", "X-Scout-Subscription", true, "test-device");
  }

  @Test
  public void confinesTunnelToScoutAndMeshRoutes() throws Exception {
    Config config = MeshTunnelConfig.build(
        profile("10.66.0.2/32", "127.0.0.1:51820", List.of("10.66.0.0/16")),
        "io.github.scout_hazard_system.securemesh.foss.preview");
    assertEquals(1, config.getInterface().getIncludedApplications().size());
    assertTrue(config.getInterface().getIncludedApplications()
        .contains("io.github.scout_hazard_system.securemesh.foss.preview"));
    assertEquals(1280, (int) config.getInterface().getMtu().get());
    assertEquals(1, config.getPeers().size());
  }

  @Test
  public void rejectsDefaultRoutesAndOtherNetworks() {
    for (String route : List.of("0.0.0.0/0", "::/0", "10.0.0.0/8", "192.168.1.0/24",
        "10.66.0.0/15", "10.66.999.0/24", "10.66.0.0/16\nAllowedIPs = 0.0.0.0/0")) {
      assertThrows(Exception.class, () -> MeshTunnelConfig.build(
          profile("10.66.0.2/32", "127.0.0.1:51820", List.of(route)), "test.scout"));
    }
  }

  @Test
  public void rejectsConfigInjectionAndOutOfMeshAddress() {
    assertThrows(Exception.class, () -> MeshTunnelConfig.build(
        profile("192.168.0.2/32", "127.0.0.1:51820", List.of("10.66.0.0/16")), "test.scout"));
    assertThrows(Exception.class, () -> MeshTunnelConfig.build(
        profile("10.66.0.2/32", "mesh.example.com:51820\n[Peer]", List.of("10.66.0.0/16")), "test.scout"));
    assertThrows(Exception.class, () -> MeshTunnelConfig.build(
        profile("10.66.0.2/32", "127.0.0.1:51820", List.of("10.66.0.0/16")), "test.scout\nDNS = 1.1.1.1"));
  }
}
