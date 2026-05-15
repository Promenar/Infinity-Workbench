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
data class BackupFileEntry(
    val uuid: String,
    val parentUuid: String? = null,
    val name: String,
    val hash: String,
    val sizeBytes: Long = 0,
    val isDirectory: Boolean = false,
    val materializedPath: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupDataPackage(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val agents: List<AgentEntity> = emptyList(),
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val skills: List<CustomSkillEntity> = emptyList(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val documents: List<BackupFileEntry> = emptyList()
)

class BackupRepository(private val context: Context) {
    private val app = context.applicationContext as NexaraApplication
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun prepareBackupPackage(state: BackupUiState, onProgress: (Float, String) -> Unit = { _, _ -> }): ByteArray {
        onProgress(0.1f, "Fetching Agents...")
        val agents = if (state.settingsChecked) app.database.agentDao().getAll() else emptyList()
        
        onProgress(0.3f, "Fetching Sessions...")
        val sessions = if (state.sessionsChecked) app.database.sessionDao().getAll() else emptyList()
        
        onProgress(0.5f, "Fetching Messages...")
        val messages = if (state.sessionsChecked) {
            sessions.flatMap { app.database.messageDao().getBySession(it.id) }
        } else emptyList()

        onProgress(0.7f, "Fetching Skills & MCP...")
        val skills = if (state.keysChecked) app.database.skillDao().getAllCustomSkills().first() else emptyList()
        val mcpServers = if (state.keysChecked) app.database.skillDao().getAllMcpServers().first() else emptyList()
        
        onProgress(0.8f, "Fetching Library Documents...")
        val fileEntries = if (state.libraryChecked) app.database.fileEntryDao().observeRoots().first() else emptyList()
        val documents = fileEntries.map { BackupFileEntry(it.uuid, it.parentUuid, it.name, it.hash, it.sizeBytes, it.isDirectory, it.materializedPath, it.createdAt, it.updatedAt) }

        val pkg = BackupDataPackage(
            agents = agents,
            sessions = sessions,
            messages = messages,
            skills = skills,
            mcpServers = mcpServers,
            documents = documents
        )

        onProgress(0.9f, "Compressing Data...")
        val jsonString = json.encodeToString(pkg)
        return compress(jsonString)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadToWebDav(state: BackupUiState, onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean {
        val data = prepareBackupPackage(state, onProgress)
        onProgress(0.95f, "Uploading to WebDAV...")
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
    suspend fun restoreFromWebDav(state: BackupUiState, remoteFileName: String, onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean {
        val url = if (state.webdavUrl.endsWith("/")) "${state.webdavUrl}$remoteFileName" else "${state.webdavUrl}/$remoteFileName"
        val auth = Base64.encode("${state.webdavUser}:${state.webdavPass}".toByteArray())

        return try {
            onProgress(0.1f, "Downloading from WebDAV...")
            val response: HttpResponse = app.httpClient.get(url) {
                header(HttpHeaders.Authorization, "Basic $auth")
            }
            if (response.status.isSuccess()) {
                restoreFromPackage(response.bodyAsText().byteInputStream(), onProgress)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restoreFromPackage(inputStream: InputStream, onProgress: (Float, String) -> Unit = { _, _ -> }) {
        onProgress(0.2f, "Reading Package...")
        val compressedData = inputStream.readBytes()
        onProgress(0.3f, "Decompressing...")
        val jsonString = decompress(compressedData)
        onProgress(0.4f, "Parsing JSON...")
        val pkg = json.decodeFromString<BackupDataPackage>(jsonString)

        onProgress(0.5f, "Restoring Agents...")
        pkg.agents.forEach { app.database.agentDao().insert(it) }
        
        onProgress(0.6f, "Restoring Sessions...")
        pkg.sessions.forEach { app.database.sessionDao().insert(it) }
        
        onProgress(0.8f, "Restoring Messages (${pkg.messages.size})...")
        pkg.messages.forEach { app.database.messageDao().insert(it) }
        
        onProgress(0.9f, "Restoring Skills & Documents...")
        pkg.skills.forEach { app.database.skillDao().insertCustomSkill(it) }
        pkg.mcpServers.forEach { app.database.skillDao().insertMcpServer(it) }
        pkg.documents.forEach { backup ->
            app.database.fileEntryDao().insert(FileEntry(
                uuid = backup.uuid,
                parentUuid = backup.parentUuid,
                name = backup.name,
                hash = backup.hash,
                sizeBytes = backup.sizeBytes,
                isDirectory = backup.isDirectory,
                physicalRootPath = backup.materializedPath.substringBeforeLast("/").ifBlank { "/" },
                materializedPath = backup.materializedPath,
                createdAt = backup.createdAt,
                updatedAt = backup.updatedAt
            ))
        }
        onProgress(1.0f, "Restore Complete")
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
