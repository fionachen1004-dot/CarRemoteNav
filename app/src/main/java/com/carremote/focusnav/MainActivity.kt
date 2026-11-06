package com.carremote.focusnav

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keyView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteNavService.loadPrefs(this)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(text("遙控器焦點導覽", 28f, bold = true))

        statusView = text("", 20f)
        root.addView(statusView)

        keyView = text("", 20f)
        root.addView(keyView)

        val toggle = Switch(this).apply {
            text = "啟用遙控器焦點導覽"
            textSize = 20f
            isChecked = RemoteNavService.navEnabled
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> RemoteNavService.applyEnabled(this@MainActivity, on) }
        }
        root.addView(toggle)

        val openBtn = Button(this).apply {
            text = "開啟無障礙設定"
            textSize = 20f
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "這台車機找不到無障礙設定頁，請改用 README 裡的 adb 指令開啟",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(openBtn)

        root.addView(
            text(
                "\n使用方式\n" +
                    "1. 按上方按鈕，在清單裡找到「遙控器焦點導覽」並開啟。\n" +
                    "2. 用遙控器方向鍵移動白色焦點框，按確定鍵點選；長按確定鍵＝長按。\n" +
                    "3. 到畫面邊緣再按方向鍵，會自動捲動清單。\n" +
                    "4. 返回鍵、ESC 鍵＝返回；PageUp / PageDown＝捲動。\n\n" +
                    "測試遙控器\n" +
                    "按遙控器任何鍵，上方「最後按鍵」會顯示它送出的按鍵名稱。" +
                    "如果按方向鍵時完全沒有變化，代表遙控器目前是滑鼠模式，" +
                    "請先切換成鍵盤／方向鍵模式（多數飛鼠遙控器有切換按鍵，請看遙控器說明書）。",
                18f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        statusView.text = if (RemoteNavService.instance != null) {
            "服務狀態：運作中 ✅"
        } else {
            "服務狀態：尚未開啟（請到無障礙設定開啟「遙控器焦點導覽」）"
        }
        val last = RemoteNavService.lastKey
        keyView.text = "最後按鍵：" + (if (last.isEmpty()) "（尚未收到）" else last)
    }

    private fun text(s: String, sp: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(0xFF111111.toInt())
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
}
