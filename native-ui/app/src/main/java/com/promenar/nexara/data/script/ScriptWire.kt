package com.promenar.nexara.data.script

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import com.promenar.nexara.domain.script.ScriptFailure
import com.promenar.nexara.domain.script.ScriptLimits

internal object ScriptWire {
    const val HELLO = 1
    const val READY = 2
    const val EXECUTE = 3
    const val RESULT = 4
    const val CAPABILITY = 5
    const val CAPABILITY_RESULT = 6
    const val TERMINATE = 7

    fun send(to: Messenger, type: Int, invocation: String = "", call: Int = 0, payload: String = "", replyTo: Messenger? = null) {
        checkPayload(payload)
        to.send(Message.obtain(null, type).apply {
            data = Bundle().apply {
                putString("invocation", invocation)
                putInt("call", call)
                putString("payload", payload)
            }
            this.replyTo = replyTo
        })
    }

    fun checkPayload(payload: String) {
        if (payload.toByteArray(Charsets.UTF_8).size > ScriptLimits.IPC_BYTES) throw ScriptFailure("IPC_LIMIT", "能力消息超过 Binder 大小上限")
    }
}
