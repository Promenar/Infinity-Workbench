package com.promenar.nexara.utils

import android.content.SharedPreferences

/**
 * 把 SharedPreferences 变更回调推迟到写入方的调用栈之外执行。
 * 写入方可能持锁 apply()（如 ProviderManager 的 @Synchronized 方法），主线程上的 apply 会同步回调监听器；
 * 监听器此时重建组件并获取其它锁，而另一线程持有那把锁又反过来读取写入方，就会锁顺序反转而死锁。
 */
class DeferredPreferenceChangeListener(
    private val post: (Runnable) -> Unit,
    private val onChanged: (key: String?) -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        post(Runnable { onChanged(key) })
    }
}
