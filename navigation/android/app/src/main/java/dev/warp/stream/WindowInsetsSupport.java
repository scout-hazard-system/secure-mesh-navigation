package dev.warp.stream;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Keep existing Views clear of system bars, cutouts, and the keyboard on API 36. */
final class WindowInsetsSupport {
  private WindowInsetsSupport() {}

  static void apply(Activity activity) {
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    int left = root.getPaddingLeft();
    int top = root.getPaddingTop();
    int right = root.getPaddingRight();
    int bottom = root.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
      Insets insets = windowInsets.getInsets(
          WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
              | WindowInsetsCompat.Type.ime());
      view.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom + insets.bottom);
      return windowInsets;
    });
    ViewCompat.requestApplyInsets(root);
  }
}
