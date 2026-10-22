package com.promenar.nexara.data.chat.queue

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.MessageDocumentAttachment
import org.junit.Test

class ComposerDraftRecoveryStoreTest {
    @Test fun `失败草稿按会话完整保留并只交接一次`() {
        val store = ComposerDraftRecoveryStore()
        val doc = MessageDocumentAttachment("d", "a.txt", "text/plain", "内容", 6, "hash", 2)
        val draft = RecoverableComposerDraft("文字", listOf("content://image"), listOf(doc))
        store.retain("A", draft)
        store.retain("B", draft.copy(text = "B"))
        assertThat(store.take("A")).containsExactly(draft)
        assertThat(store.take("A")).isEmpty()
        assertThat(store.take("B").single().text).isEqualTo("B")
    }

    @Test fun `会话删除清理失败草稿而不影响其它会话`() {
        val store = ComposerDraftRecoveryStore()
        val draft = RecoverableComposerDraft("文字", emptyList(), emptyList())
        store.retain("A", draft)
        store.retain("A", draft.copy(text = "另一条"))
        store.retain("B", draft)
        store.deleteSession("A")
        assertThat(store.take("A")).isEmpty()
        assertThat(store.take("B")).containsExactly(draft)
    }
}
