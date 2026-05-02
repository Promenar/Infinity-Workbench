package com.promenar.nexara.native.bridge

import com.facebook.react.bridge.*

class NexaraBridgeModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "NexaraBridge"

    @ReactMethod
    fun updateAgents(jsonString: String) {
        NexaraBridge.updateAgents(jsonString)
    }

    @ReactMethod
    fun updateSessions(jsonString: String) {
        NexaraBridge.updateSessions(jsonString)
    }

    @ReactMethod
    fun setCurrentSession(sessionId: String?) {
        NexaraBridge.currentSessionId.value = sessionId
    }

    @ReactMethod
    fun openNativeChat() {
        val intent = android.content.Intent(reactApplicationContext, com.promenar.nexara.native.NativeChatActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        reactApplicationContext.startActivity(intent)
    }

    // 预留给 JS 发送实时 Token 的方法
    @ReactMethod
    fun onNewToken(token: String) {
        // TODO
    }
}
