package com.promenar.nexara.ui.chat

import kotlinx.coroutines.CancellationException

/** 只有完整删除成功才发出导航回调；普通错误留在当前页面供用户重试。 */
class SessionDeletionUiAction(
    private val delete: suspend () -> Unit,
) {
    suspend fun run(onDeleted: () -> Unit): Throwable? = try {
        delete()
        onDeleted()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failure
    }
}
