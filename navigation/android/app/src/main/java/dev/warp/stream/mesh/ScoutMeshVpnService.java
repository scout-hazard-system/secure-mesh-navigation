package dev.warp.stream.mesh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.Peer;
import dev.warp.stream.BuildConfig;
import dev.warp.stream.MainActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single, real WireGuard VPN service. GoBackend owns the only TUN and its encrypted transport;
 * no second TUN, reflective backend, or packet-dropping "connected" fallback is permitted.
 */
public final class ScoutMeshVpnService extends GoBackend.VpnService {
  public static final String ACTION_CONNECT = "dev.warp.stream.mesh.CONNECT";
  public static final String ACTION_DISCONNECT = "dev.warp.stream.mesh.DISCONNECT";
  public static final String ACTION_STATE = "dev.warp.stream.mesh.STATE";
  public static final String EXTRA_STATE = "state";
  public static final String EXTRA_DETAIL = "detail";
  public static final String STATE_DISCONNECTED = "disconnected";
  public static final String STATE_CONNECTING = "connecting";
  public static final String STATE_DISCONNECTING = "disconnecting";
  public static final String STATE_CONNECTED = "connected";
  public static final String STATE_ERROR = "error";

  private static final String CHANNEL_ID = "scout_mesh_vpn";
  private static final int NOTIFICATION_ID = 66;
  private static volatile String currentState = STATE_DISCONNECTED;
  private static volatile MeshSessionState currentSession;

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Object backendLock = new Object();
  private final MeshSessionState session = new MeshSessionState();
  private volatile boolean destroyed;
  private GoBackend backend;
  private final Tunnel tunnel = new Tunnel() {
    @Override
    public String getName() {
      return "scout-mesh";
    }

    @Override
    public void onStateChange(State state) {
      if (destroyed) {
        return;
      }
      if (state == State.DOWN) {
        if (session.beginStop()) {
          main.post(() -> finishConnection(STATE_DISCONNECTING, "VPN stopped by Android"));
        }
      }
    }
  };

  public static boolean isRunning() {
    MeshSessionState owner = currentSession;
    return owner != null && owner.isConnected();
  }

  public static String currentState() {
    return currentState;
  }

  public static boolean isStopping() {
    MeshSessionState owner = currentSession;
    return owner != null && owner.isStopping();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    currentSession = session;
    currentState = STATE_DISCONNECTED;
  }

  public static Intent connectIntent(Context context) {
    return new Intent(context, ScoutMeshVpnService.class).setAction(ACTION_CONNECT);
  }

  public static Intent disconnectIntent(Context context) {
    return new Intent(context, ScoutMeshVpnService.class).setAction(ACTION_DISCONNECT);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    super.onStartCommand(intent, flags, startId);
    if (destroyed || session.isStopping()) {
      return START_NOT_STICKY;
    }
    if (intent == null || !ACTION_CONNECT.equals(intent.getAction())) {
      disconnect("Disconnected");
      return START_NOT_STICKY;
    }
    // Do not restore legacy plaintext identities or bypass the pending secure-enrollment flow.
    if (!BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT || !MeshPrefs.hasVpnConsent(this)) {
      session.beginStop();
      finishConnection(STATE_ERROR, "Secure enrollment is not yet available in this build");
      return START_NOT_STICKY;
    }
    long attempt = session.beginConnect();
    if (attempt == 0) {
      return START_NOT_STICKY;
    }
    try {
      ensureChannel();
      ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Connecting Scout Mesh…"),
          Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    } catch (RuntimeException denied) {
      session.beginStop();
      finishConnection(STATE_ERROR, "Android did not allow the VPN foreground service to start");
      return START_NOT_STICKY;
    }
    publishState(STATE_CONNECTING, "Starting encrypted tunnel");
    worker.execute(() -> connect(attempt));
    // Restart/reboot must require a new user-initiated, unlocked session.
    return START_NOT_STICKY;
  }

  private void connect(long attempt) {
    try {
      if (!session.isRequested(attempt)) {
        return;
      }
      MeshProfile profile = MeshPrefs.loadProfile(this);
      if (profile == null) {
        throw new IllegalStateException("No mesh profile");
      }
      Config config = MeshTunnelConfig.build(profile, getPackageName());
      // Resolve outside the native-operation lock, so Android teardown cannot wait on DNS retries.
      for (Peer peer : config.getPeers()) {
        if (peer.getEndpoint().isEmpty()
            || peer.getEndpoint().get().getResolved().isEmpty()) {
          throw new IllegalStateException("Mesh endpoint unavailable");
        }
      }
      synchronized (backendLock) {
        if (!session.isRequested(attempt) || destroyed) {
          return;
        }
        backend = new GoBackend(getApplicationContext());
        if (backend.setState(tunnel, Tunnel.State.UP, config) != Tunnel.State.UP) {
          throw new IllegalStateException("WireGuard did not start");
        }
        if (!session.isRequested(attempt)) {
          backend.setState(tunnel, Tunnel.State.DOWN, null);
          return;
        }
      }
      main.post(() -> {
        if (destroyed || !session.connected(attempt)) {
          return;
        }
        MeshPrefs.setMeshEnabled(this, true);
        getSystemService(NotificationManager.class)
            .notify(NOTIFICATION_ID, buildNotification("Scout Mesh tunnel active"));
        publishState(STATE_CONNECTED, "Encrypted tunnel active; hub reachability is not yet verified");
      });
    } catch (Exception | LinkageError failure) {
      boolean reportFailure = session.beginStop();
      closeTransport();
      // Never expose a configuration, key, server response, or exception text in logs/notifications.
      if (reportFailure) {
        main.post(() -> finishConnection(STATE_ERROR, "WireGuard could not start; no tunnel is active"));
      }
    }
  }

  private void disconnect(String detail) {
    if (!session.beginStop() || worker.isShutdown()) {
      return;
    }
    publishState(STATE_DISCONNECTING, "Disconnecting; wait before reconnecting");
    worker.execute(() -> {
      closeTransport();
      main.post(() -> finishConnection(STATE_DISCONNECTING, detail));
    });
  }

  private void closeTransport() {
    synchronized (backendLock) {
      if (backend != null) {
        try {
          // GoBackend identifies tunnels by object identity; use the exact original object.
          backend.setState(tunnel, Tunnel.State.DOWN, null);
        } catch (Exception ignored) {
          // The superclass onDestroy still owns the final native-handle cleanup.
        }
      }
    }
  }

  private void finishConnection(String state, String detail) {
    if (destroyed) {
      return;
    }
    MeshPrefs.setMeshEnabled(this, false);
    publishState(state, detail);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  private void publishState(String state, String detail) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post(() -> publishState(state, detail));
      return;
    }
    if (currentSession != session) {
      return;
    }
    currentState = state;
    sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
        .putExtra(EXTRA_STATE, state).putExtra(EXTRA_DETAIL, detail));
  }

  private void ensureChannel() {
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID, "Scout Mesh VPN", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Encrypted remote access to your selected Scout hub");
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  private Notification buildNotification(String content) {
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent disconnect = PendingIntent.getService(this, 1, disconnectIntent(this),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Scout Mesh")
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentIntent(open)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnect)
        .setOngoing(true)
        .build();
  }

  @Override
  public void onRevoke() {
    main.post(() -> disconnect("VPN permission revoked"));
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    session.destroy();
    worker.shutdownNow();
    // Serialize the superclass's last-resort native teardown with startup. No DNS runs in this lock.
    synchronized (backendLock) {
      super.onDestroy();
      backend = null;
    }
    MeshPrefs.setMeshEnabled(this, false);
    main.removeCallbacksAndMessages(null);
    if (!STATE_ERROR.equals(currentState)) {
      publishState(STATE_DISCONNECTED, "Disconnected");
    } else {
      publishState(STATE_ERROR, "No tunnel is active");
    }
    if (currentSession == session) {
      currentSession = null;
    }
  }
}
