package dev.warp.stream;

import java.net.URI;
import java.util.Locale;
import okhttp3.HttpUrl;

/** Backend origins never contain credentials, queries, fragments, or arbitrary path prefixes. */
public final class EndpointPolicy {
  private EndpointPolicy() {}

  public static String normalize(String raw, boolean allowCleartext) {
    if (raw == null || raw.isBlank() || raw.length() > 2048) {
      return null;
    }
    try {
      URI uri = new URI(raw.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!"https".equals(scheme) && !(allowCleartext && "http".equals(scheme))) {
        return null;
      }
      if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
          || uri.getPort() == 0 || uri.getPort() > 65535) {
        return null;
      }
      String origin = new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT),
          uri.getPort(), null, null, null).toASCIIString();
      HttpUrl transportUrl = HttpUrl.parse(origin);
      if (transportUrl == null) {
        return null;
      }
      String canonical = transportUrl.toString();
      return canonical.substring(0, canonical.length() - 1);
    } catch (Exception invalid) {
      return null;
    }
  }
}
