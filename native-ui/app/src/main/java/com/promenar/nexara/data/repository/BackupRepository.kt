package com.promenar.nexara.data.repository

import android.content.Context
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.*
import com.promenar.nexara.ui.settings.BackupUiState
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class BackupDataPackage(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val agents: List<AgentEntity> = emptyList(),
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val skills: List<CustomSkillEntity> = emptyList(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val documents: List<DocumentEntity> = emptyList()
)

class BackupRepository(private val context: Context) {
    private val app = context.applicationContext as NexaraApplication
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun prepareBackupPackage(state: BackupUiState): ByteArray {
        val agents = if (state.settingsChecked) app.database.agentDao().getAll() else emptyList()
        val sessions = if (state.sessionsChecked) app.database.sessionDao().getAll() else emptyList()
        
        val messages = if (state.sessionsChecked) {
            sessions.flatMap { app.database.messageDao().getBySession(it.id) }
        } else emptyList()

        val skills = if (state.keysChecked) app.database.skillDao().getAllCustomSkills().first() else emptyList()
        val mcpServers = if (state.keysChecked) app.database.skillDao().getAllMcpServers().first() else emptyList()
        
        val documents = if (state.libraryChecked) app.database.documentDao().observeAll().first() else emptyList()

        val pkg = BackupDataPackage(
            agents = agents,
            sessions = sessions,
            messages = messages,
            skills = skills,
            mcpServers = mcpServers,
            documents = documents
        )

        val jsonString = json.encodeToString(pkg)
        return compress(jsonString)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadToWebDav(state: BackupUiState): Boolean {
        val data = prepareBackupPackage(state)
        val fileName = "nexara_backup_${System.currentTimeMillis()}.nexara"
        val url = if (state.webdavUrl.endsWith("/")) "${state.webdavUrl}$fileName" else "${state.webdavUrl}/$fileName"
        
        val auth = Base64.encode("${state.webdavUser}:${state.webdavPass}".toByteArray())
        
        return try {
            val response: HttpResponse = app.httpClient.put(url) {
                header(HttpHeaders.Authorization, "Basic $auth")
                setBody(data)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun restoreFromWebDav(state: BackupUiState, remoteFileName: String): Boolean {
        val url = if (state.webdavUrl.endsWith("/")) "${state.webdavUrl}$remoteFileName" else "${state.webdavUrl}/$remoteFileName"
        val auth = Base64.encode("${state.webdavUser}:${state.webdavPass}".toByteArray())

        return try {
            val response: HttpResponse = app.httpClient.get(url) {
                header(HttpHeaders.Authorization, "Basic $auth")
            }
            if (response.status.isSuccess()) {
                restoreFromPackage(response.bodyAsText().byteInputStream())
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restoreFromPackage(inputStream: InputStream) {
        val compressedData = inputStream.readBytes()
        val jsonString = decompress(compressedData)
        val pkg = json.decodeFromString<BackupDataPackage>(jsonString)

        pkg.agents.forEach { app.database.agentDao().insert(it) }
        pkg.sessions.forEach { app.database.sessionDao().insert(it) }
        pkg.messages.forEach { app.database.messageDao().insert(it) }
        pkg.skills.forEach { app.database.skillDao().insertCustomSkill(it) }
        pkg.mcpServers.forEach { app.database.skillDao().insertMcpServer(it) }
        pkg.documents.forEach { app.database.documentDao().insert(it) }
    }

    private fun compress(data: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return bos.toByteArray()
    }

    private fun decompress(data: ByteArray): String {
        return GZIPInputStream(data.inputStream()).bufferedReader().use { it.readText() }
    }
}
