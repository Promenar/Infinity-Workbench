package com.promenar.infinity.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.jobs.BackgroundJobLog
import com.promenar.infinity.ui.common.MonospaceTextDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 后台任务日志存放在应用私有目录，按任务引用读取末尾内容，不再跳转工作区。 */
@Composable
fun BackgroundJobLogDialog(
    controller: BackgroundJobController,
    sessionId: String,
    reference: String,
    onDismiss: () -> Unit,
) {
    var log by remember(reference) { mutableStateOf<BackgroundJobLog?>(null) }
    var failed by remember(reference) { mutableStateOf(false) }
    LaunchedEffect(sessionId, reference) {
        try {
            log = withContext(Dispatchers.IO) { controller.readLog(sessionId, reference) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val current = log
    MonospaceTextDialog(
        title = stringResource(R.string.agent_jobs_log_title),
        message = when {
            failed -> stringResource(R.string.agent_jobs_log_failed)
            current == null -> stringResource(R.string.agent_jobs_log_loading)
            current.truncated -> stringResource(R.string.agent_jobs_log_truncated)
            else -> ""
        },
        text = when {
            current == null -> ""
            current.text.isEmpty() -> stringResource(R.string.agent_jobs_log_empty)
            else -> current.text
        },
        onDismiss = onDismiss,
    )
}
