package com.promenar.infinity.ui.ssh

import com.promenar.infinity.data.ssh.SshFailure
import kotlinx.coroutines.CancellationException

internal fun safeSshError(error: Exception): String = when (error) {
    is CancellationException -> throw error
    is SshFailure -> error.code
    is SshInputException -> error.message ?: "SSH_INPUT_INVALID"
    else -> "SSH_OPERATION_FAILED"
}
