package dev.warp.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import okhttp3.Request;

public class EndpointPolicyTest {
  @Test
  public void normalizesHttpsOrigins() {
    assertEquals("https://mesh.example.com:8443",
        EndpointPolicy.normalize(" https://Mesh.Example.com:8443/ ", false));
    assertEquals("https://[::1]:8443", EndpointPolicy.normalize("https://[::1]:8443", false));
  }

  @Test
  public void permitsHttpOnlyWithExplicitLabFlag() {
    assertNull(EndpointPolicy.normalize("http://10.66.0.1:18080", false));
    assertEquals("http://10.0.2.2:18080", EndpointPolicy.normalize("http://10.0.2.2:18080/", true));
  }

  @Test
  public void rejectsCredentialOrPathBearingUrls() {
    String[] invalid = {
        "https://user:password@mesh.example.com", "https://mesh.example.com?token=x",
        "https://mesh.example.com/#fragment", "https://mesh.example.com/path",
        "https://mesh.example.com:0", "https://mesh.example.com:65536",
        "file:///tmp/backend", "ftp://mesh.example.com", "//mesh.example.com",
        "https://", "https://mesh.example.com\nInjected: header", "",
        "https://[fe80::1%25wlan0]:8443", "https://[fe80::1%wlan0]:8443"
    };
    for (String candidate : invalid) {
      assertNull(candidate, EndpointPolicy.normalize(candidate, true));
    }
    assertNull(EndpointPolicy.normalize(null, false));
  }

  @Test
  public void everyAcceptedOriginIsTransportCompatibleAndCanonical() {
    for (String value : new String[] {"https://[::1]:8443", "https://EXAMPLE.org:443/",
        "https://192.0.2.1:8443", "https://mesh.example.org", "http://127.0.0.1:8080"}) {
      String normalized = EndpointPolicy.normalize(value, true);
      Request request = new Request.Builder().url(normalized + "/api/health").build();
      assertEquals(normalized, request.url().resolve("/").toString().replaceAll("/$", ""));
    }
  }
}
