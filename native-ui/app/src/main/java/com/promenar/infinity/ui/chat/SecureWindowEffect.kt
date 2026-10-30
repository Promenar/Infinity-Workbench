package com.promenar.infinity.ui.chat

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * 窗口 FLAG_SECURE 的引用计数：同一窗口上多张保密卡并存时，最后一个释放者才清除标志；
 * 首次加锁前窗口已带 FLAG_SECURE（别处设置的）时，释放后保持原样。只在主线程使用。
 */
internal class SecureWindowCounter<K : Any> {
    private class Entry(var count: Int, val wasSecure: Boolean)

    private val entries = WeakHashMap<K, Entry>()

    /** [isSecure] 读窗口当前是否已安全，[setSecure] 改写标志。 */
    fun acquire(key: K, isSecure: () -> Boolean, setSecure: (Boolean) -> Unit) {
        val entry = entries[key]
        if (entry != null) {
            entry.count += 1
            return
        }
        val wasSecure = isSecure()
        entries[key] = Entry(1, wasSecure)
        if (!wasSecure) setSecure(true)
    }

    fun release(key: K, setSecure: (Boolean) -> Unit) {
        val entry = entries[key] ?: return
        entry.count -= 1
        if (entry.count > 0) return
        entries.remove(key)
        if (!entry.wasSecure) setSecure(false)
    }

    fun holders(key: K): Int = entries[key]?.count ?: 0
}

private val secureWindows = SecureWindowCounter<android.view.Window>()

/**
 * [active] 期间给当前 Activity 窗口加 FLAG_SECURE：禁止截屏、录屏与最近任务预览。
 * 多处同时生效时按计数保留，任一处结束不会清掉其他卡的保护。预览与无 Activity 时不做任何事。
 */
@Composable
internal fun SecureWindowEffect(active: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, active) {
        if (!active) return@DisposableEffect onDispose { }
        val set: (Boolean) -> Unit = { secure ->
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        secureWindows.acquire(window, { window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }, set)
        onDispose { secureWindows.release(window, set) }
    }
}
