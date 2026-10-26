package com.promenar.nexara.domain.tool

enum class ToolOutputStream { STDOUT, STDERR }

/**
 * 运行中的工具向界面推送输出的通道，只用于展示，不改变交给模型的结果；可在任意线程调用。
 */
interface ToolLiveOutputSink {
    fun emit(stream: ToolOutputStream, text: String)

    /** PTY 回车覆盖后的有界文本快照。 */
    fun replace(stream: ToolOutputStream, text: String) = emit(stream, text)

    /** 工具让出后保留同一面板；返回值在任务结束时释放。 */
    fun retain(): () -> Unit = {}

    /** 登记单个命令的停止动作（须为非阻塞，可在主线程调用）；不登记时界面不提供停止按钮。 */
    fun onStop(stop: () -> Unit)

    /** 该后端无法提供实时输出，界面改为说明结束后显示结果。 */
    fun unsupported()

    /** 用户已在界面请求停止该命令。 */
    val stopRequested: Boolean

    object None : ToolLiveOutputSink {
        override fun emit(stream: ToolOutputStream, text: String) = Unit
        override fun onStop(stop: () -> Unit) = Unit
        override fun unsupported() = Unit
        override val stopRequested: Boolean = false
    }
}
