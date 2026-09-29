package dev.warp.stream;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class PrivacyHttpClientTest {
  private Context context;
  private PrivacyHttpClient client;
  private final List<String> traffic = Collections.synchronizedList(new ArrayList<>());
  private final CountDownLatch idle = new CountDownLatch(1);
  private final AtomicInteger callbacks = new AtomicInteger();
  private final Callback callback = new Callback() {
    @Override public void onFailure(Call call, IOException error) { callbacks.incrementAndGet(); }
    @Override public void onResponse(Call call, Response response) {
      callbacks.incrementAndGet();
      response.close();
    }
  };

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("scanner_stream_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    AppPrefs.setTrackingConsent(context, true, true);
  }

  @After public void tearDown() throws Exception {
    if (client != null) {
      client.close();
      client.dispatcher().executorService().shutdown();
      assertTrue(client.dispatcher().executorService().awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private void transport(Interceptor responder) {
    client = new PrivacyHttpClient(context, new OkHttpClient.Builder().addInterceptor(chain -> {
      traffic.add(chain.request().url().encodedPath());
      return responder.intercept(chain);
    }).build());
    client.dispatcher().setIdleCallback(idle::countDown);
  }

  private Response response(Interceptor.Chain chain, String body) {
    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK")
        .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
  }

  private Request request(String path) {
    return new Request.Builder().url("https://example.invalid/" + path).build();
  }

  private void revoke() { AppPrefs.setTrackingConsent(context, false, true); }

  private void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IOException("Test latch timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private AddressCatalogRouter.ResolveCallback resolved(Runnable action) {
    return new AddressCatalogRouter.ResolveCallback() {
      @Override public void onResolved(AddressCatalogRouter.AddressCandidate candidate, boolean fromCatalog) {
        callbacks.incrementAndGet();
        action.run();
      }
      @Override public void onFailure(String message) { callbacks.incrementAndGet(); }
    };
  }

  private void resolve(Runnable onResolved) {
    new AddressCatalogRouter(client).resolve("https://example.invalid", "Test address",
        51.123456, -0.765432, resolved(onResolved));
  }

  @Test public void ordinaryFailureStillFallsBackAndUpserts() throws Exception {
    transport(chain -> {
      if (chain.request().url().encodedPath().endsWith("/resolve")) {
        throw new IOException("Synthetic failure");
      }
      return response(chain, "{\"results\":[{\"lat\":51.1,\"lon\":-0.7}]}");
    });
    resolve(() -> {});
    await(idle);
    assertEquals(3, traffic.size());
    assertEquals(1, callbacks.get());
    assertTrue(traffic.get(2).endsWith("/upsert"));
  }

  @Test public void revokeDuringFailureCannotLaunchGeocodeFallback() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    transport(chain -> {
      entered.countDown();
      await(release);
      throw new IOException("Synthetic cancelled failure");
    });
    resolve(() -> {});
    await(entered);
    revoke();
    client.dispatcher().cancelAll();
    release.countDown();
    await(idle);
    assertEquals(1, traffic.size());
    assertEquals(0, callbacks.get());
  }

  @Test public void revokeInsideResolvedCallbackPreventsCatalogUpsert() throws Exception {
    transport(chain -> response(chain, "{\"results\":[{\"lat\":51.1,\"lon\":-0.7}]}"));
    // Empty catalog response forces the geocoder before delivering a result.
    client.close();
    client.dispatcher().executorService().shutdown();
    transport(chain -> response(chain, chain.request().url().encodedPath().endsWith("/resolve")
        ? "{}" : "{\"results\":[{\"lat\":51.1,\"lon\":-0.7}]}"));
    resolve(this::revoke);
    await(idle);
    assertEquals(2, traffic.size());
    assertEquals(1, callbacks.get());
  }

  @Test public void queuedRequestsAreRejectedBeforeTransport() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    transport(chain -> {
      entered.countDown();
      await(release);
      return response(chain, "{}");
    });
    client.dispatcher().setMaxRequests(1);
    client.newCall(request("first")).enqueue(callback);
    await(entered);
    client.newCall(request("queued-with-old-coordinates")).enqueue(callback);
    revoke();
    release.countDown();
    await(idle);
    assertEquals(List.of("/first"), traffic);
    assertEquals(0, callbacks.get());
  }

  @Test public void cancelledCallbackCannotStartNestedRequest() throws Exception {
    transport(chain -> response(chain, "{}"));
    client.newCall(request("first")).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException error) { fail("Unexpected failure"); }
      @Override public void onResponse(Call call, Response response) {
        response.close();
        call.cancel();
        client.newCall(request("nested")).enqueue(callback);
      }
    });
    await(idle);
    assertEquals(List.of("/first"), traffic);
    assertEquals(0, callbacks.get());
  }

  @Test public void callbackScopeSurvivesUiHandoff() throws Exception {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    transport(chain -> response(chain, "{}"));
    client.newCall(request("first")).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException error) { fail("Unexpected failure"); }
      @Override public void onResponse(Call call, Response response) {
        response.close();
        queued.set(client.guard(() -> client.newCall(request("ui-fallback")).enqueue(callback)));
      }
    });
    await(idle);
    assertNotNull(queued.get());
    revoke();
    queued.get().run();
    assertEquals(List.of("/first"), traffic);
  }

  @Test public void synchronousScopeAndCloneCannotAdoptNewConsent() throws Exception {
    transport(chain -> response(chain, "{}"));
    Call original;
    try (PrivacyHttpClient.Scope ignored = client.scope()) {
      original = client.newCall(request("first"));
      try (Response response = original.execute()) { assertTrue(response.isSuccessful()); }
      revoke();
      assertThrows(IOException.class, () -> client.newCall(request("fallback")).execute());
      assertThrows(IOException.class, () -> original.clone().execute());
    }
    try (Response fresh = client.newCall(request("fresh-manual-request")).execute()) {
      assertTrue(fresh.isSuccessful());
    }
    assertEquals(List.of("/first", "/fresh-manual-request"), traffic);
  }

  @Test public void originChangeInvalidatesQueuedWorkEvenWithoutLocationConsent() {
    transport(chain -> response(chain, "{}"));
    revoke();
    Runnable queued = client.guard(callbacks::incrementAndGet);
    AppPrefs.saveBaseUrl(context, "https://new.example.invalid");
    queued.run();
    assertEquals(0, callbacks.get());
  }

  @Test public void destroyedClientCannotRestartFromQueuedWork() throws Exception {
    transport(chain -> response(chain, "{}"));
    Runnable queued = client.guard(callbacks::incrementAndGet);
    client.close();
    queued.run();
    assertThrows(IOException.class, () -> client.newCall(request("after-destroy")).execute());
    assertEquals(0, callbacks.get());
    assertTrue(traffic.isEmpty());
  }
}
