package com.promenar.nexara.ui.testing

/**
 * 面向自动化测试的稳定语义标识。标识描述控件职责，不依赖本地化文案或动态数据。
 */
object UiTags {
    const val BACKUP_EXPORT = "backup_export"
    const val BACKUP_IMPORT = "backup_import"
    const val BACKUP_OPERATION_STATUS = "backup_operation_status"
    const val CHAT_ROOT = "chat_root"
    const val CHAT_STATE_EMPTY = "chat_state_empty"
    const val CHAT_STATE_LOADING = "chat_state_loading"
    const val CHAT_STATE_GENERATING = "chat_state_generating"
    const val CHAT_STATE_APPROVAL = "chat_state_approval"
    const val CHAT_STATE_ERROR = "chat_state_error"
    const val CHAT_STATE_READY = "chat_state_ready"
    const val CHAT_LOADING_SKELETON = "chat_loading_skeleton"
    const val CHAT_GENERATION_ACTION = "chat_generation_action"
    const val CHAT_OPTIONS = "chat_options"
    const val CHAT_SESSION_SETTINGS = "chat_session_settings"
    const val CHAT_INPUT = "chat_input"
    const val CHAT_MODEL_SELECTOR = "chat_model_selector"
    const val CHAT_MODEL_LIST = "chat_model_list"
    private const val CHAT_MODEL_OPTION_PREFIX = "chat_model_option:"

    fun chatModelOption(modelId: String): String = CHAT_MODEL_OPTION_PREFIX + modelId
    const val CHAT_APPROVAL_CARD = "chat_approval_card"
    const val CHAT_APPROVAL_REQUIRED = "chat_approval_required"
    const val CHAT_APPROVAL_EXECUTED = "chat_approval_executed"
    const val CHAT_APPROVAL_APPROVE = "chat_approval_approve"
    const val CHAT_APPROVAL_DECLINE = "chat_approval_decline"
}
