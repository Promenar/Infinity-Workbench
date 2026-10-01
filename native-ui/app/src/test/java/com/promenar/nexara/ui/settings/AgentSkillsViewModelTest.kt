package com.promenar.nexara.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.skills.AgentSkillStore
import com.promenar.nexara.data.skills.InMemoryPreferences
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSkillsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = AgentSkillStore(Files.createTempDirectory("skills-vm").toFile(), InMemoryPreferences())
    private var payload: ByteArray = ByteArray(0)
    private val viewModel by lazy {
        AgentSkillsViewModel(store, openInput = { ByteArrayInputStream(payload) }, ioDispatcher = dispatcher)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun md(name: String, body: String = "Body") = "---\nname: $name\ndescription: Demo\n---\n$body".toByteArray()

    @Test
    fun `imports markdown and zip by content sniffing`() = runTest(dispatcher) {
        payload = md("from-md")
        viewModel.import(mockk<Uri>())
        payload = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write(md("from-zip"))
                zip.closeEntry()
            }
        }.toByteArray()
        viewModel.import(mockk<Uri>())

        assertThat(viewModel.skills.value.map { it.metadata.name }).containsExactly("from-md", "from-zip")
        assertThat(viewModel.event.value).isEqualTo(AgentSkillEvent.Installed("from-zip"))
    }

    @Test
    fun `conflict offers replace retry and failures surface message`() = runTest(dispatcher) {
        payload = md("dup", "v1")
        viewModel.import(mockk<Uri>())
        payload = md("dup", "v2")
        viewModel.import(mockk<Uri>())

        val conflict = viewModel.event.value as AgentSkillEvent.Conflict
        conflict.retry()
        assertThat(store.readDocument("dup")!!.body).isEqualTo("v2")

        payload = byteArrayOf(0, 1, 2)
        viewModel.import(mockk<Uri>())
        assertThat(viewModel.event.value).isInstanceOf(AgentSkillEvent.Failed::class.java)
    }
}
