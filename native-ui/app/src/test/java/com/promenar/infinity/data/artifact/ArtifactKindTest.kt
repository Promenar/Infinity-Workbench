package com.promenar.infinity.data.artifact

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.canvas.CanvasKind
import org.junit.Test

class ArtifactKindTest {
    @Test
    fun documentsImagesAndPagesFollowExtension_caseInsensitive() {
        assertThat(artifactKindFor("需求说明.MD", null)).isEqualTo(ArtifactKind.DOCUMENT)
        assertThat(artifactKindFor("report.pdf", null)).isEqualTo(ArtifactKind.DOCUMENT)
        assertThat(artifactKindFor("plan.docx", null)).isEqualTo(ArtifactKind.DOCUMENT)
        assertThat(artifactKindFor("cover.PNG", null)).isEqualTo(ArtifactKind.IMAGE)
        assertThat(artifactKindFor("arch.svg", null)).isEqualTo(ArtifactKind.IMAGE)
        assertThat(artifactKindFor("index.html", null)).isEqualTo(ArtifactKind.PAGE)
    }

    @Test
    fun mimeDecidesWhenExtensionIsUnknown_andEverythingElseIsOther() {
        assertThat(artifactKindFor("photo", "image/jpeg")).isEqualTo(ArtifactKind.IMAGE)
        assertThat(artifactKindFor("notes", "text/markdown")).isEqualTo(ArtifactKind.DOCUMENT)
        assertThat(artifactKindFor("sales.csv", "text/csv")).isEqualTo(ArtifactKind.OTHER)
        assertThat(artifactKindFor("bundle.zip", null)).isEqualTo(ArtifactKind.OTHER)
        assertThat(artifactKindFor("Makefile", null)).isEqualTo(ArtifactKind.OTHER)
    }

    @Test
    fun canvasKindMapsToPageOrPrototype() {
        assertThat(CanvasKind.PAGE.artifactKind).isEqualTo(ArtifactKind.PAGE)
        assertThat(CanvasKind.PROTOTYPE.artifactKind).isEqualTo(ArtifactKind.PROTOTYPE)
    }
}
