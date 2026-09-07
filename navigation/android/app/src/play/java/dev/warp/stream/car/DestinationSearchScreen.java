package dev.warp.stream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.SearchTemplate;
import dev.warp.stream.AppPrefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Search UI for setting a route destination directly from Android Auto. */
final class DestinationSearchScreen extends PrivacyCarScreen implements SearchTemplate.SearchCallback {
  interface Listener {
    void onDestinationSelected(CarRoutingClient.AddressCandidate candidate);

    void onDestinationCleared();
  }

  private final Listener listener;
  private final AtomicLong searchToken = new AtomicLong(0L);
  private final Double biasLat;
  private final Double biasLon;
  private final List<CarRoutingClient.AddressCandidate> suggestions = new ArrayList<>();

  private volatile boolean loading = false;
  private volatile String currentQuery = "";
  private volatile String statusText = "Type at least 3 characters";

  DestinationSearchScreen(
      @NonNull CarContext carContext,
      @NonNull CarRoutingClient routingClient,
      @NonNull Listener listener,
      Double biasLat,
      Double biasLon) {
    super(carContext, routingClient);
    this.listener = listener;
    this.biasLat = biasLat;
    this.biasLon = biasLon;
  }

  @Override
  public void onSearchSubmitted(@NonNull String searchText) {
    runSearch(searchText);
  }

  @Override
  public void onSearchTextChanged(@NonNull String searchText) {
    runSearch(searchText);
  }

  @NonNull
  @Override
  public Template onGetTemplate() {
    ItemList.Builder listBuilder = new ItemList.Builder();
    if (currentQuery.isBlank()) {
      listBuilder.addItem(new Row.Builder().setTitle(statusText).build());
    } else if (!loading && suggestions.isEmpty()) {
      listBuilder.addItem(new Row.Builder().setTitle("No destination matches").build());
    } else {
      for (CarRoutingClient.AddressCandidate suggestion : suggestions) {
        String source = suggestion.fromCatalog ? "catalog" : "osm-fallback";
        String coord =
            String.format(
                Locale.US,
                "%s  •  %.5f, %.5f",
                source,
                suggestion.lat,
                suggestion.lon);
        listBuilder.addItem(
            new Row.Builder()
                .setTitle(suggestion.displayName)
                .addText(coord)
                .setOnClickListener(() -> selectSuggestion(suggestion))
                .build());
      }
    }

    if (AppPrefs.destination(getCarContext()) != null) {
      listBuilder.addItem(
          new Row.Builder()
              .setTitle("Clear current destination")
              .addText("Render map without route target")
              .setOnClickListener(
                  () -> {
                    listener.onDestinationCleared();
                    getScreenManager().pop();
                  })
              .build());
    }

    int maxItems =
        getCarContext()
            .getCarService(ConstraintManager.class)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
    ItemList list = listBuilder.build();
    SearchTemplate.Builder builder =
        new SearchTemplate.Builder(this)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search destination")
            .setLoading(loading)
            .setItemList(trimList(list, maxItems));
    return builder.build();
  }

  private void runSearch(String rawQuery) {
    String query = rawQuery == null ? "" : rawQuery.trim();
    currentQuery = query;
    if (query.length() < 3) {
      loading = false;
      synchronized (suggestions) {
        suggestions.clear();
      }
      statusText = "Type at least 3 characters";
      invalidate();
      return;
    }
    loading = true;
    statusText = "Searching...";
    invalidate();
    long token = searchToken.incrementAndGet();
    String baseUrl = AppPrefs.resolveReachableBaseUrl(getCarContext());
    executePrivate(
        () -> {
          List<CarRoutingClient.AddressCandidate> results =
              routingClient.searchDestinations(baseUrl, query, biasLat, biasLon);
          if (searchToken.get() != token) {
            return;
          }
          loading = false;
          synchronized (suggestions) {
            suggestions.clear();
            suggestions.addAll(results);
          }
          statusText = results.isEmpty() ? "No destination matches" : "";
          postPrivate(this::invalidate);
        });
  }

  private void selectSuggestion(CarRoutingClient.AddressCandidate suggestion) {
    if (!isPrivacyCurrent() || suggestion == null) {
      return;
    }
    listener.onDestinationSelected(suggestion);
    if (!suggestion.fromCatalog && !currentQuery.isBlank()) {
      String baseUrl = AppPrefs.resolveReachableBaseUrl(getCarContext());
      executePrivate(
          () -> routingClient.upsertCatalog(baseUrl, currentQuery, suggestion, biasLat, biasLon));
    }
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
}
