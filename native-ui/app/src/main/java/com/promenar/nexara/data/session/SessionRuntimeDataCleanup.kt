package com.promenar.nexara.data.session

/** 私有数据分别清理，一类失败不得跳过另一类；调用方负责不可取消的删除收尾。 */
suspend fun cleanupSessionRuntimeData(
    deleteGrants: suspend () -> Unit,
    deleteOutputs: suspend () -> Unit,
    deleteMounts: suspend () -> Unit = {},
    deleteFolds: suspend () -> Unit = {},
    deleteSubagents: suspend () -> Unit = {},
    deleteComposerQueue: suspend () -> Unit = {},
    deleteCanvases: suspend () -> Unit = {},
    deleteSchedules: suspend () -> Unit = {},
    deleteTriggerInteraction: suspend () -> Unit = {},
) {
    var failure: Throwable? = null
    try { deleteGrants() } catch (error: Throwable) { failure = error }
    try { deleteOutputs() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteMounts() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteFolds() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteSubagents() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteComposerQueue() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteCanvases() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteSchedules() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    try { deleteTriggerInteraction() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
