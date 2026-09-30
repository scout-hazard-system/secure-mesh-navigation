package dev.warp.stream;

import static org.junit.Assert.*;
import android.content.Context;
import android.app.Service;
import dev.warp.stream.mesh.MeshPrefs;
import dev.warp.stream.mesh.ScoutMeshVpnService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class DistributionSafetyTest {
  private Context context;

  @Before
  public void resetPreferences() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("scanner_stream_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    MeshPrefs.clear(context);
  }

  @Test
  public void telemetryAndLegacyEnrollmentAreOffByDefault() {
    assertFalse(AppPrefs.isAnalyticsEnabled(context));
    assertFalse(AppPrefs.isActiveTrackingEnabled(context));
    assertFalse(AppPrefs.isTrackingConsentResolved(context));
    assertFalse(BuildConfig.ENABLE_DEV_CONTROLS);
    assertFalse(BuildConfig.ENABLE_LEGACY_MESH_ENROLLMENT);
    assertFalse(MeshPrefs.hasVpnConsent(context));
  }

  @Test
  public void serverChoiceIsNotReplacedByALabProbe() {
    AppPrefs.saveBaseUrl(context, "https://selfhost.example.org:8443/");
    assertEquals("https://selfhost.example.org:8443", AppPrefs.resolveReachableBaseUrl(context));
    AppPrefs.saveBaseUrl(context, "https://another.example.org");
    assertEquals("https://another.example.org", AppPrefs.resolveReachableBaseUrl(context));
  }

  @Test
  public void consentIsRevokedWhenTheSelectedServerChanges() {
    AppPrefs.saveBaseUrl(context, "https://first.example.org");
    AppPrefs.setTrackingConsent(context, true, true);
    AppPrefs.setAnalyticsEnabled(context, true);
    long revision = AppPrefs.privacyRevision(context);
    AppPrefs.saveBaseUrl(context, "https://first.example.org/");
    assertEquals(revision, AppPrefs.privacyRevision(context));
    assertTrue(AppPrefs.isLocationSharingAllowed(context));
    AppPrefs.saveBaseUrl(context, "https://second.example.org");
    assertTrue(AppPrefs.privacyRevision(context) > revision);
    assertFalse(AppPrefs.isLocationSharingAllowed(context));
    assertFalse(AppPrefs.isAnalyticsEnabled(context));
    assertFalse(AppPrefs.isTrackingConsentResolved(context));
  }

  @Test
  public void malformedSavedOriginFallsBackWithoutCrashing() {
    context.getSharedPreferences("scanner_stream_prefs", Context.MODE_PRIVATE)
        .edit().putString("base_url", "https://[fe80::1%25wlan0]:8443").commit();
    assertEquals(AppPrefs.DEFAULT_BASE_URL, AppPrefs.resolveReachableBaseUrl(context));
  }

  @Test
  public void entryTokenIsNotPersistedAndLegacyProfilesAreNotLoaded() {
    MeshPrefs.saveEntryToken(context, "test-only-enrollment-value");
    assertFalse(context.getSharedPreferences("scout_mesh_prefs", Context.MODE_PRIVATE).contains("entry_token"));
    assertEquals("", MeshPrefs.entryToken(context));
    context.getSharedPreferences("scout_mesh_prefs", Context.MODE_PRIVATE)
        .edit().putString("profile_json", "{}").commit();
    assertNull(MeshPrefs.loadProfile(context));
  }

  @Test
  public void serviceDoesNotPretendAnUnenrolledVpnIsConnected() {
    ServiceController<ScoutMeshVpnService> controller =
        Robolectric.buildService(ScoutMeshVpnService.class).create();
    try {
      ScoutMeshVpnService service = controller.get();
      assertEquals(Service.START_NOT_STICKY,
          service.onStartCommand(ScoutMeshVpnService.connectIntent(context), 0, 1));
      assertEquals(ScoutMeshVpnService.STATE_ERROR, ScoutMeshVpnService.currentState());
      assertFalse(ScoutMeshVpnService.isRunning());
      assertTrue(ScoutMeshVpnService.isStopping());
      service.onStartCommand(ScoutMeshVpnService.connectIntent(context), 0, 2);
      assertTrue(ScoutMeshVpnService.isStopping());
      assertFalse(MeshPrefs.isMeshEnabled(context));
    } finally {
      controller.destroy();
    }
    assertFalse(ScoutMeshVpnService.isStopping());
  }
}
