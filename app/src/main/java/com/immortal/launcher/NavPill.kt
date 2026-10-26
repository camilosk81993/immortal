/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Botones Atras e Inicio siempre a la vista sobre cualquier app. La barra del sistema del Portal
 * se esconde sola a los pocos segundos; estos botones se quedan. Se ocultan en las pantallas
 * propias de Immortal (que ya traen su flecha). Alojados como TYPE_ACCESSIBILITY_OVERLAY por
 * [BarWatchService], igual que [QuickBar].
 */
object NavPill {
  private const val TAG = "ImmortalNavPill"
  private const val PREFS = "nav_pill"
  private val main = Handler(Looper.getMainLooper())

  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var pill: View? = null
  @Volatile private var foreground: String? = null

  fun isEnabled(c: Context): Boolean =
      c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

  fun setEnabled(c: Context, on: Boolean) {
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
    applyConfig()
  }

  fun attach(service: AccessibilityService) {
    main.post {
      if (pill != null) {
        refresh()
        return@post
      }
      host = service
      val wmgr = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      wm = wmgr
      val view = build(service)
      val d = service.resources.displayMetrics.density
      val lp =
          WindowManager.LayoutParams(
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                  PixelFormat.TRANSLUCENT,
              )
              .apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (16 * d).toInt()
                y = (16 * d).toInt()
              }
      runCatching {
            wmgr.addView(view, lp)
            pill = view
          }
          .onFailure { Log.w(TAG, "addView failed", it) }
      refresh()
    }
  }

  fun detach() {
    main.post {
      pill?.let { v -> runCatching { wm?.removeView(v) } }
      pill = null
      host = null
      wm = null
    }
  }

  /** Paquete de la app que esta al frente (lo informa [BarWatchService]). */
  fun setForeground(pkg: String?) {
    foreground = pkg
    main.post { refresh() }
  }

  fun applyConfig() = main.post { refresh() }

  private fun refresh() {
    val ctx = host ?: return
    val pkg = foreground
    val show = isEnabled(ctx) && pkg != null && pkg != ctx.packageName
    pill?.visibility = if (show) View.VISIBLE else View.GONE
  }

  private fun build(service: AccessibilityService): View {
    val d = service.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun button(icon: Int, desc: String, onClick: () -> Unit): View =
        ImageView(service).apply {
          setImageResource(icon)
          contentDescription = desc
          scaleType = ImageView.ScaleType.CENTER_INSIDE
          setPadding(dp(12), dp(12), dp(12), dp(12))
          background =
              GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB3032F30L.toInt())
                setStroke(dp(1), 0x40FFFFFF)
              }
          setOnClickListener { onClick() }
        }

    return LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      alpha = 0.75f
      addView(
          button(R.drawable.ic_nav_back, "Atrás") {
            BackHelper.performBack(service) {
              service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
          },
          LinearLayout.LayoutParams(dp(50), dp(50)),
      )
      addView(
          button(R.drawable.ic_nav_home, "Inicio") { BackHelper.ensureImmortalHome(service) },
          LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(10) },
      )
    }
  }
}
