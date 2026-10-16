package com.promenar.nexara.data.script

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** 调试源集中的内核权限探针，发行变体不包含此服务。 */
class ScriptIsolationProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("com.promenar.nexara.script.probe")
            val path = data.readString().orEmpty()
            val port = data.readInt()
            val read = runCatching { File(path).readText() }.isSuccess
            val network = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) } }.isSuccess
            reply.writeNoException()
            reply.writeInt(if (read) 1 else 0)
            reply.writeInt(if (network) 1 else 0)
            reply.writeInt(android.os.Process.myUid())
            return true
        }
    }
}
