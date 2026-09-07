package dev.warp.stream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import dev.warp.stream.AppPrefs;
import java.util.Locale;

/** Route status/details pane for Android Auto with refresh + destination controls. */
final class RouteOverviewScreen extends PrivacyCarScreen {
  interface Controller {
    void onSearchRequested();
    void onAlternativesRequested();

    void onDestinationCleared();
  }

  private final Controller controller;

  private volatile boolean loading = false;
  private volatile String summaryLine = "Tap refresh to load route options";
  private volatile String hazardLine = "";

  RouteOverviewScreen(
      @NonNull CarContext carContext,
      @NonNull CarRoutingClient routingClient,
      @NonNull Controller controller) {
    super(carContext, routingClient);
    this.controller = controller;
    refreshSummary();
  }

  @NonNull
  @Override
  public Template onGetTemplate() {
    Pane.Builder pane = new Pane.Builder();
    String destLabel = AppPrefs.destinationLabel(getCarContext());
    double[] dest = AppPrefs.destination(getCarContext());
    if (dest == null) {
      pane.addRow(
          new Row.Builder()
              .setTitle("No destination set")
              .addText("Use Search to choose a route target")
              .build());
      pane.addRow(
          new Row.Builder()
              .setTitle("Search destination")
              .addText("Open destination search")
              .setOnClickListener(controller::onSearchRequested)
              .build());
    } else {
      if (destLabel == null || destLabel.isBlank()) {
        destLabel = "Destination";
      }
      pane.addRow(
          new Row.Builder()
              .setTitle(destLabel)
              .addText(
                  String.format(
                      Locale.US,
                      "lat %.5f  lon %.5f",
                      dest[0],
                      dest[1]))
              .build());
      pane.addRow(new Row.Builder().setTitle(summaryLine).build());
      Integer preferredAlt = AppPrefs.preferredRouteAlternativeIndex(getCarContext());
      if (preferredAlt != null) {
        pane.addRow(
            new Row.Builder()
                .setTitle("Preferred route: " + (preferredAlt + 1))
                .addText("Selected from Android Auto alternatives")
                .build());
      }
      if (!hazardLine.isBlank()) {
        pane.addRow(new Row.Builder().setTitle(hazardLine).build());
      }
      pane.addRow(
          new Row.Builder()
              .setTitle("Route alternatives")
              .addText("Compare and prefer route variants")
              .setOnClickListener(controller::onAlternativesRequested)
              .build());
      pane.addRow(
          new Row.Builder()
              .setTitle("Search destination")
              .addText("Replace current destination")
              .setOnClickListener(controller::onSearchRequested)
              .build());
      pane.addRow(
          new Row.Builder()
              .setTitle("Refresh route summary")
              .addText("Reload alternatives and hazards")
              .setOnClickListener(this::refreshSummary)
              .build());
      pane.addRow(
          new Row.Builder()
              .setTitle("Clear destination")
              .addText("Render map without destination route")
              .setOnClickListener(controller::onDestinationCleared)
              .build());
    }
    return new PaneTemplate.Builder(pane.build())
        .setTitle("Route Overview")
        .setHeaderAction(Action.BACK)
        .build();
  }

  private void refreshSummary() {
    double[] dest = AppPrefs.destination(getCarContext());
    if (dest == null) {
      summaryLine = "No route summary: destination not set";
      hazardLine = "";
      invalidate();
      return;
    }
    invalidate();
    String baseUrl = AppPrefs.resolveReachableBaseUrl(getCarContext());
    executePrivate(
        () -> {
          double[] origin = resolveOrigin(baseUrl);
          if (origin == null) {
            summaryLine = "Route summary unavailable: missing origin fix";
            hazardLine = "";
            postPrivate(this::invalidate);
            return;
          }
          CarRoutingClient.RouteOptionsSummary summary =
              routingClient.fetchRouteOptions(baseUrl, origin[0], origin[1], dest[0], dest[1]);
          if (summary == null) {
            summaryLine = "Route summary unavailable";
            hazardLine = "";
          } else {
            summaryLine =
                "alts "
                    + summary.alternatives
                    + " • "
                    + formatDistance(summary.shortestMeters)
                    + " • "
                    + formatDuration(summary.fastestSeconds);
            String hints =
                (summary.hasTollHint ? "toll hint " : "")
                    + (summary.hasFerryHint ? "ferry hint " : "");
            String hazard =
                "hazards: "
                    + ("ok".equalsIgnoreCase(summary.hazardStatus) ? "available" : summary.hazardStatus);
            String waze =
                "waze: "
                    + ((!summary.wazeAppUrl.isBlank() && !"unknown".equalsIgnoreCase(summary.wazeRouteMode))
                        ? summary.wazeRouteMode
                        : "unavailable");
            hazardLine = (hints + hazard + " • " + waze).trim();
          }
          postPrivate(this::invalidate);
        });
  }

  private double[] resolveOrigin(String baseUrl) {
    CarRoutingClient.AddressCandidate backendLatest =
        routingClient.fetchLatestGpsOrigin(baseUrl);
    if (backendLatest == null) {
      return null;
    }
    return new double[] {backendLatest.lat, backendLatest.lon};
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
}
