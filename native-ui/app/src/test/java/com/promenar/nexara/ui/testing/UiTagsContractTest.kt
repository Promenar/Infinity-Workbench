package com.promenar.nexara.ui.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiTagsContractTest {
    @Test
    fun approvalTags_areUniqueStableAndDoNotContainVisibleCopy() {
        val tags = listOf(
            UiTags.CHAT_APPROVAL_CARD,
            UiTags.CHAT_APPROVAL_REQUIRED,
            UiTags.CHAT_APPROVAL_EXECUTED,
            UiTags.CHAT_APPROVAL_APPROVE,
            UiTags.CHAT_APPROVAL_DECLINE,
        )

        assertThat(tags.toSet()).hasSize(tags.size)
        assertThat(tags).containsExactly(
            "chat_approval_card",
            "chat_approval_required",
            "chat_approval_executed",
            "chat_approval_approve",
            "chat_approval_decline",
        ).inOrder()
    }
}
