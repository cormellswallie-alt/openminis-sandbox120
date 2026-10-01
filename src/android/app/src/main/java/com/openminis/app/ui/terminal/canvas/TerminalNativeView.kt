/*
 * T194 part-2 — native Android terminal View with long-press selection +
 * floating ActionMode, ClipboardManager copy. Architecture inspired by
 * Termux terminal-view (Apache-2.0) — long-press → setInitialTextSelection
 * → startActionMode(TYPE_FLOATING) → onActionItemClicked.copy →
 * ClipboardManager.setPrimaryClip — but reimplemented from architecture
 * description against our existing TerminalEmulator/TerminalBuffer; no
 * source copied.
 *
 * Renderer is a port of TerminalCanvasView.kt (Compose Canvas → onDraw)
 * preserving cell paint, ANSI styling, cursor blink, and the new T194
 * selection-rect overlay. Vertical scroll → emulator.scrollOffset matches
 * the existing fling-aware drag logic minus the Compose decay animator
 * (Android's OverScroller would re-introduce that — out of scope for
 * part-2; users still get manual drag scrollback).
 */
package com.openminis.app.ui.terminal.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.ui.terminal.emulator.CursorShape
import com.openminis.app.ui.terminal.emulator.TerminalCell
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import com.openminis.app.ui.terminal.emulator.TerminalPalette
import com.openminis.app.ui.terminal.emulator.TextAttributes
import com.openminis.app.ui.terminal.rememberJetBrainsMonoTypeface

/**
 * Compose wrapper around [TerminalNativeView]. Drop-in replacement for
 * [TerminalCanvasView] — same parameter shape so [TerminalScreen] can
 * swap the call site by one identifier. Uses our bundled JetBrains Mono
 * typeface so glyph metrics match the old canvas-based renderer.
 */
@Composable
fun TerminalNativeViewCompose(
    emulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onResize: (cols: Int, rows: Int) -> Unit,
    onTap: () -> Unit = {},
) {
    val typeface = rememberJetBrainsMonoTypeface()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalNativeView(ctx, typeface).apply {
                setFontSizeSp(fontSizeSp)
                attachEmulator(emulator, onResize, onTap)
            }
        },
        update = { view ->
            view.setFontSizeSp(fontSizeSp)
            view.attachEmulator(emulator, onResize, onTap)
        },
    )
}

class TerminalNativeView @JvmOverloads constructor(
    context: Context,
    private val typeface: Typeface = Typeface.MONOSPACE,
) : View(context) {

    private var emulator: TerminalEmulator? = null
    private var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    private var onTap: () -> Unit = {}

    private val basePaint = Paint().apply {
        this.typeface = this@TerminalNativeView.typeface
        textSize = 13f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
        isSubpixelText = true
    }
    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var baselineOffset: Float = 0f
    private var cols: Int = 80
    private var rows: Int = 24

    // Cursor blink — flips every 500 ms.
    private var cursorVisible: Boolean = true
    private val cellPainter = TerminalCellPainter(basePaint)
    private val selectionPaint = Paint().apply { color = android.graphics.Color.argb(0x66, 0x33, 0x99, 0xFF) }
    private val cursorPaint = Paint().apply { color = TerminalPalette.defaultForeground.toArgbInt() }
    private val cursorTextPaint = Paint(basePaint).apply { color = TerminalPalette.defaultBackground.toArgbInt() }
    private val cursorChars = CharArray(2)
    private val blinkRunnable = object : Runnable {
        override fun run() {
            val em = emulator
            if (isShown && em?.cursorVisible == true && em.scrollOffset == 0) {
                cursorVisible = !cursorVisible
                val (c, r) = em.cursorPos()
                invalidate((c * cellWidth).toInt(), (r * cellHeight).toInt(),
                    ((c + 2) * cellWidth).toInt() + 1, ((r + 1) * cellHeight).toInt() + 1)
            }
            postDelayed(this, 500)
        }
    }

    // ── Selection state ────────────────────────────────────────────────────
    private var actionMode: ActionMode? = null
    private var lastSelEndX: Int = 0   // pixels — for ActionMode anchor rect
    private var lastSelEndY: Int = 0

    init {
        isFocusable = true
        isClickable = true
        recomputeMetrics()
    }

    fun attachEmulator(
        emulator: TerminalEmulator,
        onResize: (Int, Int) -> Unit,
        onTap: () -> Unit,
    ) {
        val changed = this.emulator !== emulator
        this.emulator = emulator
        this.onResize = onResize
        this.onTap = onTap
        if (isAttachedToWindow) observeEmulator()
        if (changed) invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        val px = sp * resources.displayMetrics.scaledDensity
        if (!px.isFinite() || px <= 0f || basePaint.textSize == px) return
        basePaint.textSize = px
        cellPainter.reset()
        cursorTextPaint.textSize = px
        recomputeMetrics()
        requestLayout()
        invalidate()
    }

    private fun recomputeMetrics() {
        cellWidth = basePaint.measureText("M")
        val fm = basePaint.fontMetrics
        cellHeight = fm.bottom - fm.top
        baselineOffset = -fm.top
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blinkRunnable)
        // Redraw only on actual emulator state changes; no idle 60 Hz poll.
        redrawScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        observeEmulator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
        redrawScope?.cancel()
        redrawScope = null
        observedEmulator = null
        actionMode?.finish()
    }

    private var redrawScope: kotlinx.coroutines.CoroutineScope? = null
    private var redrawJob: kotlinx.coroutines.Job? = null
    private var observedEmulator: TerminalEmulator? = null
    private fun observeEmulator() {
        val em = emulator ?: return
        if (em === observedEmulator && redrawJob?.isActive == true) return
        redrawJob?.cancel()
        observedEmulator = em
        redrawJob = redrawScope?.launch {
            androidx.compose.runtime.snapshotFlow { em.version.value }.conflate().collect {
                postInvalidateOnAnimation()
            }
        }
    }

    // ── Sizing ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val newCols = maxOf(1, (w / cellWidth).toInt())
        val newRows = maxOf(1, (h / cellHeight).toInt())
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            onResize(newCols, newRows)
        }
    }

    // ── Touch handling ─────────────────────────────────────────────────────

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tapping outside an active selection cancels it; otherwise pass focus.
            if (emulator?.selectionRect?.value != null) {
                emulator?.clearSelectionRect()
                actionMode?.finish()
                return true
            }
            onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startSelectionAt(e.x, e.y)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            // distanceY is e1.y - e2.y in Android's GestureDetector — i.e.
            // positive when the finger moves up. Scroll back through history
            // means moving content down on screen, which from the user's
            // POV is dragging the finger down, distanceY < 0.
            val em = emulator ?: return false
            // Reverse sign: dragging down (distanceY < 0) increases scrollOffset.
            val rowDelta = (-distanceY / cellHeight).toInt()
            if (rowDelta != 0) {
                em.scrollOffset = (em.scrollOffset + rowDelta).coerceAtLeast(0)
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ── Selection lifecycle ───────────────────────────────────────────────

    private fun startSelectionAt(px: Float, py: Float) {
        val em = emulator ?: return
        if (cellWidth <= 0f || cellHeight <= 0f) return
        val col = (px / cellWidth).toInt().coerceIn(0, cols - 1)
        val row = (py / cellHeight).toInt().coerceIn(0, rows - 1)

        // Word-expand on whitespace boundaries.
        val (sx, ex) = wordExpand(col, row)
        em.setSelectionRect(sx, row, ex, row)
        lastSelEndX = ((ex + 1) * cellWidth).toInt()
        lastSelEndY = ((row + 1) * cellHeight).toInt()

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startActionModeFloating()
    }

    private fun wordExpand(col: Int, row: Int): Pair<Int, Int> {
        val em = emulator ?: return col to col
        val line = em.visibleLineAt(row) ?: return col to col
        val lineWidth = minOf(line.size, em.cols)
        if (col !in 0 until lineWidth) return col to col

        fun isWordChar(c: Int): Boolean {
            // Letters, digits, and common URL punctuation.
            val ch = c.toChar()
            return ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' ||
                ch == '/' || ch == ':' || ch == '?' || ch == '&' || ch == '=' ||
                ch == '+' || ch == '%' || ch == '#' || ch == '~' || ch == '@'
        }

        if (!isWordChar(line[col].char)) return col to col
        var sx = col
        var ex = col
        while (sx > 0 && isWordChar(line[sx - 1].char)) sx--
        while (ex < lineWidth - 1 && isWordChar(line[ex + 1].char)) ex++
        return sx to ex
    }

    private fun startActionModeFloating() {
        if (actionMode != null) return
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val em = emulator ?: return false
                return when (item.itemId) {
                    MENU_COPY -> {
                        val sel = em.selectionRect.value
                        if (sel != null) {
                            val text = em.getSelectedText(sel[0], sel[1], sel[2], sel[3])
                            if (text.isNotEmpty()) {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Minis Shell", text))
                            }
                        }
                        mode.finish()
                        true
                    }
                    MENU_SELECT_ALL -> {
                        em.setSelectionRect(0, 0, cols - 1, rows - 1)
                        lastSelEndX = (cols * cellWidth).toInt()
                        lastSelEndY = (rows * cellHeight).toInt()
                        true
                    }
                    else -> false
                }
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                emulator?.clearSelectionRect()
            }
            override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect) {
                val em = emulator
                val sel = em?.selectionRect?.value
                if (sel == null) {
                    super.onGetContentRect(mode, view, outRect)
                    return
                }
                // Anchor toolbar above the bottom-right corner of the selection.
                val (sx, sy, ex, ey) = if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2])) {
                    intArrayOf(sel[0], sel[1], sel[2], sel[3])
                } else {
                    intArrayOf(sel[2], sel[3], sel[0], sel[1])
                }.let { listOf(it[0], it[1], it[2], it[3]) }
                outRect.set(
                    (sx * cellWidth).toInt(),
                    (sy * cellHeight).toInt(),
                    ((ex + 1) * cellWidth).toInt(),
                    ((ey + 1) * cellHeight).toInt(),
                )
            }
        }
        actionMode = startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    // ── Render ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return

        // Clear background — matches default palette.
        canvas.drawColor(TerminalPalette.defaultBackground.toArgbInt())

        for (r in 0 until em.rows) {
            val row = em.visibleLineAt(r) ?: continue
            val y = r * cellHeight
            if (canvas.quickReject(0f, y, width.toFloat(), y + cellHeight, Canvas.EdgeType.AA)) continue
            for (c in 0 until minOf(row.size, em.cols)) {
                val cell = row[c]
                if (cell.isWideTrailer) continue
                val x = c * cellWidth
                if (canvas.quickReject(x, y, x + cellWidth * cell.width, y + cellHeight, Canvas.EdgeType.AA)) continue
                cellPainter.draw(canvas, cell, c * cellWidth, y, cellWidth, cellHeight, baselineOffset)
            }
        }

        // Selection highlight.
        val sel = em.selectionRect.value
        if (sel != null) {
            val (sx, sy, ex, ey) = if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2])) {
                intArrayOf(sel[0], sel[1], sel[2], sel[3])
            } else {
                intArrayOf(sel[2], sel[3], sel[0], sel[1])
            }.let { listOf(it[0], it[1], it[2], it[3]) }
            for (r in sy..ey) {
                if (r !in 0 until rows) continue
                val cStart = if (r == sy) sx else 0
                val cEnd = if (r == ey) ex + 1 else cols
                if (cEnd <= cStart) continue
                canvas.drawRect(
                    cStart * cellWidth, r * cellHeight,
                    cEnd * cellWidth, (r + 1) * cellHeight,
                    selectionPaint,
                )
            }
        }

        // Cursor (only when viewing live tail).
        if (em.cursorVisible && cursorVisible && em.scrollOffset == 0) {
            val (cc, cr) = em.cursorPos()
            if (cr in 0 until rows && cc in 0 until cols) {
                val cx = cc * cellWidth
                val cy = cr * cellHeight
                when (em.cursorShape) {
                    CursorShape.BLOCK -> {
                        canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
                        val cell = em.visibleLineAt(cr)?.getOrNull(cc)
                        if (cell != null && cell.char != ' '.code) {
                            val cp = cell.char.takeIf { Character.isValidCodePoint(it) } ?: 0xFFFD
                            val length = Character.toChars(cp, cursorChars, 0)
                            canvas.drawText(cursorChars, 0, length, cx, cy + baselineOffset, cursorTextPaint)
                        }
                    }
                    CursorShape.UNDERLINE -> canvas.drawRect(
                        cx, cy + cellHeight - 2f,
                        cx + cellWidth, cy + cellHeight, cursorPaint,
                    )
                    CursorShape.BAR -> canvas.drawRect(
                        cx, cy, cx + 2f, cy + cellHeight, cursorPaint,
                    )
                }
            }
        }
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2
    }
}

// `androidx.compose.ui.graphics.Color → toArgb()` would pull a Compose
// dependency we don't need here; do the conversion inline.
private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f).toInt(),
        (red * 255f).toInt(),
        (green * 255f).toInt(),
        (blue * 255f).toInt(),
    )

/** Reuses drawing objects and typefaces instead of allocating for every cell/frame. */
private class TerminalCellPainter(private val basePaint: Paint) {
    private val background = Paint()
    private val glyph = Paint(basePaint)
    private val chars = CharArray(2)
    private val faces = arrayOf(
        basePaint.typeface,
        Typeface.create(basePaint.typeface, Typeface.BOLD),
        Typeface.create(basePaint.typeface, Typeface.ITALIC),
        Typeface.create(basePaint.typeface, Typeface.BOLD_ITALIC),
    )
    fun reset() { glyph.set(basePaint) }
    fun draw(canvas: Canvas, cell: TerminalCell, x: Float, y: Float, w: Float, h: Float, baseline: Float) {
        val attrs = cell.attributes
        val inverse = attrs.has(TextAttributes.INVERSE)
        val bold = attrs.has(TextAttributes.BOLD)
        val fg = TerminalPalette.resolve(if (inverse) cell.background else cell.foreground, true, bold)
        val bg = TerminalPalette.resolve(if (inverse) cell.foreground else cell.background, false)
        if (bg != TerminalPalette.defaultBackground) {
            background.color = bg.toArgbInt()
            canvas.drawRect(x, y, x + w * cell.width, y + h, background)
        }
        if (cell.char == ' '.code && !attrs.has(TextAttributes.UNDERLINE) && !attrs.has(TextAttributes.STRIKETHROUGH)) return
        glyph.color = fg.toArgbInt()
        glyph.typeface = faces[(if (bold) 1 else 0) + (if (attrs.has(TextAttributes.ITALIC)) 2 else 0)]
        glyph.isFakeBoldText = bold
        glyph.isUnderlineText = attrs.has(TextAttributes.UNDERLINE)
        glyph.isStrikeThruText = attrs.has(TextAttributes.STRIKETHROUGH)
        glyph.alpha = when { attrs.has(TextAttributes.HIDDEN) -> 0; attrs.has(TextAttributes.DIM) -> 128; else -> 255 }
        val cp = cell.char.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF } ?: 0xFFFD
        val length = Character.toChars(cp, chars, 0)
        canvas.drawText(chars, 0, length, x, y + baseline, glyph)
    }
}
