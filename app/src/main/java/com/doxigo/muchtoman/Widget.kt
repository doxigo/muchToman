package com.doxigo.muchtoman

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.ceil

// The hero's own colours (Theme.kt Hero) — RemoteViews cannot read Compose values, so the
// ARGB ints live here and must not drift from that object.
private const val GOLD = 0xFFF7C948.toInt()
private const val MINT = 0xFF3BE0A8.toInt()
private const val WARN = 0xFFFFB4A6.toInt()
private const val MUTED = 0xFF93B7B0.toInt()

/**
 * One line of text as pixels, set in a real Modam instance. Rendered here because the
 * launcher, which inflates the widget's layout in its own process, cannot load font
 * resources from this APK — every fontFamily in a RemoteViews layout silently falls back
 * to the system Naskh. Pixels survive the crossing; text does not.
 *
 * drawText runs the same bidi and shaping a TextView would, and the first strong character
 * of every string here is Persian, so mixed runs («▲ ۲٫۵٪ از دیروز») come out RTL on
 * their own.
 */
private fun textBitmap(context: Context, text: String, sp: Float, @FontRes font: Int, color: Int): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, font)
        textSize = sp * context.resources.displayMetrics.scaledDensity
        fontFeatureSettings = TABULAR
        this.color = color
    }
    val fm = paint.fontMetrics
    val width = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
    // top/bottom, not ascent/descent: Persian stacks its dots and marks past the ascender.
    val height = ceil(fm.bottom - fm.top).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(text, 0f, -fm.top, paint)
    return bitmap
}

/**
 * Redraws every placed widget from what is already on disk — no network, so it is safe to
 * call from anywhere money changes. The widget shows the same total the app would, because
 * it goes through the same [listHoldings] + [computeTotals] the app goes through.
 *
 * Masking is the widget's own setting, not the app lock's: the lock guards the app on the
 * phone, and whether a passing eye may read the home screen is a separate question with its
 * own switch. Masked, it shows «٭٭٭» and keeps the change line hidden — a percent beside
 * three stars would give away most of what the stars hide.
 */
fun updateTotalWidget(context: Context) {
    val mgr = AppWidgetManager.getInstance(context)
    val ids = mgr.getAppWidgetIds(ComponentName(context, TotalWidget::class.java))
    if (ids.isEmpty()) return

    val store = Store(context)
    val total = computeTotals(
        listHoldings(store.holdings, store.smsEnabled, store.bankAccounts, store.disabledBanks),
        effectiveRates(store.cachedRates, store.overrides, store.cachedStocks),
    ).toman

    val views = RemoteViews(context.packageName, R.layout.widget_total)
    views.setImageViewBitmap(
        R.id.widget_label,
        textBitmap(context, "دارایی من", 12f, R.font.modam_medium, MUTED),
    )
    views.setOnClickPendingIntent(
        R.id.widget_root,
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    if (store.widgetLock) {
        views.setImageViewBitmap(
            R.id.widget_total,
            textBitmap(context, "٭٭٭", 19f, R.font.modam_heavy, GOLD),
        )
        views.setContentDescription(R.id.widget_total, "مبلغ پنهان است")
        views.setViewVisibility(R.id.widget_change, View.GONE)
        mgr.updateAppWidget(ids, views)
        return
    }

    val totalText = "${faCompact(total, 3, pad = true)} تومان"
    views.setImageViewBitmap(
        R.id.widget_total,
        textBitmap(context, totalText, 19f, R.font.modam_heavy, GOLD),
    )
    views.setContentDescription(R.id.widget_total, totalText)

    // Against the newest snapshot before today — changeOver's grace window is built for
    // month-wide reads and would fall back to today's own entry here, comparing the total
    // with itself.
    val today = System.currentTimeMillis() / DAY_MS
    val baseDay = store.history.keys.filter { it < today }.maxOrNull()
    val base = baseDay?.let { store.history.getValue(it) }
    if (base == null) {
        views.setViewVisibility(R.id.widget_change, View.GONE)
    } else {
        val delta = total - base
        val flat = abs(delta) < 1
        val since = if (today - baseDay == 1L) "از دیروز" else "از ${faNumber((today - baseDay).toDouble())} روز پیش"
        val figure = when {
            flat -> "بدون تغییر"
            base > 0 -> "${faDecimal(abs(delta) / base * 100, 1)}٪"
            else -> faCompact(abs(delta))
        }
        val (text, tone) = when {
            flat -> "$figure $since" to MUTED
            delta > 0 -> "▲ $figure $since" to MINT
            else -> "▼ $figure $since" to WARN
        }
        views.setViewVisibility(R.id.widget_change, View.VISIBLE)
        views.setImageViewBitmap(
            R.id.widget_change,
            textBitmap(context, text, 12f, R.font.modam_semibold, tone),
        )
        // TalkBack reads the verdict, not the triangle's unicode name.
        views.setContentDescription(
            R.id.widget_change,
            when {
                flat -> "بدون تغییر $since"
                delta > 0 -> "$figure بیشتر $since"
                else -> "$figure کمتر $since"
            },
        )
    }
    mgr.updateAppWidget(ids, views)
}

class TotalWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        updateTotalWidget(context)
}
