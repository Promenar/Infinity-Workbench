package com.promenar.infinity.data.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UnlimitedAutomationConsentTest {
    @Test
    fun `确认只在不限全自动组合触发且重建后保留并可重置`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("p0-consent-test", 0)
        prefs.edit().clear().commit()
        val consent = UnlimitedAutomationConsent(prefs)
        assertThat(consent.requiresConfirmation(true, "auto")).isTrue()
        assertThat(consent.requiresConfirmation(false, "auto")).isFalse()
        assertThat(consent.requiresConfirmation(true, "semi")).isFalse()
        consent.confirm()
        val rebuilt = UnlimitedAutomationConsent(context.getSharedPreferences("p0-consent-test", 0))
        assertThat(rebuilt.requiresConfirmation(true, "auto")).isFalse()
        rebuilt.reset()
        assertThat(UnlimitedAutomationConsent(prefs).requiresConfirmation(true, "auto")).isTrue()
        prefs.edit().clear().commit()
    }
}
