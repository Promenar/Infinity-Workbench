package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocEditorScreenStateTest {
    @Test
    fun `pending索引告警与编辑器phase正交且只由pending事实决定`() {
        listOf(
            DocEditorPhase.LoadError,
            DocEditorPhase.Ready,
            DocEditorPhase.SaveError,
            DocEditorPhase.SaveConflict,
        ).forEach { phase ->
            assertThat(
                shouldShowDocEditorPendingIndexNotice(
                    DocEditorUiState(
                        phase = phase,
                        indexQueueFailed = true,
                        indexPendingTargets = listOf(
                            com.promenar.nexara.domain.repository.RenameIndexTarget(
                                fileUuid = "doc",
                                targetHash = "hash",
                                targetEpoch = 1L,
                            ),
                        ),
                    ),
                ),
            ).isTrue()
        }
        assertThat(
            shouldShowDocEditorPendingIndexNotice(
                DocEditorUiState(indexQueueFailed = true, indexPendingTargets = emptyList()),
            ),
        ).isFalse()
    }

    @Test
    fun `dirty 返回必须确认而 clean 返回直接导航`() {
        assertThat(docEditorBackDecision(isDirty = true))
            .isEqualTo(DocEditorBackDecision.ConfirmDiscard)
        assertThat(docEditorBackDecision(isDirty = false))
            .isEqualTo(DocEditorBackDecision.NavigateBack)
    }

    @Test
    fun `Saving 返回始终留在编辑器`() {
        assertThat(
            docEditorBackDecision(
                isDirty = true,
                phase = DocEditorPhase.Saving,
            ),
        ).isEqualTo(DocEditorBackDecision.StaySaving)
        assertThat(
            docEditorBackDecision(
                isDirty = false,
                phase = DocEditorPhase.Saving,
            ),
        ).isEqualTo(DocEditorBackDecision.StaySaving)
    }

    @Test
    fun `Split 只在可用宽度达到 720dp 时出现`() {
        assertThat(isDocEditorSplitAvailable(719.99f)).isFalse()
        assertThat(isDocEditorSplitAvailable(720f)).isTrue()
        assertThat(isDocEditorSplitAvailable(840f)).isTrue()
    }

    @Test
    fun `通知双动作在窄屏或大字体下纵向堆叠`() {
        assertThat(shouldStackDocEditorNoticeActions(360f, 1f)).isTrue()
        assertThat(shouldStackDocEditorNoticeActions(840f, 2f)).isTrue()
        assertThat(shouldStackDocEditorNoticeActions(840f, 1f)).isFalse()
    }

    @Test
    fun `phase 与文档事实映射为唯一可见状态`() {
        assertThat(DocEditorUiState(phase = DocEditorPhase.Loading).toVisibleState())
            .isEqualTo(DocEditorVisibleState.Loading)
        assertThat(DocEditorUiState(phase = DocEditorPhase.LoadError).toVisibleState())
            .isEqualTo(DocEditorVisibleState.LoadError)
        assertThat(DocEditorUiState(phase = DocEditorPhase.Ready).toVisibleState())
            .isEqualTo(DocEditorVisibleState.Ready)
        assertThat(DocEditorUiState(phase = DocEditorPhase.Saving).toVisibleState())
            .isEqualTo(DocEditorVisibleState.Saving)
        assertThat(DocEditorUiState(phase = DocEditorPhase.SaveError).toVisibleState())
            .isEqualTo(DocEditorVisibleState.SaveError)
        assertThat(DocEditorUiState(phase = DocEditorPhase.SaveConflict).toVisibleState())
            .isEqualTo(DocEditorVisibleState.SaveConflict)
        assertThat(
            DocEditorUiState(
                phase = DocEditorPhase.NotFound,
                hasLoadedDocument = false,
                contentDirty = false,
            ).toVisibleState(),
        ).isEqualTo(DocEditorVisibleState.NotFoundOnLoad)
        assertThat(
            DocEditorUiState(
                phase = DocEditorPhase.NotFound,
                hasLoadedDocument = true,
                contentDirty = true,
            ).toVisibleState(),
        ).isEqualTo(DocEditorVisibleState.NotFoundAfterSave)
        assertThat(
            DocEditorUiState(
                phase = DocEditorPhase.Ready,
                hasLoadedDocument = true,
                contentAccess = DocEditorContentAccess.MetadataOnly,
            ).toVisibleState(),
        ).isEqualTo(DocEditorVisibleState.MetadataOnly)
        assertThat(
            DocEditorUiState(
                phase = DocEditorPhase.Ready,
                hasLoadedDocument = true,
                contentAccess = DocEditorContentAccess.PerformanceProtected,
            ).toVisibleState(),
        ).isEqualTo(DocEditorVisibleState.PerformanceProtected)

        mapOf(
            DocEditorPhase.Saving to DocEditorVisibleState.Saving,
            DocEditorPhase.SaveError to DocEditorVisibleState.SaveError,
            DocEditorPhase.SaveConflict to DocEditorVisibleState.SaveConflict,
            DocEditorPhase.NotFound to DocEditorVisibleState.NotFoundAfterSave,
        ).forEach { (phase, expected) ->
            assertThat(
                DocEditorUiState(
                    phase = phase,
                    hasLoadedDocument = true,
                    contentDirty = true,
                    contentAccess = DocEditorContentAccess.PerformanceProtected,
                ).toVisibleState(),
            ).isEqualTo(expected)
        }
    }
}
