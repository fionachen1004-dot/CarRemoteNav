package com.carremote.focusnav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 讓遙控器像 Google TV 一樣用「焦點框」操作：
 * 方向鍵＝移到該方向最近的可點項目，確定鍵＝點選，邊緣再按＝捲動。
 */
class RemoteNavService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteNavService? = null
        @Volatile var lastKey: String = ""
        @Volatile var lastInfo: String = ""
        @Volatile var navEnabled: Boolean = true

        private const val PREFS = "cfg"
        private const val KEY_ENABLED = "enabled"

        private const val UP = 0
        private const val DOWN = 1
        private const val LEFT = 2
        private const val RIGHT = 3

        private const val HIDE_AFTER_MS = 8000L
        private const val LONG_PRESS_MS = 600L

        fun loadPrefs(ctx: Context) {
            navEnabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }

        fun applyEnabled(ctx: Context, on: Boolean) {
            navEnabled = on
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
            instance?.onToggled()
        }
    }

    private class Cand(val node: AccessibilityNodeInfo, val rect: Rect)

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: FocusOverlayView? = null
    private var current: Rect? = null
    private var currentNode: AccessibilityNodeInfo? = null
    private var frameVisible = false
    private var enterDownAt = 0L
    private var revealOnly = false
    private var lastMoveAt = 0L
    private var refreshPending = false

    private val hideRunnable = Runnable {
        overlay?.hideFrame()
        frameVisible = false
    }

    // ───────────────────────── 生命週期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        loadPrefs(this)
        val view = FocusOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            overlay = null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
        current = null
        currentNode = null
        frameVisible = false
        if (instance === this) instance = null
    }

    fun onToggled() {
        if (!navEnabled) {
            handler.removeCallbacks(hideRunnable)
            hideRunnable.run()
            current = null
            currentNode = null
        }
    }

    // ───────────────────────── 事件 ─────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!navEnabled || current == null || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleRefresh()
        }
    }

    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed({
            refreshPending = false
            refresh()
        }, 350)
    }

    /** 畫面內容改變後，把焦點框貼回原本那個項目；找不到就收起。 */
    private fun refresh() {
        if (current == null) return
        val had = current
        if (syncCurrent(collect())) {
            val now = current
            if (frameVisible && now != null && had != now) overlay?.showAt(now, true)
        } else {
            overlay?.hideFrame()
            frameVisible = false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) {
            lastKey = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") +
                "（來自 " + (event.device?.name ?: "未知裝置") + "）"
        }
        if (!navEnabled) return false

        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> onDirection(UP, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> onDirection(DOWN, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> onDirection(LEFT, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> onDirection(RIGHT, event)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> onEnter(event)
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (down) performGlobalAction(GLOBAL_ACTION_BACK)
                true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) pageScroll(DOWN)
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) pageScroll(UP)
                true
            }
            else -> false
        }
    }

    private fun onDirection(dir: Int, event: KeyEvent): Boolean {
        // 正在打字時，左右鍵留給輸入框移動游標
        if ((dir == LEFT || dir == RIGHT) && editingText()) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0 && now - lastMoveAt < 140) return true
        lastMoveAt = now
        handleDirection(dir)
        return true
    }

    private fun onEnter(event: KeyEvent): Boolean {
        if (current == null) return false // 還沒有焦點框時，確定鍵照原樣交給系統
        val now = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                enterDownAt = now
                revealOnly = !frameVisible
                if (revealOnly && syncCurrent(collect())) showFrame(false)
            }
            return true
        }
        if (revealOnly) {
            revealOnly = false
            return true
        }
        activate(now - enterDownAt >= LONG_PRESS_MS)
        scheduleHide()
        return true
    }

    // ───────────────────────── 焦點移動 ─────────────────────────

    private fun handleDirection(dir: Int) {
        val cands = collect()

        if (current != null) {
            if (!syncCurrent(cands)) {
                // 原本的項目不見了（換頁了），重新選
                overlay?.hideFrame()
                frameVisible = false
            } else if (!frameVisible) {
                showFrame(false) // 框已自動收起，第一下只是叫出來，不移動
                return
            }
        }

        val cur = current
        if (cur == null) {
            val dm = resources.displayMetrics
            val first = pickNearest(cands, dm.widthPixels / 2f, dm.heightPixels / 2f)
            if (first != null) {
                setFocus(first, false)
                lastInfo = "選到第一個項目（共找到 ${cands.size} 個可點項目）"
            } else {
                lastInfo = "這個畫面找不到任何可點項目（App 沒有公開按鈕資訊）"
                overlay?.pulse()
            }
            return
        }

        val next = pickDirectional(cands, cur, dir)
        if (next != null) {
            setFocus(next, true)
            lastInfo = "移動焦點（共找到 ${cands.size} 個可點項目）"
            return
        }
        // 這個方向沒有項目了 → 嘗試捲動
        if (!tryScroll(dir)) overlay?.pulse()
    }

    private fun setFocus(c: Cand, animate: Boolean) {
        current = Rect(c.rect)
        currentNode = c.node
        showFrame(animate)
    }

    private fun showFrame(animate: Boolean) {
        val r = current ?: return
        overlay?.showAt(r, animate && frameVisible)
        frameVisible = true
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_AFTER_MS)
    }

    private fun syncCurrent(cands: List<Cand>): Boolean {
        val cur = current ?: return false
        var best: Cand? = null
        var bestRatio = 0.4f
        for (c in cands) {
            val ratio = overlapRatio(c.rect, cur)
            if (ratio >= bestRatio) {
                bestRatio = ratio
                best = c
            }
        }
        return if (best == null) {
            current = null
            currentNode = null
            false
        } else {
            current = Rect(best.rect)
            currentNode = best.node
            true
        }
    }

    private fun pickNearest(cands: List<Cand>, x: Float, y: Float): Cand? =
        cands.minByOrNull {
            val dx = it.rect.exactCenterX() - x
            val dy = it.rect.exactCenterY() - y
            dx * dx + dy * dy
        }

    private fun pickDirectional(cands: List<Cand>, cur: Rect, dir: Int): Cand? {
        val cx = cur.exactCenterX()
        val cy = cur.exactCenterY()
        val vertical = dir == UP || dir == DOWN
        var best: Cand? = null
        var bestScore = Double.MAX_VALUE
        for (c in cands) {
            val r = c.rect
            if (overlapRatio(r, cur) > 0.6f) continue
            if (r.contains(cur)) continue // 不要跳到包住目前項目的外層容器
            val dx = r.exactCenterX() - cx
            val dy = r.exactCenterY() - cy
            val inDir = when (dir) {
                UP -> dy < -4f && r.top < cur.top
                DOWN -> dy > 4f && r.bottom > cur.bottom
                LEFT -> dx < -4f && r.left < cur.left
                else -> dx > 4f && r.right > cur.right
            }
            if (!inDir) continue
            val major = (if (vertical) abs(dy) else abs(dx)).toDouble()
            var minor = (if (vertical) abs(dx) else abs(dy)).toDouble()
            val overlap = if (vertical) {
                min(r.right, cur.right) - max(r.left, cur.left)
            } else {
                min(r.bottom, cur.bottom) - max(r.top, cur.top)
            }
            if (overlap > 0) minor *= 0.25 // 與目前項目在同一行／列，優先
            val score = major + 2.0 * minor
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bt = min(a.bottom, b.bottom)
        if (r <= l || bt <= t) return 0f
        val inter = (r - l).toLong() * (bt - t)
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return if (union <= 0L) 0f else inter.toFloat() / union.toFloat()
    }

    // ───────────────────────── 蒐集可點項目 ─────────────────────────

    private fun collect(): List<Cand> {
        val out = ArrayList<Cand>()
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels.toLong()
        val budget = intArrayOf(4000)
        rootInActiveWindow?.let { walk(it, out, screenArea, 0, budget) }
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    w.root?.let { walk(it, out, screenArea, 0, budget) }
                }
            }
        } catch (e: Exception) {
        }
        return out.distinctBy { it.rect.flattenToString() }
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Cand>, screenArea: Long, depth: Int, budget: IntArray) {
        if (budget[0]-- <= 0 || depth > 60) return
        if (!n.isVisibleToUser) return
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() >= 12 && r.height() >= 12) {
            val area = r.width().toLong() * r.height()
            val nearlyFullScreen = area * 10 >= screenArea * 8
            if (!nearlyFullScreen && n.isEnabled && isActionable(n)) out.add(Cand(n, r))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, screenArea, depth + 1, budget)
        }
    }

    private fun isActionable(n: AccessibilityNodeInfo): Boolean =
        n.isClickable || n.isCheckable ||
            (n.isFocusable && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

    private fun editingText(): Boolean {
        return try {
            val imeUp = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            imeUp && rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
        } catch (e: Exception) {
            false
        }
    }

    // ───────────────────────── 點選 ─────────────────────────

    private fun activate(isLong: Boolean) {
        val rect = current ?: return
        val node = currentNode
        var r = rect
        if (node != null && node.refresh()) {
            val fresh = Rect()
            node.getBoundsInScreen(fresh)
            if (fresh.width() > 0 && fresh.height() > 0) r = fresh
            if (!isLong) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    overlay?.pulse()
                    return
                }
            } else if (node.isLongClickable) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    overlay?.pulse()
                    return
                }
            }
        }
        // 控制項不支援無障礙點擊 → 直接在中心位置模擬手指點一下
        overlay?.pulse()
        val p = Path()
        p.moveTo(r.exactCenterX(), r.exactCenterY())
        gesture(p, if (isLong) 700L else 50L)
    }

    private fun gesture(p: Path, durationMs: Long) {
        try {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
                .build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
        }
    }

    // ───────────────────────── 捲動 ─────────────────────────

    /**
     * 往 dir 方向捲動。依序嘗試：
     * 1. 具體方向的捲動動作（SCROLL_UP/DOWN/LEFT/RIGHT）
     * 2. 通用的 SCROLL_FORWARD / SCROLL_BACKWARD（很多 App 只提供這個）
     * 3. 都不行就在畫面上模擬手指滑動
     */
    private fun tryScroll(dir: Int): Boolean {
        val specific = when (dir) {
            UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        val generic = if (dir == DOWN || dir == RIGHT) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val vertical = dir == UP || dir == DOWN

        val matches = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        rootInActiveWindow?.let { findScrollable(it, specific, generic, matches, 0, intArrayOf(4000)) }

        val cur = current
        var container: Rect? = null
        var done = false

        if (matches.isNotEmpty()) {
            val exact = matches.filter { m -> m.first.actionList.any { it.id == specific } }
            val pool = if (exact.isNotEmpty()) exact else matches
            val inside: List<Pair<AccessibilityNodeInfo, Rect>> = if (cur == null) {
                emptyList()
            } else {
                pool.filter { it.second.contains(cur.centerX(), cur.centerY()) }
            }
            val pick: Pair<AccessibilityNodeInfo, Rect>? = if (exact.isNotEmpty()) {
                // 有明確方向的動作：取包住焦點框的最內層，否則取最大的
                inside.minByOrNull { it.second.width().toLong() * it.second.height() }
                    ?: pool.maxByOrNull { it.second.width().toLong() * it.second.height() }
            } else {
                // 只有前進／後退：垂直方向取最高的、水平方向取最寬的，避免誤捲橫向列
                val extent = { r: Rect -> if (vertical) r.height() else r.width() }
                (if (inside.isNotEmpty()) inside else pool).maxByOrNull { extent(it.second) }
            }
            if (pick != null) {
                container = pick.second
                val useAction = if (pick.first.actionList.any { it.id == specific }) specific else generic
                done = pick.first.performAction(useAction)
                lastInfo = if (done) "已用捲動指令捲動" else "捲動指令被 App 拒絕，改用手指滑動"
            }
        }

        if (!done) {
            done = swipe(dir, container)
            lastInfo = if (done) "已模擬手指滑動來捲動" else "無法捲動這個畫面"
        }

        if (done) {
            overlay?.pulse()
            handler.postDelayed({ afterScroll() }, 600)
        }
        return done
    }

    private fun findScrollable(
        n: AccessibilityNodeInfo,
        specific: Int,
        generic: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        depth: Int,
        budget: IntArray
    ) {
        if (budget[0]-- <= 0 || depth > 60) return
        if (!n.isVisibleToUser) return
        if (n.isScrollable && n.actionList.any { it.id == specific || it.id == generic }) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(Pair(n, r))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c, specific, generic, out, depth + 1, budget)
        }
    }

    /** 在容器（沒有就用整個畫面）範圍內模擬手指滑動，滑動量約為容器高（寬）的一半。 */
    private fun swipe(dir: Int, container: Rect?): Boolean {
        val dm = resources.displayMetrics
        val area = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val box = Rect(container ?: area)
        if (!box.intersect(area) || box.width() < 40 || box.height() < 40) box.set(area)

        val x = box.exactCenterX()
        val y = box.exactCenterY()
        val dx = box.width() * 0.25f
        val dy = box.height() * 0.25f
        val p = Path()
        when (dir) {
            DOWN -> { p.moveTo(x, y + dy); p.lineTo(x, y - dy) }   // 內容往上走＝往下捲
            UP -> { p.moveTo(x, y - dy); p.lineTo(x, y + dy) }
            RIGHT -> { p.moveTo(x + dx, y); p.lineTo(x - dx, y) }
            else -> { p.moveTo(x - dx, y); p.lineTo(x + dx, y) }
        }
        return try {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 350L))
                .build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }

    /** 捲動完成後，把焦點框放到原位置附近最近的新項目上。 */
    private fun afterScroll() {
        val cur = current ?: return
        val c = pickNearest(collect(), cur.exactCenterX(), cur.exactCenterY()) ?: return
        setFocus(c, true)
    }

    private fun pageScroll(dir: Int) {
        tryScroll(dir)
    }
}
