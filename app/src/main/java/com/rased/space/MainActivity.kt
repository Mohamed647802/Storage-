package com.rased.space

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val pkg: String,
    val app: Long,
    val data: Long,   // البيانات بدون الكاش
    val cache: Long
) {
    val total: Long get() = app + data + cache
}

class MainActivity : Activity() {

    private var all: List<AppItem> = emptyList()
    private val selected = HashSet<String>()
    private var sortKey = 0          // 0 الإجمالي, 1 التطبيق, 2 البيانات, 3 الكاش
    private var desc = true
    private var loading = false
    private var lastLoad = 0L

    private val loader = Executors.newSingleThreadExecutor()
    private val statsPool = Executors.newFixedThreadPool(4)
    private val iconPool = Executors.newFixedThreadPool(2)
    private val icons = LruCache<String, Drawable>(150)

    private lateinit var adapter: Adapter
    private lateinit var summary: TextView
    private lateinit var permPanel: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var chips: List<TextView>
    private lateinit var chipDir: TextView
    private lateinit var chipAll: TextView
    private lateinit var btnClean: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        summary = findViewById(R.id.summary)
        permPanel = findViewById(R.id.permPanel)
        swipe = findViewById(R.id.swipe)
        chipDir = findViewById(R.id.chipDir)
        chipAll = findViewById(R.id.chipAll)
        btnClean = findViewById(R.id.btnClean)
        chips = listOf(
            findViewById(R.id.chipTotal), findViewById(R.id.chipApp),
            findViewById(R.id.chipData), findViewById(R.id.chipCache)
        )

        swipe.setColorSchemeColors(0xFF7C5CFF.toInt())
        swipe.setProgressBackgroundColorSchemeColor(0xFF171C33.toInt())
        swipe.setOnRefreshListener { load(true) }

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        chips.forEachIndexed { i, c -> c.setOnClickListener { sortKey = i; render() } }
        chipDir.setOnClickListener { desc = !desc; render() }

        chipAll.setOnClickListener {
            if (all.isNotEmpty() && selected.size == all.size) selected.clear()
            else selected.addAll(all.map { it.pkg })
            adapter.notifyDataSetChanged()
            updateBar()
        }
        btnClean.setOnClickListener { startClean() }

        adapter = Adapter()
        adapter.setHasStableIds(true)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
            setItemViewCacheSize(12)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            permPanel.visibility = View.VISIBLE
            return
        }
        permPanel.visibility = View.GONE
        val force = CacheCleanerService.finished
        if (force) CacheCleanerService.finished = false
        if (force || all.isEmpty()) load(true)
        else if (SystemClock.elapsedRealtime() - lastLoad > 30_000) load(false)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun load(spinner: Boolean) {
        if (loading) {
            swipe.isRefreshing = false
            return
        }
        loading = true
        if (spinner) swipe.isRefreshing = true
        loader.execute {
            val pm = packageManager
            val ssm = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
            val user = Process.myUserHandle()
            val tasks = pm.getInstalledApplications(0).map { ai ->
                Callable<AppItem?> {
                    try {
                        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                        val cache = st.cacheBytes
                        val data = (st.dataBytes - cache).coerceAtLeast(0)
                        AppItem(ai.loadLabel(pm).toString(), ai.packageName, st.appBytes, data, cache)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val res = statsPool.invokeAll(tasks).mapNotNull {
                try { it.get() } catch (e: Exception) { null }
            }
            runOnUiThread {
                all = res
                lastLoad = SystemClock.elapsedRealtime()
                loading = false
                swipe.isRefreshing = false
                selected.retainAll(res.map { it.pkg }.toSet())
                render()
            }
        }
    }

    private fun value(a: AppItem): Long = when (sortKey) {
        0 -> a.total
        1 -> a.app
        2 -> a.data
        else -> a.cache
    }

    private fun render() {
        val sorted = if (desc) all.sortedByDescending { value(it) } else all.sortedBy { value(it) }
        adapter.submit(sorted)
        chips.forEachIndexed { i, c ->
            c.isSelected = i == sortKey
            c.setTextColor(if (i == sortKey) 0xFFFFFFFF.toInt() else 0xFFB4BAD6.toInt())
        }
        chipDir.text = if (desc) "تنازلي ↓" else "تصاعدي ↑"
        summary.text = "الإجمالي: ${fmt(all.sumOf { it.total })}  •  ${all.size} تطبيق  •  كاش: ${fmt(all.sumOf { it.cache })}"
        updateBar()
    }

    private fun updateBar() {
        val sel = all.filter { it.pkg in selected }
        btnClean.text = if (sel.isEmpty()) "مسح الكاش" else "مسح الكاش (${sel.size}) • ${fmt(sel.sumOf { it.cache })}"
        btnClean.alpha = if (sel.isEmpty()) 0.5f else 1f
        val allSel = all.isNotEmpty() && selected.size == all.size
        chipAll.isSelected = allSel
        chipAll.setTextColor(if (allSel) 0xFFFFFFFF.toInt() else 0xFFB4BAD6.toInt())
    }

    private fun startClean() {
        val targets = all.filter { it.pkg in selected && it.cache > 0 }.sortedByDescending { it.cache }
        if (selected.isEmpty()) {
            Toast.makeText(this, "اختر تطبيقًا واحدًا على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "التطبيقات المختارة ليس بها كاش", Toast.LENGTH_SHORT).show()
            return
        }
        if (CacheCleanerService.instance == null) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("مطلوب تفعيل خدمة")
                .setMessage(
                    "أندرويد لا يسمح بمسح كاش تطبيقات أخرى مباشرة، لذلك يحتاج راصد المساحة إلى خدمة تسهيل الوصول " +
                        "لتضغط زر «مسح الذاكرة المؤقتة» نيابةً عنك.\n\n" +
                        "فعّل «راصد المساحة» من: تسهيل الوصول ← التطبيقات المثبتة.\n\n" +
                        "إن كان الخيار رماديًا (أندرويد 13+): افتح معلومات التطبيق ← ⋮ ← السماح بالإعدادات المقيّدة، ثم أعد المحاولة."
                )
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton("معلومات التطبيق") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("مسح الكاش")
            .setMessage(
                "سيتم مسح كاش ${targets.size} تطبيق (${fmt(targets.sumOf { it.cache })}) تلقائيًا بفتح إعدادات كل تطبيق.\n\n" +
                    "لا تلمس الشاشة أثناء العملية، وسيعود التطبيق تلقائيًا عند الانتهاء."
            )
            .setPositiveButton("ابدأ") { _, _ ->
                selected.clear()
                CacheCleanerService.start(targets.map { it.pkg })
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun fmt(b: Long): String = Formatter.formatFileSize(this, b)

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(l: List<AppItem>) { items = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.check)
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val pkg: TextView = v.findViewById(R.id.pkg)
            val total: TextView = v.findViewById(R.id.total)
            val tApp: TextView = v.findViewById(R.id.tApp)
            val tData: TextView = v.findViewById(R.id.tData)
            val tCache: TextView = v.findViewById(R.id.tCache)
            val open: Button = v.findViewById(R.id.btnOpen)
            val info: Button = v.findViewById(R.id.btnInfo)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val h = VH(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                val k = items[pos].pkg
                if (!selected.add(k)) selected.remove(k)
                h.check.isChecked = k in selected
                updateBar()
            }
            h.open.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                val i = packageManager.getLaunchIntentForPackage(items[pos].pkg)
                if (i != null) startActivity(i)
                else Toast.makeText(this@MainActivity, "لا يمكن فتح هذا التطبيق", Toast.LENGTH_SHORT).show()
            }
            h.info.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", items[pos].pkg, null))
                )
            }
            return h
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].pkg.hashCode().toLong()

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = items[pos]
            h.name.text = a.label
            h.pkg.text = a.pkg
            h.total.text = fmt(a.total)
            h.tApp.text = fmt(a.app)
            h.tData.text = fmt(a.data)
            h.tCache.text = fmt(a.cache)
            h.check.isChecked = a.pkg in selected

            h.icon.tag = a.pkg
            val cached = icons.get(a.pkg)
            if (cached != null) {
                h.icon.setImageDrawable(cached)
            } else {
                h.icon.setImageDrawable(null)
                iconPool.execute {
                    val d = try { packageManager.getApplicationIcon(a.pkg) } catch (e: Exception) { null }
                    if (d != null) {
                        icons.put(a.pkg, d)
                        runOnUiThread { if (h.icon.tag == a.pkg) h.icon.setImageDrawable(d) }
                    }
                }
            }
        }
    }
}
