package io.github.howard20181.hyperpasskey

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块配置界面:列出所有已安装的应用,选择其一作为首选密码管理软件。
 * 不做任何凭据服务校验,hook 侧按所选包名处理。
 * 选择通过 libxposed RemotePreferences 同步给 hook 逻辑,保存后即时生效。
 */
class ConfigActivity : Activity() {
    private var xposedService: XposedService? = null
    private val packages = ArrayList<String>()
    private var listView: ListView? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                runOnUiThread { refreshCurrentSelection() }
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })

        setTitle(R.string.config_title)
        val pm = packageManager
        val results = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
        // 按包名去重,一个应用可能声明多个桌面入口
        val apps = LinkedHashMap<String, String>()
        for (info in results) {
            val ai = info.activityInfo ?: continue
            if (apps.containsKey(ai.packageName)) continue
            apps[ai.packageName] = info.loadLabel(pm).toString().ifBlank { ai.packageName }
        }
        val sorted = apps.entries.sortedBy { it.value.lowercase() }

        val items = ArrayList<String>(sorted.size + 1)
        items.add(getString(R.string.config_none))
        packages.clear()
        packages.add("")
        for ((pkg, label) in sorted) {
            items.add(label)
            packages.add(pkg)
        }

        listView = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@ConfigActivity,
                android.R.layout.simple_list_item_single_choice,
                items
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val text = view.findViewById<TextView>(android.R.id.text1)
                    val pkg = packages[position]
                    if (pkg.isNotBlank()) {
                        text.text = "${text.text}\n$pkg"
                    }
                    return view
                }
            }
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ -> selectProvider(packages[position]) }
        }
        setContentView(listView)
        refreshCurrentSelection()
        if (packages.size <= 1) {
            Toast.makeText(this, R.string.config_no_providers, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshCurrentSelection() {
        val service = xposedService
        if (service == null) {
            Toast.makeText(this, R.string.config_service_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val pkg = service.getRemotePreferences(PREFS_GROUP)
            .getString(PREF_KEY_PREFERRED_PACKAGE, "") ?: ""
        val index = packages.indexOf(pkg)
        listView?.setItemChecked(index, true)
    }

    private fun selectProvider(pkg: String) {
        val service = xposedService
        if (service == null) {
            Toast.makeText(this, R.string.config_service_unavailable, Toast.LENGTH_LONG).show()
            refreshCurrentSelection()
            return
        }
        val editor = service.getRemotePreferences(PREFS_GROUP).edit()
        if (pkg.isBlank()) {
            editor.remove(PREF_KEY_PREFERRED_PACKAGE)
        } else {
            editor.putString(PREF_KEY_PREFERRED_PACKAGE, pkg)
        }
        editor.apply()
        Toast.makeText(
            this,
            if (pkg.isBlank()) R.string.config_cleared else R.string.config_saved,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val PREFS_GROUP = "hypermonica_config"
        const val PREF_KEY_PREFERRED_PACKAGE = "preferred_package"
    }
}
