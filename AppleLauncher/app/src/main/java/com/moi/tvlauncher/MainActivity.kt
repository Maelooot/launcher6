package com.moi.tvlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    // ---- Reglages faciles a modifier ----
    private val LAT = 48.8566          // Paris
    private val LON = 2.3522
    private val AUTO_CLEAN = true      // vide la RAM a chaque retour sur l'accueil
    // -------------------------------------

    private lateinit var bg: View
    private lateinit var title: TextView
    private lateinit var row: LinearLayout
    private lateinit var ramPill: TextView
    private lateinit var storagePill: TextView
    private lateinit var weatherPill: TextView
    private var currentColor = Color.parseColor("#2b1a4d")
    private var pkgs: List<String> = emptyList()
    private var lastWeather = 0L
    private val ui = Handler(Looper.getMainLooper())

    private val stats = object : Runnable {
        override fun run() { updateStats(); ui.postDelayed(this, 4000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this)
        bg = View(this)
        root.addView(bg, ViewGroup.LayoutParams(-1, -1))
        setBg(currentColor)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(28), dp(56), dp(32))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(col, ViewGroup.LayoutParams(-1, -1))

        // Barre du haut : heure a gauche, pastilles "verre" a droite
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextColor(Color.WHITE); textSize = 64f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE d MMMM"; format24Hour = "EEEE d MMMM"
            setTextColor(Color.parseColor("#CCFFFFFF")); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(clock); left.addView(date)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        ramPill = pill(); storagePill = pill(); weatherPill = pill()
        ramPill.isFocusable = true
        ramPill.isClickable = true
        ramPill.setOnClickListener { cleanRam(true) }
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(2), Color.WHITE); cornerRadius = dp(22).toFloat()
        }
        ramPill.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(150).start()
            v.foreground = if (has) ring else null
        }
        for (p in listOf(ramPill, storagePill, weatherPill)) {
            val lp = LinearLayout.LayoutParams(-2, -2); lp.leftMargin = dp(12)
            top.addView(p, lp)
        }
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 46f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(8))
        }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }
        col.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        weatherPill.text = "…"
    }

    private fun pill(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(18), dp(9), dp(18), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF"))
            setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            cornerRadius = dp(22).toFloat()
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        if (AUTO_CLEAN) cleanRam(false)
        ui.post(stats)
        if (System.currentTimeMillis() - lastWeather > 15 * 60 * 1000) fetchWeather()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(stats)
    }

    override fun onBackPressed() { }

    // ---------- Stats RAM / stockage ----------
    private fun memInfo(): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi
    }

    private fun go(bytes: Long) = String.format(Locale.FRANCE, "%.1f", bytes / 1073741824.0)

    private fun updateStats() {
        val mi = memInfo()
        val used = mi.totalMem - mi.availMem
        ramPill.text = "RAM  ${go(used)} / ${go(mi.totalMem)} Go"
        val st = StatFs(Environment.getDataDirectory().path)
        storagePill.text = "Stockage  ${go(st.availableBytes)} Go libres"
    }

    // ---------- Nettoyage memoire ----------
    private fun cleanRam(toast: Boolean) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = memInfo().availMem
        val list = pkgs.filter { !it.contains("fixer", true) && it != packageName }
        Thread {
            for (p in list) try { am.killBackgroundProcesses(p) } catch (e: Exception) { }
            Thread.sleep(900)
            val freed = (memInfo().availMem - before) / 1048576
            ui.post {
                updateStats()
                if (toast) Toast.makeText(this,
                    if (freed > 0) "$freed Mo liberes" else "Memoire deja optimisee",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---------- Meteo (Open-Meteo, sans cle) ----------
    private fun fetchWeather() {
        lastWeather = System.currentTimeMillis()
        Thread {
            try {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                        "&current=temperature_2m,weather_code&timezone=auto")
                val c = u.openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("current")
                val t = Math.round(j.getDouble("temperature_2m"))
                val ico = when (j.getInt("weather_code")) {
                    0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
                    in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"
                    in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"; else -> "🌡️"
                }
                ui.post { weatherPill.text = "$ico  $t°C" }
            } catch (e: Exception) {
                lastWeather = 0
                ui.post { weatherPill.text = "Meteo indisponible" }
            }
        }.start()
    }

    // ---------- Applications ----------
    private fun loadApps() {
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo.packageName
                if (p != packageName && !found.containsKey(p)) found[p] = ri
            }
        }
        pkgs = found.keys.toList()
        val list = found.values.sortedBy { it.loadLabel(pm).toString().lowercase() }

        row.removeAllViews()
        var first: View? = null
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val d = ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)
            val tile = makeTile(label, d, ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
            row.addView(tile)
            if (first == null) first = tile
        }
        first?.requestFocus()
    }

    private fun makeTile(label: String, d: Drawable, cn: ComponentName): View {
        val tile = FrameLayout(this)
        val lp = LinearLayout.LayoutParams(dp(240), dp(135))
        lp.setMargins(dp(10), 0, dp(10), 0)
        tile.layoutParams = lp
        tile.isFocusable = true
        tile.isClickable = true
        tile.elevation = dp(4).toFloat()
        val radius = dp(14).toFloat()
        tile.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        tile.clipToOutline = true
        val color = avgColor(d)
        tile.background = GradientDrawable().apply { setColor(darken(color, 0.55f)) }

        val img = ImageView(this).apply {
            setImageDrawable(d)
            scaleType = if (d.intrinsicWidth > d.intrinsicHeight * 1.3) ImageView.ScaleType.CENTER_CROP
                        else ImageView.ScaleType.CENTER_INSIDE
            if (scaleType == ImageView.ScaleType.CENTER_INSIDE) setPadding(dp(28), dp(16), dp(28), dp(16))
        }
        tile.addView(img, FrameLayout.LayoutParams(-1, -1))

        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE); cornerRadius = radius
        }
        tile.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(180).start()
            v.elevation = dp(if (has) 16 else 4).toFloat()
            v.foreground = if (has) ring else null
            if (has) { title.text = label; fadeBg(darken(color, 0.6f)) }
        }
        tile.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (e: Exception) { }
        }
        return tile
    }

    private fun avgColor(d: Drawable): Int {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 16, 16); d.draw(Canvas(bmp))
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 16) for (y in 0 until 16) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) > 80) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ }
        }
        if (n == 0L) return Color.parseColor("#3a2a6a")
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun darken(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun setBg(c: Int) {
        currentColor = c
        bg.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c, Color.BLACK))
    }

    private fun fadeBg(to: Int) {
        ValueAnimator.ofObject(ArgbEvaluator(), currentColor, to).apply {
            duration = 450
            addUpdateListener { setBg(it.animatedValue as Int) }
            start()
        }
    }
}
