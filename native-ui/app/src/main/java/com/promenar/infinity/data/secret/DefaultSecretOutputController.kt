package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.*
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer

class DefaultSecretOutputController(
    private val vault: SessionSecretVault,
    private val destinations: SecretDestinationWriter,
) : SecretOutputController {
    override fun observeCards(sessionId: String) = vault.observeCards(sessionId)
    override fun reveal(sessionId: String, name: String): CharArray? {
        val bytes = vault.revealBytes(sessionId, name) ?: return null
        return try {
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            CharArray(decoded.remaining()).also { decoded.get(it); if (decoded.hasArray()) decoded.array().fill('\u0000') }
        } finally { bytes.fill(0) }
    }
    override suspend fun saveAs(sessionId: String, name: String, destination: SecretDestination, username: String?): SecretInputOutcome {
        if (!destination.isPersistentCredential()) return failed("SECRET_DESTINATION_NOT_PERSISTENT")
        val bytes = vault.revealBytes(sessionId, name) ?: return failed("SECRET_SLOT_NOT_FOUND")
        return try {
            destinations.describe(sessionId, destination)
            destinations.write(sessionId, destination, bytes, username)
            SecretInputOutcome.Saved(destinationType(destination))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: SecretIoFailure) { failed(failure.code)
        } catch (_: Exception) { failed("SECRET_WRITE_FAILED")
        } finally { bytes.fill(0) }
    }
    override fun destroy(sessionId: String, name: String) = vault.destroy(sessionId, name)
    private fun failed(code: String) = SecretInputOutcome.Failed(code, "保密信息未保存，请检查去向后重试。")
}
