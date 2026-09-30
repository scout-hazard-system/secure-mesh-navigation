package dev.warp.stream.car;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** One car session == one route map screen. */
public final class CarSession extends Session {
  private RouteMapScreen routeMapScreen;

  @NonNull
  @Override
  public Screen onCreateScreen(@NonNull Intent intent) {
    routeMapScreen = new RouteMapScreen(getCarContext());
    routeMapScreen.handleNavigationIntent(intent);
    return routeMapScreen;
  }

  @Override
  public void onNewIntent(@NonNull Intent intent) {
    if (routeMapScreen != null) {
      routeMapScreen.handleNavigationIntent(intent);
    }
  }
}
