package dev.warp.stream.mesh;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class MeshSessionStateTest {
  @Test
  public void duplicateConnectDoesNotCreateAnotherTransportOwner() {
    MeshSessionState state = new MeshSessionState();
    long attempt = state.beginConnect();
    assertTrue(attempt > 0);
    assertEquals(0, state.beginConnect());
    assertTrue(state.connected(attempt));
    assertEquals(0, state.beginConnect());
    assertTrue(state.isRequested(attempt));
  }

  @Test
  public void disconnectDuringResolutionRejectsReconnectUntilANewServiceExists() {
    MeshSessionState state = new MeshSessionState();
    ArrayDeque<Runnable> work = new ArrayDeque<>();
    AtomicInteger nativeStarts = new AtomicInteger();
    long attempt = state.beginConnect();
    work.add(() -> {
      if (state.isRequested(attempt)) {
        nativeStarts.incrementAndGet();
      }
    });
    assertTrue(state.beginStop());
    assertEquals(0, state.beginConnect());
    work.remove().run();
    assertEquals(0, nativeStarts.get());
    assertFalse(state.connected(attempt));
    state.destroy();
    assertEquals(0, state.beginConnect());
    assertTrue(new MeshSessionState().beginConnect() > 0);
  }

  @Test
  public void backendSelfStopAndLateSuccessCannotReviveTheSession() {
    MeshSessionState state = new MeshSessionState();
    long attempt = state.beginConnect();
    assertTrue(state.connected(attempt));
    assertTrue(state.beginStop());
    // GoBackend DOWN self-stops the service before reporting DOWN.
    state.destroy();
    assertFalse(state.beginStop());
    assertFalse(state.connected(attempt));
    assertFalse(state.isRequested(attempt));
    assertEquals(0, state.beginConnect());
  }

  @Test
  public void destroyDuringStartupInvalidatesAllPendingWork() {
    MeshSessionState state = new MeshSessionState();
    long attempt = state.beginConnect();
    state.destroy();
    assertFalse(state.isRequested(attempt));
    assertFalse(state.connected(attempt));
    assertFalse(state.beginStop());
    assertEquals(0, state.beginConnect());
  }
}
