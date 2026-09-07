package dev.warp.stream

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ScrollView
import dev.warp.stream.frontendui.R

class FrontendMenuPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

  init {
    isFillViewport = true
    LayoutInflater.from(context).inflate(R.layout.view_frontend_menu_panel, this, true)
  }
}
