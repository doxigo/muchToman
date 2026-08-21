package com.doxigo.muchtoman

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.FontRes
import androidx.annotation.LayoutRes
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// The hero's own colours (Theme.kt Hero) — RemoteViews cannot read Compose values, so the
// ARGB ints live here and must not drift from that object.
private const val GOLD = 0xFF9FE870.toInt()
private const val MINT = 0xFF9FE870.toInt()
private const val WARN = 0xFFFFB59F.toInt()
private const val MUTED = 0xFFA9C295.toInt()

private fun spToPx(context: Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** The same colour at [a]/255 opacity. */
private fun fade(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

/**
 * Which way the money went, as a colour. Below one Toman is no movement at all — a total
 * built from live rates never lands on the same figure twice, and a half-Rial drift is not
 * news worth colouring.
 */
private fun tone(delta: Double): Int = when {
    abs(delta) < 1 -> MUTED
    delta > 0 -> MINT
    else -> WARN
}

/**
 * How much tile there is to spend, and what to spend it on. The thresholds are in dp rather
 * than cells because a cell is not a fixed size: the same "two by one" is 110×40dp on the
 * grid the platform documents and closer to 160×100dp on a Pixel. What actually decides the
 * layout is whether there is room for a second column, and then for a chart.
 *
 * Every step up adds a line and never takes one away — which was the whole complaint about
 * the single layout this replaced, where a wider tile showed *less* than a narrow one.
 */
internal enum class Face(
    @LayoutRes val layout: Int,
    val label: Float,
    val total: Float,
    val pill: Float,
    val ago: Float,
) {
    /** Label, total, change. Stacked, because there is no second column. */
    COMPACT(R.layout.widget_total, 11f, 21f, 10f, 0f),

    /** …plus how fresh the rates are, in a column of its own. */
    WIDE(R.layout.widget_total_wide, 11f, 27f, 11f, 10f),

    /** …plus a month of chart. */
    TALL(R.layout.widget_total_tall, 12f, 34f, 12f, 11f);

    companion object {
        /**
         * A host that has not measured the widget yet reports nothing. WIDE is the safe guess
         * between the two extremes, and onAppWidgetOptionsChanged corrects it the moment the
         * launcher does measure.
         */
        fun of(widthDp: Int, heightDp: Int): Face = when {
            widthDp <= 0 || heightDp <= 0 -> WIDE
            heightDp >= 140 -> TALL
            widthDp >= 220 -> WIDE
            else -> COMPACT
        }
    }
}

/** The portrait pair out of a host's options bundle. */
private fun Bundle?.widthDp(): Int = this?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
private fun Bundle?.heightDp(): Int = this?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0

/**
 * One line of text as pixels, set in a real Modam instance. Rendered here because the
 * launcher, which inflates the widget's layout in its own process, cannot load font
 * resources from this APK — every fontFamily in a RemoteViews layout silently falls back
 * to the system Naskh. Pixels survive the crossing; text does not.
 *
 * drawText runs the same bidi and shaping a TextView would, and the first strong character
 * of every string here is Persian, so mixed runs («۲٫۵٪ از دیروز») come out RTL on their own.
 */
private fun textBitmap(context: Context, text: String, sp: Float, @FontRes font: Int, color: Int): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, font)
        textSize = spToPx(context, sp)
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
 * The verdict as a pill: a caret and the figure on a wash of their own colour, the same
 * object the report screen puts under its change figure. Drawn into the bitmap rather than
 * assembled from a background drawable and a view, because the text is already pixels and a
 * rounded rect behind it costs three lines — against a tinted nine-patch per tone, a view to
 * hang it on, and a second thing that can disagree about its own padding.
 *
 * The caret is a path, not «▲». Modam has no triangle, so that glyph is whatever the
 * launcher's fallback font happens to keep there, at whatever size it happens to be.
 */
private fun pillBitmap(
    context: Context,
    text: String,
    sp: Float,
    tone: Int,
    up: Boolean?,
): Bitmap {
    val d = context.resources.displayMetrics
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.modam_semibold)
        textSize = spToPx(context, sp)
        fontFeatureSettings = TABULAR
        color = tone
    }
    val fm = paint.fontMetrics
    val padX = 11f * d.density
    val padY = 5f * d.density
    val gap = 5f * d.density
    val caret = if (up == null) 0f else paint.textSize * 0.5f
    val textW = paint.measureText(text)

    val w = ceil(padX * 2 + textW + if (up == null) 0f else caret + gap).toInt().coerceAtLeast(1)
    val h = ceil(padY * 2 + (fm.bottom - fm.top)).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawRoundRect(
        RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fade(tone, 0x24) },
    )
    // RTL: the caret leads, so it sits on the right and the figure runs leftwards from it.
    if (up != null) {
        val cx = w - padX - caret / 2f
        val cy = h / 2f
        val r = caret / 2f
        val sign = if (up) -1f else 1f
        canvas.drawPath(
            Path().apply {
                moveTo(cx, cy + sign * r * 0.88f)
                lineTo(cx + r, cy - sign * r * 0.60f)
                lineTo(cx - r, cy - sign * r * 0.60f)
                close()
            },
            paint,
        )
    }
    canvas.drawText(text, padX, padY - fm.top, paint)
    return bitmap
}

/**
 * A month of the total as one line, the report's chart with everything a widget cannot read
 * stripped out: no axes, no captions, no marker ring. Time flows left to right — charts keep
 * that direction even in an RTL interface, and every Iranian bank app agrees.
 *
 * Sized in real pixels rather than scaled by the ImageView, so the stroke is the width it was
 * asked for. The area fade reaches zero at the bottom edge and the line is held clear of it,
 * which is what lets the chart bleed to the tile's rounded corners without squaring them off.
 */
private fun sparkBitmap(
    context: Context,
    points: List<Pair<Long, Double>>,
    tone: Int,
    width: Int,
    height: Int,
): Bitmap {
    val d = context.resources.displayMetrics.density
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    val d0 = points.first().first
    val dSpan = (points.last().first - d0).coerceAtLeast(1L).toFloat()
    val padY = height * 0.12f

    fun px(day: Long) = (day - d0) / dSpan * width
    // A flat history really does draw mid-height: without the guard every point of an
    // unchanged total sat on the bottom edge, reading exactly like a crash to zero.
    fun py(v: Double) =
        if (maxV == minV) height / 2f
        else (height - padY) - (((v - minV) / span).toFloat() * (height - 2 * padY))

    val line = Path()
    points.forEachIndexed { i, (day, v) ->
        if (i == 0) line.moveTo(px(day), py(v)) else line.lineTo(px(day), py(v))
    }
    val area = Path().apply {
        addPath(line)
        lineTo(width.toFloat(), height.toFloat())
        lineTo(0f, height.toFloat())
        close()
    }
    canvas.drawPath(
        area,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                fade(tone, 0x4D), fade(tone, 0x00),
                Shader.TileMode.CLAMP,
            )
        },
    )
    canvas.drawPath(
        line,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tone
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        },
    )
    // No marker on today's end. The report's chart earns one because its line stops inside a
    // card; this one runs off the tile, and a dot on the edge would be drawn half-missing.
    return bitmap
}

/** How many days of history the tall tile draws. */
private const val WIDGET_CHART_DAYS = 30

/**
 * Everything on disk, read once and handed to every placed widget — the same numbers the app
 * would show, because they come through the same [listHoldings] and [computeTotals].
 */
private class WidgetModel(
    val total: Double,
    val masked: Boolean,
    val delta: Double?,
    val percent: Double?,
    val since: String,
    val points: List<Pair<Long, Double>>,
    val ago: String,
)

private fun readModel(context: Context, now: Long): WidgetModel {
    val store = Store(context)
    // Read once each, into locals. Every one of these properties parses JSON off disk on each
    // access, and the rates blob alone is ~80 KB — this used to decode it twice and the
    // history three times to draw one tile. Snapshotting the history also closes a real hole:
    // the day was looked up in one read and then fetched with getValue from another, so a
    // daily-snapshot write landing between the two threw NoSuchElementException.
    val rates = store.cachedRates
    val history = store.history
    val total = computeTotals(
        listHoldings(store.holdings, store.smsEnabled, store.bankAccounts, store.disabledBanks),
        effectiveRates(rates, store.overrides, store.cachedStocks),
    ).toman

    // Against the newest snapshot before today — changeOver's grace window is built for
    // month-wide reads and would fall back to today's own entry here, comparing the total
    // with itself.
    val today = now / DAY_MS
    val baseDay = history.keys.filter { it < today }.maxOrNull()
    val base = baseDay?.let { history.getValue(it) }

    return WidgetModel(
        total = total,
        masked = store.widgetLock,
        delta = base?.let { total - it },
        percent = base?.takeIf { it > 0 }?.let { (total - it) / it * 100 },
        since = when {
            baseDay == null -> ""
            today - baseDay == 1L -> "از دیروز"
            else -> "${faNumber((today - baseDay).toDouble())} روز پیش"
        },
        points = (history.filterKeys { it in (today - WIDGET_CHART_DAYS) until today }
            .toSortedMap().map { it.key to it.value } + (today to total)),
        ago = faAgo(rates.updatedAt, now),
    )
}

/**
 * Redraws every placed widget from what is already on disk — no network, so it is safe to
 * call from anywhere money changes.
 *
 * Masking is the widget's own setting, not the app lock's: the lock guards the app on the
 * phone, and whether a passing eye may read the home screen is a separate question with its
 * own switch. Masked, it shows «٭٭٭» and keeps the change and the chart hidden — a percent
 * beside three stars, or a line with a visible slope, gives away most of what the stars hide.
 */
fun updateTotalWidget(context: Context) {
    val app = context.applicationContext
    WIDGET_SCOPE.launch { drawTotalWidget(app) }
}

/**
 * For the callers that must not return until the tiles are drawn — the daily worker finishes
 * and its process becomes killable the moment [DailySnapshotWorker.doWork] returns.
 */
suspend fun updateTotalWidgetAndWait(context: Context) = withContext(WIDGET_DISPATCHER) {
    drawTotalWidget(context.applicationContext)
}

/**
 * Where the widget is actually drawn. Blocking, and never to be called on the main thread:
 * it is a SharedPreferences read, a JSON parse of everything on disk, and a handful of text
 * bitmaps rasterised through a font — a few hundred milliseconds on an old phone. It used to
 * run inline on every single change of money, so every edit, every fetch and every bank
 * message paid for it in dropped frames whether or not a widget was even placed.
 *
 * RemoteViews and AppWidgetManager are both safe off the main thread.
 */
private fun drawTotalWidget(context: Context) {
    val mgr = AppWidgetManager.getInstance(context)
    val ids = mgr.getAppWidgetIds(ComponentName(context, TotalWidget::class.java))
    if (ids.isEmpty()) return

    // Read once, drawn per tile: two widgets of different sizes are two different layouts of
    // the same numbers, and the numbers are a SharedPreferences read and a JSON parse.
    val model = readModel(context, System.currentTimeMillis())
    for (id in ids) mgr.updateAppWidget(id, render(context, model, mgr.getAppWidgetOptions(id)))
}

/**
 * One draw at a time, in the order they were asked for.
 *
 * The main thread used to provide that for free: every redraw ran inline, so the tile always
 * showed the store as of the last write. Taking the work off the main thread takes the ordering
 * with it — and a cold open asks for three redraws at once, since the rates fetch, the wallet
 * refresh and the inbox scan all end in recordSnapshot(). Each reads its own snapshot of the
 * store before painting, so unserialised they can land in any order and leave the home screen
 * showing a total from before the fetch, with no periodic update to correct it
 * (updatePeriodMillis is 0). Worse on the switch that masks the total: an unmasked draw already
 * in flight would repaint the real number over the «٭٭٭» she had just asked for.
 *
 * limitedParallelism(1) is FIFO, and every caller dispatches after its own store write, so
 * dispatch order is write order. Shared with [updateTotalWidgetAndWait] so the daily worker
 * queues behind the app's draws rather than beside them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val WIDGET_DISPATCHER = Dispatchers.Default.limitedParallelism(1)

/**
 * Outlives any one screen or broadcast on purpose — a redraw asked for as the app closes still
 * has to land. SupervisorJob so one tile that throws does not stop the next redraw.
 */
private val WIDGET_SCOPE = CoroutineScope(WIDGET_DISPATCHER + SupervisorJob())

private fun render(context: Context, model: WidgetModel, options: Bundle?): RemoteViews {
    val face = Face.of(options.widthDp(), options.heightDp())
    val views = RemoteViews(context.packageName, face.layout)

    views.setImageViewBitmap(
        R.id.widget_label,
        textBitmap(context, "دارایی من", face.label, R.font.modam_medium, MUTED),
    )
    views.setOnClickPendingIntent(
        R.id.widget_root,
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )
    if (face != Face.COMPACT) {
        views.setImageViewBitmap(
            R.id.widget_ago,
            textBitmap(context, model.ago, face.ago, R.font.modam_medium, MUTED),
        )
        views.setContentDescription(R.id.widget_ago, "نرخ‌ها ${model.ago}")
    }

    if (model.masked) {
        views.setImageViewBitmap(
            R.id.widget_total,
            textBitmap(context, "٭٭٭", face.total, R.font.modam_heavy, GOLD),
        )
        views.setContentDescription(R.id.widget_total, "مبلغ پنهانه")
        views.setViewVisibility(R.id.widget_change, View.GONE)
        if (face == Face.TALL) views.setViewVisibility(R.id.widget_chart, View.GONE)
        return views
    }

    val totalText = "${faCompact(model.total, 3, pad = true)} تومان"
    views.setImageViewBitmap(
        R.id.widget_total,
        textBitmap(context, totalText, face.total, R.font.modam_heavy, GOLD),
    )
    views.setContentDescription(R.id.widget_total, totalText)

    val delta = model.delta
    if (delta == null) {
        views.setViewVisibility(R.id.widget_change, View.GONE)
    } else {
        val flat = abs(delta) < 1
        val percent = model.percent
        val figure = when {
            flat -> "بدون تغییر"
            percent != null -> "${faDecimal(abs(percent), 1)}٪"
            else -> faCompact(abs(delta))
        }
        views.setViewVisibility(R.id.widget_change, View.VISIBLE)
        views.setImageViewBitmap(
            R.id.widget_change,
            pillBitmap(
                context,
                "$figure ${model.since}",
                face.pill,
                tone(delta),
                up = if (flat) null else delta > 0,
            ),
        )
        // TalkBack reads the verdict, not the triangle's shape.
        views.setContentDescription(
            R.id.widget_change,
            when {
                flat -> "بدون تغییر ${model.since}"
                delta > 0 -> "$figure بیشتر از ${model.since}"
                else -> "$figure کمتر از ${model.since}"
            },
        )
    }

    if (face == Face.TALL) {
        val chart = chartBitmap(context, model, options)
        if (chart == null) {
            views.setViewVisibility(R.id.widget_chart, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_chart, View.VISIBLE)
            views.setImageViewBitmap(R.id.widget_chart, chart)
        }
    }
    return views
}

/**
 * The chart at the size the tile will actually give it, so fitXY has nothing left to stretch.
 * Height is what the tile has left once the header, the total and the pill are paid for; the
 * estimate only has to be close, since fitXY absorbs the rest.
 *
 * Null when there is nothing honest to draw: a single day is a dot, not a line, and a chart
 * of one point would read as a flat month she never had.
 */
private fun chartBitmap(context: Context, model: WidgetModel, options: Bundle?): Bitmap? {
    if (model.points.size < 2) return null
    val d = context.resources.displayMetrics.density
    val widthDp = options.widthDp().takeIf { it > 0 } ?: 250
    val heightDp = options.heightDp().takeIf { it > 0 } ?: 140
    return sparkBitmap(
        context,
        model.points,
        // The line's own window, not the pill's. The pill answers "since yesterday" and the
        // line draws a month: a month that climbed, under a day that slipped, was being drawn
        // in the colour of the bad day — a rising line in the colour of a loss. The report's
        // chart takes the tone of the window it is actually showing, and so does this one.
        tone(model.points.last().second - model.points.first().second),
        width = (widthDp * d).roundToInt(),
        // What is left once the header, the total and the pill are paid for. It only has to
        // be close — fitXY absorbs the difference — but close keeps the stroke unsquashed.
        height = ((heightDp - 132).coerceIn(28, 160) * d).roundToInt(),
    )
}

class TotalWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        redrawAsync { drawTotalWidget(context.applicationContext) }

    /** Resizing is the only signal that the tile's shape changed; redraw at the new one. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = redrawAsync {
        val app = context.applicationContext
        manager.updateAppWidget(id, render(app, readModel(app, System.currentTimeMillis()), options))
    }

    /**
     * goAsync(): a broadcast receiver gets its own short slice of the main thread, and the
     * redraw is now off it — but a receiver that has already returned is a process the system
     * is free to kill mid-draw. This holds it open until the tile is actually on screen.
     * Dragging a widget about resizes it many times a second, so this is the path where doing
     * the work inline was most visible.
     */
    private fun redrawAsync(work: () -> Unit) {
        val pending = goAsync()
        WIDGET_SCOPE.launch {
            try {
                work()
            } finally {
                pending.finish()
            }
        }
    }
}
