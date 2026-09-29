package dev.warp.stream;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class MainActivitySafetyTest {
  private Context context;
  private ActivityController<MainActivity> controller;

  @Before
  public void resetPreferences() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("scanner_stream_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    String permission = context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
    PackageManager pm = context.getPackageManager();
    assertEquals(PermissionInfo.PROTECTION_SIGNATURE,
        pm.getPermissionInfo(permission, 0).protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
    assertTrue(Arrays.asList(pm.getPackageInfo(context.getPackageName(),
        PackageManager.GET_PERMISSIONS).requestedPermissions).contains(permission));
    // Robolectric does not automatically grant the app's own signature permission on API 26.
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(permission);
  }

  @After
  public void destroyActivity() {
    if (controller != null) {
      controller.pause().stop().destroy();
    }
  }

  private MainActivity launch() {
    AppPrefs.setTrackingConsent(context, false, true);
    controller = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    return controller.get();
  }

  @Test
  public void consumerMenuIsAvailableButLabControlsAreHidden() {
    MainActivity activity = launch();
    activity.findViewById(R.id.menuBtn).performClick();
    assertEquals(View.VISIBLE, activity.findViewById(R.id.controlPanel).getVisibility());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.serverSettingsBtn).getVisibility());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.privacySettingsBtn).getVisibility());
    assertEquals(View.GONE, activity.findViewById(R.id.baseUrlInput).getVisibility());
    assertEquals(View.GONE, activity.findViewById(R.id.stackStartBtn).getVisibility());
  }

  @Test
  public void revocationClearsFixAndLateProviderCallbacksCannotRestoreIt() {
    MainActivity activity = launch();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    AppPrefs.setTrackingConsent(context, true, true);
    ReflectionHelpers.callInstanceMethod(activity, "registerLocationTracking");
    LocationListener listener = ReflectionHelpers.getField(activity, "locationListener");
    assertNotNull(listener);
    // These callbacks were abstract on Android 8–10.
    listener.onProviderEnabled(LocationManager.GPS_PROVIDER);
    listener.onProviderDisabled(LocationManager.GPS_PROVIDER);
    listener.onStatusChanged(LocationManager.GPS_PROVIDER, 2, new Bundle());
    Location fix = new Location(LocationManager.GPS_PROVIDER);
    fix.setLatitude(51.1234567);
    fix.setLongitude(-0.7654321);
    listener.onLocationChanged(fix);
    assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(activity, "hasUsableDeviceFix"));
    Bundle saved = new Bundle();
    activity.onSaveInstanceState(saved);
    assertFalse(saved.containsKey("state_device_lat"));
    assertFalse(saved.containsKey("state_device_lon"));

    AppPrefs.setTrackingConsent(context, false, true);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull(ReflectionHelpers.getField(activity, "lastDeviceLat"));
    assertNull(ReflectionHelpers.getField(activity, "locationListener"));
    assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(activity, "hasUsableDeviceFix"));
    AppPrefs.setTrackingConsent(context, true, true);
    listener.onLocationChanged(fix);
    assertNull(ReflectionHelpers.getField(activity, "lastDeviceLat"));
  }

  @Test
  public void invalidSavedOriginDoesNotCrashStartup() {
    context.getSharedPreferences("scanner_stream_prefs", Context.MODE_PRIVATE)
        .edit().putString("base_url", "https://[fe80::1%25wlan0]:8443").commit();
    MainActivity activity = launch();
    assertNotNull(activity.findViewById(R.id.map3dView));
    assertEquals(AppPrefs.DEFAULT_BASE_URL, AppPrefs.baseUrl(context));
  }

  @Test
  public void pausedAndDestroyedListenersCannotRestoreDeviceFix() {
    MainActivity activity = launch();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    AppPrefs.setTrackingConsent(context, true, true);
    ReflectionHelpers.callInstanceMethod(activity, "registerLocationTracking");
    LocationListener oldListener = ReflectionHelpers.getField(activity, "locationListener");
    assertNotNull(oldListener);
    long revision = AppPrefs.privacyRevision(context);
    Location fix = new Location(LocationManager.GPS_PROVIDER);
    fix.setLatitude(51.1234567);
    fix.setLongitude(-0.7654321);
    controller.pause();
    oldListener.onLocationChanged(fix);
    assertNull(ReflectionHelpers.getField(activity, "lastDeviceLat"));
    controller.resume();
    oldListener.onLocationChanged(fix);
    assertNull(ReflectionHelpers.getField(activity, "lastDeviceLat"));
    LocationListener latestListener = ReflectionHelpers.getField(activity, "locationListener");
    assertNotNull(latestListener);
    controller.pause().stop().destroy();
    controller = null;
    latestListener.onLocationChanged(fix);
    assertNull(ReflectionHelpers.getField(activity, "lastDeviceLat"));
    assertEquals(revision, AppPrefs.privacyRevision(context));
  }
}
