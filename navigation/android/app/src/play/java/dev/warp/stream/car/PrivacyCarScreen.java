package dev.warp.stream.car;

import android.os.Handler;
import android.os.Looper;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import dev.warp.stream.AppPrefs;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Child-screen origins and biases must not outlive the privacy settings they were captured under. */
abstract class PrivacyCarScreen extends Screen {
  protected final CarRoutingClient routingClient;
  private final long privacyRevision;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private volatile boolean destroyed;

  PrivacyCarScreen(CarContext context, CarRoutingClient client) {
    super(context);
    routingClient = client;
    privacyRevision = AppPrefs.privacyRevision(context);
    getLifecycle().addObserver(new DefaultLifecycleObserver() {
      @Override public void onDestroy(LifecycleOwner owner) {
        destroyed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
      }
    });
  }

  final boolean isPrivacyCurrent() {
    return !destroyed && privacyRevision == AppPrefs.privacyRevision(getCarContext());
  }

  private Runnable guarded(Runnable task) {
    return routingClient.guard(() -> {
      if (isPrivacyCurrent()) {
        task.run();
      }
    });
  }

  final void executePrivate(Runnable task) {
    if (isPrivacyCurrent()) {
      executor.submit(guarded(task));
    }
  }

  final void postPrivate(Runnable task) {
    mainHandler.post(guarded(task));
  }
}
