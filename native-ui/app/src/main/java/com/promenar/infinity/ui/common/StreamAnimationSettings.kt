package com.promenar.infinity.ui.common

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class StreamAnimationSettings(
    private val scope: CoroutineScope,
    private val observeScale: (ContentResolver) -> Flow<Boolean> = ::observeStreamAnimationScale,
) {
    private var sharedState: StateFlow<Boolean?>? = null

    @Synchronized
    fun state(resolver: ContentResolver): StateFlow<Boolean?> =
        sharedState ?: observeScale(resolver).stateIn(scope, SharingStarted.Eagerly, null).also { sharedState = it }
}

private fun observeStreamAnimationScale(resolver: ContentResolver): Flow<Boolean> = callbackFlow {
    fun refresh() {
        trySend(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            launch { refresh() }
        }
    }
    resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    refresh()
    awaitClose { resolver.unregisterContentObserver(observer) }
}.flowOn(Dispatchers.IO)
