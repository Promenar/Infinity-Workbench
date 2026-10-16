package com.promenar.nexara.ui.ssh

import com.promenar.nexara.data.ssh.SshFailure
import kotlinx.coroutines.CancellationException

internal fun safeSshError(error: Exception): String = when (error) {
    is CancellationException -> throw error
    is SshFailure -> error.code
    is SshInputException -> error.message ?: "SSH_INPUT_INVALID"
    else -> "SSH_OPERATION_FAILED"
}
