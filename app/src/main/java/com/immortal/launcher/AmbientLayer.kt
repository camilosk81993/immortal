/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Las dos pantallas de reposo del Portal, encima del marco de fotos:
 *  - **Al acercarte** (alguien cerca): tu fondo con saludo, hora y fecha abajo a la izquierda.
 *  - **Nadie cerca** (cuarto vacio un rato): solo la hora sobre negro teal, con el brillo muy bajo
 *    y desplazandose despacio para no marcar la pantalla.
 *
 * Se alimenta de [PresenceHub]. Si la presencia no se puede saber (UNKNOWN) se queda en "Al
 * acercarte". Lo alojan [PhotoDreamService] y [PhotoFramePreviewActivity] envolviendo la vista del
 * marco. [setDim] sube/baja el brillo de la ventana anfitriona.
 */
class AmbientLayer(
    private val ctx: Context,
    content: View,
    private val setDim: (Boolean) -> Unit,
) {
  val view = FrameLayout(ctx)

  private val ui = Handler(Looper.getMainLooper())
  private val density = ctx.resources.displayMetrics.density

  private val light: Typeface? = ResourcesCompat.getFont(ctx, R.font.poppins_light)
  private val regular: Typeface? = ResourcesCompat.getFont(ctx, R.font.poppins_regular)
  private val medium: Typeface? = ResourcesCompat.getFont(ctx, R.font.poppins_medium)

  private val helloGreeting = label(30f, argb(0xFF9CC9DEL), medium, shadow = true)
  private val helloTime = label(168f, argb(0xFFFFFFFFL), light, shadow = true)
  private val helloDate = label(26f, argb(0xFFBFD3DCL), regular, shadow = true)
  private val restTime = label(230f, argb(0xCC9CC8CCL), light, shadow = false)
  private val restDate = label(24f, argb(0xB36BA3BEL), regular, shadow = false)

  private val hello: View = buildHello()
  private val rest: View
  private val restBox = LinearLayout(ctx)

  private var resting = false
  private var driftX: ObjectAnimator? = null
  private var driftY: ObjectAnimator? = null

  private val enterRest = Runnable { showRest() }
  private val tick =
      object : Runnable {
        override fun run() {
          refreshTexts()
          ui.postDelayed(this, 15_000L)
        }
      }
  private val presenceListener =
      PresenceHub.Listener { st -> ui.post { onPresence(st.presence) } }

  init {
    view.addView(content, ViewGroup.LayoutParams(MATCH, MATCH))
    view.addView(hello, ViewGroup.LayoutParams(MATCH, MATCH))
    rest = buildRest()
    rest.visibility = View.GONE
    view.addView(rest, ViewGroup.LayoutParams(MATCH, MATCH))
  }

  fun start() {
    refreshTexts()
    hello.alpha = 0f
    hello.animate().alpha(1f).setDuration(600).start()
    ui.removeCallbacks(tick)
    ui.postDelayed(tick, 15_000L)
    PresenceHub.addListener(presenceListener)
  }

  fun stop() {
    PresenceHub.removeListener(presenceListener)
    ui.removeCallbacksAndMessages(null)
    driftX?.cancel()
    driftY?.cancel()
    resting = false
    setDim(false)
  }

  /** En reposo, un toque despierta (no sale del marco). Devuelve true si se atendio el toque. */
  fun consumeTouch(ev: MotionEvent): Boolean {
    if (!resting) return false
    if (ev.actionMasked == MotionEvent.ACTION_UP) wake()
    return true
  }

  // --- presencia ----------------------------------------------------------------------

  private fun onPresence(p: Presence) {
    if (p == Presence.ABSENT) {
      if (!resting) {
        ui.removeCallbacks(enterRest)
        ui.postDelayed(enterRest, REST_DELAY_MS)
      }
    } else {
      ui.removeCallbacks(enterRest)
      if (resting) wake()
    }
  }

  private fun showRest() {
    if (resting) return
    resting = true
    refreshTexts()
    rest.alpha = 0f
    rest.visibility = View.VISIBLE
    rest.animate().alpha(1f).setDuration(1500).start()
    startDrift()
    setDim(true)
  }

  private fun wake() {
    if (!resting) return
    resting = false
    driftX?.cancel()
    driftY?.cancel()
    setDim(false)
    refreshTexts()
    rest.animate().alpha(0f).setDuration(500).withEndAction { rest.visibility = View.GONE }.start()
    hello.alpha = 0f
    hello.animate().alpha(1f).setDuration(600).start()
  }

  private fun startDrift() {
    driftX =
        ObjectAnimator.ofFloat(restBox, View.TRANSLATION_X, -22f * density, 22f * density).apply {
          duration = 120_000L
          interpolator = LinearInterpolator()
          repeatMode = ValueAnimator.REVERSE
          repeatCount = ValueAnimator.INFINITE
          start()
        }
    driftY =
        ObjectAnimator.ofFloat(restBox, View.TRANSLATION_Y, -14f * density, 14f * density).apply {
          duration = 97_000L
          interpolator = LinearInterpolator()
          repeatMode = ValueAnimator.REVERSE
          repeatCount = ValueAnimator.INFINITE
          start()
        }
  }

  // --- textos -------------------------------------------------------------------------

  private fun refreshTexts() {
    val now = Date()
    val time = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
    val date = DateFormatter.format(now, "EEEEMMMMd")
    helloGreeting.text = greetingFor(now)
    helloTime.text = time
    helloDate.text = date
    restTime.text = time
    restDate.text = date
  }

  // --- vistas -------------------------------------------------------------------------

  private fun buildHello(): View {
    val root = FrameLayout(ctx)
    val bg =
        ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP
          setBackgroundColor(argb(0xFF031716L))
          runCatching {
            WallpaperConfig.loadSharp(ctx, WallpaperConfig.DEFAULT_MODE)?.let { setImageBitmap(it) }
          }
        }
    root.addView(bg, ViewGroup.LayoutParams(MATCH, MATCH))

    val veil = View(ctx)
    veil.background =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(argb(0x4D031716L), argb(0x4D031716L), argb(0x80031716L)))
    root.addView(veil, ViewGroup.LayoutParams(MATCH, MATCH))

    val side = View(ctx)
    side.background =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                argb(0xCC031716L), argb(0x59031716L), argb(0x00031716L), argb(0x00031716L)))
    root.addView(side, ViewGroup.LayoutParams(MATCH, MATCH))

    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(helloGreeting)
    col.addView(helloTime)
    col.addView(helloDate)
    root.addView(
        col,
        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
          leftMargin = dp(80)
          bottomMargin = dp(76)
        })
    return root
  }

  private fun buildRest(): View {
    val root = FrameLayout(ctx)
    root.background =
        PaintDrawable().apply {
          shape = RectShape()
          shaderFactory =
              object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader =
                    RadialGradient(
                        width * 0.5f,
                        height * 0.48f,
                        maxOf(width, height) * 0.6f,
                        intArrayOf(argb(0xFF041F20L), argb(0xFF010809L)),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP)
              }
        }
    restBox.orientation = LinearLayout.VERTICAL
    restBox.gravity = Gravity.CENTER_HORIZONTAL
    restBox.addView(restTime)
    restBox.addView(restDate)
    root.addView(restBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    root.isClickable = true
    root.setOnClickListener { wake() }
    return root
  }

  private fun label(sp: Float, color: Int, face: Typeface?, shadow: Boolean): TextView =
      TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        typeface = face ?: Typeface.DEFAULT
        if (shadow) setShadowLayer(14f, 0f, 2f, argb(0x66000000L))
      }

  private fun dp(v: Int) = (v * density).toInt()

  private fun argb(v: Long): Int = v.toInt()

  private companion object {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Cuanto tiempo sin nadie cerca antes de pasar al reloj de reposo. */
    const val REST_DELAY_MS = 90_000L
  }
}
