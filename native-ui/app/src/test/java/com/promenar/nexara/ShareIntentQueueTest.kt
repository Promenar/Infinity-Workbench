package com.promenar.nexara

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentQueueTest {
    @Test
    fun `只识别SEND与SEND_MULTIPLE入口`() {
        assertThat(ShareIntentQueue.isShareIntent(Intent(Intent.ACTION_SEND))).isTrue()
        assertThat(ShareIntentQueue.isShareIntent(Intent(Intent.ACTION_SEND_MULTIPLE))).isTrue()
        assertThat(ShareIntentQueue.isShareIntent(Intent(Intent.ACTION_MAIN))).isFalse()
        assertThat(ShareIntentQueue.isShareIntent(null)).isFalse()
    }
}
