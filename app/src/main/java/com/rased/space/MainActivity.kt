package com.rased.space

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val pkg: String,
    val icon: Drawable,
    val app: Long,
    val data: Long,   // البيانات بدون الكاش
    val cache: Long
) {
    val total: Long get() = app + data + cache
}

class MainActivity : Activity() {

    private var all: List<AppItem> = emptyList()
    private var sortKey = 0          // 0 الإجمالي, 1 التطبيق, 2 البيانات, 3 الكاش
    private var desc = true
    private val pool = Executors.newSingleThreadExecutor()

    private lateinit var adapter: Adapter
    private lateinit var summary: TextView
    private lateinit var permPanel: LinearLayout
    private lateinit var progress: View
    private lateinit var chips: List<TextView>
    private lateinit var chipDir: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        summary = findViewById(R.id.summary)
        permPanel = findViewById(R.id.permPanel)
        progress = findViewById(R.id.progress)
        chipDir = findViewById(R.id.chipDir)
        chips = listOf(
            findViewById(R.id.chipTotal), findViewById(R.id.chipApp),
            findViewById(R.id.chipData), findViewById(R.id.chipCache)
        )

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        chips.forEachIndexed { i, c ->
            c.setOnClickListener { sortKey = i; render() }
        }
        chipDir.setOnClickListener { desc = !desc; render() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageAccess()) {
            permPanel.visibility = View.GONE
            load()
        } else {
            permPanel.visibility = View.VISIBLE
        }
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

    private fun load() {
        progress.visibility = View.VISIBLE
        pool.execute {
            val pm = packageManager
            val ssm = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
            val user = Process.myUserHandle()
            val list = ArrayList<AppItem>()
            for (ai in pm.getInstalledApplications(0)) {
                try {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                    val cache = st.cacheBytes
                    val data = (st.dataBytes - cache).coerceAtLeast(0)
                    list.add(AppItem(ai.loadLabel(pm).toString(), ai.packageName,
                        ai.loadIcon(pm), st.appBytes, data, cache))
                } catch (_: Exception) { }
            }
            runOnUiThread {
                all = list
                progress.visibility = View.GONE
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
        summary.text = "الإجمالي: ${fmt(all.sumOf { it.total })}  •  ${all.size} تطبيق"
    }

    private fun fmt(b: Long): String = Formatter.formatFileSize(this, b)

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(l: List<AppItem>) { items = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
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

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = items[pos]
            h.icon.setImageDrawable(a.icon)
            h.name.text = a.label
            h.pkg.text = a.pkg
            h.total.text = fmt(a.total)
            h.tApp.text = fmt(a.app)
            h.tData.text = fmt(a.data)
            h.tCache.text = fmt(a.cache)
            h.open.setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage(a.pkg)
                if (i != null) startActivity(i)
                else Toast.makeText(this@MainActivity, "لا يمكن فتح هذا التطبيق", Toast.LENGTH_SHORT).show()
            }
            h.info.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.pkg, null)))
            }
        }
    }
}
