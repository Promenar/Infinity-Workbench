package com.promenar.infinity.data.artifact

import com.promenar.infinity.data.canvas.CanvasStore

/** 在既有分支与删除入口组合两类会话私有存储。 */
class ArtifactLifecycleCanvasStore(
    private val delegate: CanvasStore,
    private val artifacts: () -> FileSessionArtifacts,
) : CanvasStore by delegate {
    override suspend fun deleteSession(sessionId: String) {
        var failure: Throwable? = null
        try { delegate.deleteSession(sessionId) } catch (e: Throwable) { failure = e }
        try { artifacts().deleteSession(sessionId) } catch (e: Throwable) {
            if (failure == null) failure = e else if (failure !== e) failure.addSuppressed(e)
        }
        failure?.let { throw it }
    }

    override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) {
        delegate.copySession(sourceSessionId, targetSessionId, messageIdMap)
        try { artifacts().copySession(sourceSessionId, targetSessionId, messageIdMap) }
        catch (failure: Throwable) {
            try { delegate.deleteSession(targetSessionId) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
