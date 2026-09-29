package dev.warp.stream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import dev.warp.stream.AppPrefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android Auto-friendly route alternatives picker for in-route UX. */
final class RouteAlternativesScreen extends PrivacyCarScreen {
  interface Listener {
    void onAlternativeSelected(CarRoutingClient.RouteAlternative alternative);
  }

  private final Listener listener;
  private final double originLat;
  private final double originLon;
  private final double destLat;
  private final double destLon;
  private final List<CarRoutingClient.RouteAlternative> alternatives = new ArrayList<>();

  private volatile boolean loading = true;
  private volatile String status = "Loading route alternatives…";

  RouteAlternativesScreen(
      @NonNull CarContext carContext,
      @NonNull CarRoutingClient routingClient,
      @NonNull Listener listener,
      double originLat,
      double originLon,
      double destLat,
      double destLon) {
    super(carContext, routingClient);
    this.listener = listener;
    this.originLat = originLat;
    this.originLon = originLon;
    this.destLat = destLat;
    this.destLon = destLon;
    loadAlternatives();
  }

  @NonNull
  @Override
  public Template onGetTemplate() {
    ItemList.Builder listBuilder = new ItemList.Builder();
    if (loading) {
      listBuilder.addItem(new Row.Builder().setTitle(status).build());
    } else if (alternatives.isEmpty()) {
      listBuilder.addItem(new Row.Builder().setTitle("No alternatives returned").build());
    } else {
      Integer preferredIndex = AppPrefs.preferredRouteAlternativeIndex(getCarContext());
      for (CarRoutingClient.RouteAlternative alternative : alternatives) {
        String title =
            "Route "
                + (alternative.index + 1)
                + (preferredIndex != null && preferredIndex == alternative.index ? " • preferred" : "");
        String etaLabel =
            alternative.etaSpeedLimitSeconds > 0.0
                ? formatDuration(alternative.etaSpeedLimitSeconds)
                : formatDuration(alternative.durationSeconds);
        String detail =
            formatDistance(alternative.distanceMeters)
                + " • "
                + etaLabel
                + " • maxspeed "
                + formatCoverage(alternative.maxspeedCoverage);
        String hints = hintsFor(alternative);
        listBuilder.addItem(
            new Row.Builder()
                .setTitle(title)
                .addText(detail)
                .addText(hints)
                .setOnClickListener(() -> selectAlternative(alternative))
                .build());
      }
    }
    int maxItems =
        getCarContext()
            .getCarService(ConstraintManager.class)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
    ItemList list = trimList(listBuilder.build(), maxItems);
    return new ListTemplate.Builder()
        .setHeaderAction(Action.BACK)
        .setTitle("Route alternatives")
        .setSingleList(list)
        .setLoading(loading)
        .build();
  }

  private void loadAlternatives() {
    loading = true;
    status = "Loading route alternatives…";
    invalidate();
    executePrivate(
        () -> {
          String baseUrl = AppPrefs.resolveReachableBaseUrl(getCarContext());
          List<CarRoutingClient.RouteAlternative> fetched =
              routingClient.fetchRouteAlternatives(baseUrl, originLat, originLon, destLat, destLon);
          synchronized (alternatives) {
            alternatives.clear();
            alternatives.addAll(fetched);
          }
          loading = false;
          status = fetched.isEmpty() ? "No alternatives returned" : "";
          postPrivate(this::invalidate);
        });
  }

  private void selectAlternative(CarRoutingClient.RouteAlternative alternative) {
    if (!isPrivacyCurrent() || alternative == null) {
      return;
    }
    AppPrefs.savePreferredRouteAlternativeIndex(getCarContext(), alternative.index);
    listener.onAlternativeSelected(alternative);
    getScreenManager().pop();
  }

  private ItemList trimList(ItemList input, int maxItems) {
    if (maxItems <= 0 || input.getItems().size() <= maxItems) {
      return input;
    }
    ItemList.Builder builder = new ItemList.Builder();
    for (int i = 0; i < maxItems; i++) {
      builder.addItem(input.getItems().get(i));
    }
    return builder.build();
  }

  private String hintsFor(CarRoutingClient.RouteAlternative alternative) {
    List<String> hints = new ArrayList<>();
    if (alternative.hasTollHint) {
      hints.add("toll hint");
    }
    if (alternative.hasFerryHint) {
      hints.add("ferry hint");
    }
    return hints.isEmpty() ? "Tap to prefer this alternative" : String.join(" • ", hints);
  }

  private String formatDistance(double meters) {
    if (!Double.isFinite(meters) || meters <= 0) {
      return "distance n/a";
    }
    double km = meters / 1000.0;
    if (km >= 10.0) {
      return String.format(Locale.US, "%.0f km", km);
    }
    return String.format(Locale.US, "%.1f km", km);
  }

  private String formatDuration(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0) {
      return "duration n/a";
    }
    long minutes = Math.max(1L, Math.round(seconds / 60.0));
    if (minutes >= 60L) {
      long hours = minutes / 60L;
      long rem = minutes % 60L;
      return rem == 0 ? (hours + "h") : (hours + "h " + rem + "m");
    }
    return minutes + "m";
  }

  private String formatCoverage(double coverage) {
    if (!Double.isFinite(coverage) || coverage <= 0.0) {
      return "fallback";
    }
    return String.format(Locale.US, "%.0f%%", Math.min(100.0, Math.max(0.0, coverage * 100.0)));
  }
}
