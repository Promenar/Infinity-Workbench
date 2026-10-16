package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxCommandResult
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertThrows
import org.junit.Test

class TermuxSetupSupportTest {
    @Test
    fun `shell arguments preserve apostrophes and expansion syntax as literal data`() {
        assertThat(TermuxSetupSupport.shellQuote("a'b \$(id); `id`"))
            .isEqualTo("'a'\"'\"'b \$(id); `id`'")
        assertThat(TermuxSetupSupport.shellQuote("")).isEqualTo("''")
    }

    @Test
    fun `shell arguments reject nul instead of silently changing content`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxSetupSupport.shellQuote("public\u0000data")
        }
    }

    @Test
    fun `authorized keys command appends one public key with restrictive permissions`() {
        assertThat(TermuxSetupSupport.authorizedKeysCommand("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample nexara"))
            .isEqualTo(
                "(umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "printf '\\n%s\\n' 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample nexara' " +
                    ">> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys)",
            )
    }

    @Test
    fun `authorized keys command safely quotes shell syntax in the key comment`() {
        assertThat(TermuxSetupSupport.authorizedKeysCommand("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQExample alice's \$(id)"))
            .contains("'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQExample alice'\"'\"'s \$(id)'")
    }

    @Test
    fun `authorized keys command preserves an unterminated existing key and file permissions`() {
        val newKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINewExample nexara"
        val command = TermuxSetupSupport.authorizedKeysCommand(newKey)
        val shell = listOf("/bin/sh", "/usr/bin/sh").firstOrNull { File(it).canExecute() }
        if (shell == null) {
            // 无 POSIX shell 的主机仍校验追加命令会先分隔既有末行。
            assertThat(command).contains("printf '\\n%s\\n' ")
            return
        }
        val fixtureRoot = File("build/tmp/termux-setup-tests").apply { mkdirs() }.toPath()
        val fixture = Files.createTempDirectory(fixtureRoot, "keys-").toFile()
        try {
            val sshDirectory = fixture.resolve("ssh's test folder").toPath().toAbsolutePath()
            Files.createDirectories(sshDirectory)
            val authorizedKeys = sshDirectory.resolve("authorized_keys")
            val oldKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOldExample existing"
            authorizedKeys.toFile().writeText(oldKey, Charsets.UTF_8)
            Files.setPosixFilePermissions(authorizedKeys, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
            ))
            val fixtureCommand = command.replace("~/.ssh", TermuxSetupSupport.shellQuote(sshDirectory.toString()))
            val process = ProcessBuilder(shell, "-c", fixtureCommand).redirectErrorStream(true).start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertThat(completed).isTrue()
            assertThat(process.exitValue()).isEqualTo(0)
            assertThat(authorizedKeys.toFile().readText(Charsets.UTF_8)).isEqualTo("$oldKey\n$newKey\n")
            assertThat(Files.getPosixFilePermissions(authorizedKeys))
                .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun `authorized keys command rejects multiline keys and private material`() {
        listOf(
            "",
            "ssh-ed25519 AAAA nexara\nssh-rsa AAAA other",
            "ssh-ed25519 AAAA nexara\rignored",
            "ssh-ed25519 AAAA nexara\u0000ignored",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "echo arbitrary-command",
        ).forEach { input ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                TermuxSetupSupport.authorizedKeysCommand(input)
            }
            assertThat(failure.message).doesNotContain(input.takeIf { it.isNotEmpty() } ?: "PRIVATE KEY")
        }
    }

    @Test
    fun `mount aliases are trimmed and checked against visible aliases`() {
        assertThat(TermuxSetupSupport.normalizeMountAlias("  termux-home  ", setOf("documents")))
            .isEqualTo("termux-home")
        assertThrows(IllegalArgumentException::class.java) {
            TermuxSetupSupport.normalizeMountAlias(" termux ", setOf("termux"))
        }
    }

    @Test
    fun `mount aliases reject traversal reserved names and control characters`() {
        listOf("", ".", "..", "mnt", "a/b", "a\\b", ".nexara-cache", "home\nother", "home\u0000other").forEach { alias ->
            assertThrows(IllegalArgumentException::class.java) {
                TermuxSetupSupport.normalizeMountAlias(alias, emptySet())
            }
        }
    }

    @Test
    fun `default mount alias selects the first available suffix`() {
        assertThat(TermuxSetupSupport.availableMountAlias(emptySet())).isEqualTo("termux")
        assertThat(TermuxSetupSupport.availableMountAlias(setOf("termux", "termux-2", "termux-4")))
            .isEqualTo("termux-3")
    }

    @Test
    fun `diagnostic succeeds only for the expected marker and a clean exit`() {
        assertThat(TermuxSetupSupport.diagnosticSucceeded(TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n", exitCode = 0)))
            .isTrue()
        listOf(
            TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n", exitCode = 1),
            TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n"),
            TermuxCommandResult(stdout = "unexpected\n", exitCode = 0),
            TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n", exitCode = 0, errorCode = "TERMUX_PERMISSION_DENIED"),
            TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n", exitCode = 0, timedOut = true),
        ).forEach { result ->
            assertThat(TermuxSetupSupport.diagnosticSucceeded(result)).isFalse()
        }
    }
}
