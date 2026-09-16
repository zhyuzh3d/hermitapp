package io.github.zhyuzh3d.hermit.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import io.github.zhyuzh3d.hermit.data.HostImageStore
import io.github.zhyuzh3d.hermit.MainActivity
import io.github.zhyuzh3d.hermit.R
import io.github.zhyuzh3d.hermit.model.WebAppInstance

class ShortcutHost(private val context: Context) {
    private val manager = context.getSystemService(ShortcutManager::class.java)
    private val images = HostImageStore(context)

    enum class PinState(val value: String) {
        PINNED("pinned"),
        NOT_PINNED("notPinned"),
        UNSUPPORTED("unsupported"),
        UNKNOWN("unknown"),
    }

    /**
     * ShortcutManager is the authoritative public API for shortcuts owned by
     * this package. Call this from a worker thread: some launchers may take
     * noticeable time to return their pinned shortcut list.
     */
    fun pinStates(appIds: Collection<String>): Map<String, PinState> {
        val supported = runCatching { manager.isRequestPinShortcutSupported }.getOrNull()
        val pinnedIds = runCatching {
            manager.pinnedShortcuts.asSequence()
                .filter(ShortcutInfo::isPinned)
                .map(ShortcutInfo::getId)
                .toSet()
        }.getOrElse { return appIds.associateWith { PinState.UNKNOWN } }
        return appIds.associateWith { appId ->
            when {
                shortcutId(appId) in pinnedIds -> PinState.PINNED
                supported == true -> PinState.NOT_PINNED
                supported == false -> PinState.UNSUPPORTED
                else -> PinState.UNKNOWN
            }
        }
    }

    fun requestPin(instance: WebAppInstance): Boolean {
        if (!manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(context, shortcutId(instance.appId))
            .setShortLabel(instance.name.take(10))
            .setLongLabel(instance.name.take(25))
            .setIcon(iconFor(instance))
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
            .setIcon(iconFor(instance))
            .setIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_APP_ID, instance.appId)
            }).build()))
    }

    fun disable(appId: String) {
        manager.disableShortcuts(listOf(shortcutId(appId)), "此页面应用已被删除")
    }

    private fun shortcutId(appId: String) = "webapp-$appId"

    private fun iconFor(instance: WebAppInstance): Icon {
        val bitmap = images.open(instance.effectiveIconUrl)?.let { image ->
            runCatching { BitmapFactory.decodeFile(image.file.absolutePath) }.getOrNull()
        }
        return bitmap?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
    }
}
