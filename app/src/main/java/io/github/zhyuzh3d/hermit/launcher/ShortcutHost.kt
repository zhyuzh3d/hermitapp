package io.github.zhyuzh3d.hermit.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.zhyuzh3d.hermit.MainActivity
import io.github.zhyuzh3d.hermit.R
import io.github.zhyuzh3d.hermit.model.WebAppInstance

class ShortcutHost(private val context: Context) {
    private val manager = context.getSystemService(ShortcutManager::class.java)

    fun requestPin(instance: WebAppInstance): Boolean {
        if (!manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(context, shortcutId(instance.appId))
            .setShortLabel(instance.name.take(10))
            .setLongLabel(instance.name.take(25))
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_APP_ID, instance.appId)
            })
            .build()
        return manager.requestPinShortcut(shortcut, null)
    }

    fun update(instance: WebAppInstance) {
        manager.updateShortcuts(listOf(ShortcutInfo.Builder(context, shortcutId(instance.appId))
            .setShortLabel(instance.name.take(10)).setLongLabel(instance.name.take(25))
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_APP_ID, instance.appId)
            }).build()))
    }

    fun disable(appId: String) {
        manager.disableShortcuts(listOf(shortcutId(appId)), "此页面应用已被删除")
    }

    private fun shortcutId(appId: String) = "webapp-$appId"
}
