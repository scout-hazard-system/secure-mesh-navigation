package dev.warp.stream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import dev.warp.stream.BuildConfig;

/** Entry point for the Android Auto host; exposes the scanner routing map. */
public final class StreamCarAppService extends CarAppService {

  @NonNull
  @Override
  public HostValidator createHostValidator() {
    if (BuildConfig.DEBUG) {
      return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }
    return new HostValidator.Builder(getApplicationContext())
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        .build();
  }

  @NonNull
  @Override
  public Session onCreateSession() {
    return new CarSession();
  }
}
