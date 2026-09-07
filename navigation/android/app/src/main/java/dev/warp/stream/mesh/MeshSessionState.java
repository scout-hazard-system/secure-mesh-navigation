package dev.warp.stream.mesh;

/**
 * One service instance owns at most one connection attempt. GoBackend's DOWN stops that service;
 * reconnect is deliberately rejected until Android destroys it and creates a fresh instance.
 */
final class MeshSessionState {
  private enum Phase { IDLE, CONNECTING, CONNECTED, STOPPING, DESTROYED }

  private Phase phase = Phase.IDLE;
  private long generation;

  synchronized boolean isConnected() {
    return phase == Phase.CONNECTED;
  }

  synchronized boolean isStopping() {
    return phase == Phase.STOPPING || phase == Phase.DESTROYED;
  }

  synchronized long beginConnect() {
    if (phase != Phase.IDLE) {
      return 0;
    }
    phase = Phase.CONNECTING;
    return ++generation;
  }

  synchronized boolean isRequested(long attempt) {
    return attempt == generation && (phase == Phase.CONNECTING || phase == Phase.CONNECTED);
  }

  synchronized boolean connected(long attempt) {
    if (attempt != generation || phase != Phase.CONNECTING) {
      return false;
    }
    phase = Phase.CONNECTED;
    return true;
  }

  synchronized boolean beginStop() {
    if (phase == Phase.STOPPING || phase == Phase.DESTROYED) {
      return false;
    }
    phase = Phase.STOPPING;
    generation++;
    return true;
  }

  synchronized void destroy() {
    phase = Phase.DESTROYED;
    generation++;
  }
}
