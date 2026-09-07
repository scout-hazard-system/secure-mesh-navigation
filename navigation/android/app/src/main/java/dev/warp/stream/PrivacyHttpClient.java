package dev.warp.stream;

import android.content.Context;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;

/**
 * Cancels a logical request chain when consent/server settings change. Callback-created retries
 * inherit the original revision instead of adopting a new scope for previously captured data.
 * This is a privacy boundary, not authentication or payment authorization.
 */
public final class PrivacyHttpClient implements Call.Factory {
  private static final ThreadLocal<Revision> CALLBACK_REVISION = new ThreadLocal<>();
  private final Context context;
  private final OkHttpClient client;
  private volatile boolean closed;

  private static final class Revision {
    final long value;
    final BooleanSupplier cancelled;
    Revision(long value, BooleanSupplier cancelled) {
      this.value = value;
      this.cancelled = cancelled;
    }
  }

  public PrivacyHttpClient(Context context, OkHttpClient transport) {
    this.context = context;
    OkHttpClient.Builder builder = transport.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false);
    Interceptor fence = chain -> {
      Revision revision = chain.request().tag(Revision.class);
      if (revision == null || !current(revision) || chain.call().isCanceled()) {
        throw new IOException("Request privacy scope expired");
      }
      return chain.proceed(chain.request());
    };
    builder.interceptors().add(0, fence);
    builder.addNetworkInterceptor(fence);
    client = builder.build();
  }

  public Dispatcher dispatcher() { return client.dispatcher(); }
  public void close() {
    closed = true;
    client.dispatcher().cancelAll();
  }

  private boolean current(Revision revision) {
    return !closed && !revision.cancelled.getAsBoolean()
        && revision.value == AppPrefs.privacyRevision(context);
  }

  private Revision capture() {
    Revision inherited = CALLBACK_REVISION.get();
    return inherited != null ? inherited
        : new Revision(AppPrefs.privacyRevision(context), () -> false);
  }

  /** Capture before queuing work; the returned runnable also preserves scope for nested requests. */
  public Runnable guard(Runnable task) {
    Revision revision = capture();
    return () -> {
      if (current(revision)) {
        try (Scope ignored = new Scope(revision)) {
          task.run();
        }
      }
    };
  }

  /** Use around a synchronous multi-request operation, before reading a location fix. */
  public Scope scope() {
    return new Scope(capture());
  }

  public static final class Scope implements AutoCloseable {
    private final Revision previous = CALLBACK_REVISION.get();
    private Scope(Revision revision) { CALLBACK_REVISION.set(revision); }
    @Override public void close() {
      if (previous == null) {
        CALLBACK_REVISION.remove();
      } else {
        CALLBACK_REVISION.set(previous);
      }
    }
  }

  @Override
  public Call newCall(Request request) {
    Revision revision = capture();
    Request scoped = request.newBuilder().tag(Revision.class, revision).build();
    return new ScopedCall(client.newCall(scoped), revision);
  }

  private final class ScopedCall implements Call {
    private final Call delegate;
    private final Revision revision;
    private ScopedCall(Call delegate, Revision revision) {
      this.delegate = delegate;
      this.revision = revision;
    }
    private boolean current() {
      return !delegate.isCanceled() && PrivacyHttpClient.this.current(revision);
    }
    private Scope callbackScope() {
      return new Scope(new Revision(revision.value, () -> !current()));
    }
    @Override public Request request() { return delegate.request(); }
    @Override public void cancel() { delegate.cancel(); }
    @Override public boolean isExecuted() { return delegate.isExecuted(); }
    @Override public boolean isCanceled() { return !current(); }
    @Override public Timeout timeout() { return delegate.timeout(); }
    @Override public Call clone() { return new ScopedCall(delegate.clone(), revision); }

    @Override
    public Response execute() throws IOException {
      if (!current()) {
        throw new IOException("Request privacy scope expired");
      }
      Response response = delegate.execute();
      if (!current()) {
        response.close();
        throw new IOException("Request privacy scope expired");
      }
      return response;
    }

    @Override
    public void enqueue(Callback callback) {
      delegate.enqueue(new Callback() {
        @Override public void onFailure(Call call, IOException error) {
          if (current()) {
            try (Scope ignored = callbackScope()) {
              callback.onFailure(ScopedCall.this, error);
            }
          }
        }
        @Override public void onResponse(Call call, Response response) throws IOException {
          if (!current()) {
            response.close();
            return;
          }
          try (Scope ignored = callbackScope()) {
            callback.onResponse(ScopedCall.this, response);
          }
        }
      });
    }
  }
}
