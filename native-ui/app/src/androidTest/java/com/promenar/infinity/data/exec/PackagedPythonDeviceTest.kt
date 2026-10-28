package com.promenar.infinity.data.exec

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备上直接运行打包的解释器，验证打包库方案在当前 ABI 与系统版本可用。 */
@RunWith(AndroidJUnit4::class)
class PackagedPythonDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val python get() = PackagedPython.from(context)

    @Test fun nativeLibrariesAreExtractedAndPythonIsPackaged() {
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS).isNotEqualTo(0)
        assertWithMessage("nativeLibraryDir=%s", context.applicationInfo.nativeLibraryDir).that(python.isAvailable).isTrue()
    }

    @Test fun standardLibraryAndNativeExtensionsLoadFromNativeLibraryDir() {
        val result = run(SMOKE)
        assertWithMessage("stderr=%s", result.stderr).that(result.exitCode).isEqualTo(0)
        assertThat(result.stderr).isEmpty()
        val value = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        fun text(key: String) = value.getValue(key).jsonPrimitive.content
        assertThat(text("version")).isEqualTo(BuildConfig.PACKAGED_PYTHON_VERSION)
        assertThat(text("ssl_ext")).isEqualTo(File(context.applicationInfo.nativeLibraryDir, "libpyext__ssl.so").path)
        assertThat(text("openssl")).startsWith("OpenSSL")
        assertThat(text("sqlite")).isEqualTo("中文")
        assertThat(text("sha256")).isEqualTo(MessageDigest.getInstance("SHA-256").digest("infinity".toByteArray())
            .joinToString("") { "%02x".format(it) })
        assertThat(text("decimal")).isEqualTo("0.3")
        listOf("zlib", "ctypes", "socket", "test_absent").forEach { key ->
            assertWithMessage(key).that(value.getValue(key).jsonPrimitive.boolean).isTrue()
        }
    }

    @Test fun exitCodeAndStderrArePropagated() {
        val result = run("import sys; sys.stdout.write('out'); sys.stderr.write('boom'); sys.exit(3)")
        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stdout).isEqualTo("out")
        assertThat(result.stderr).isEqualTo("boom")
    }

    private data class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(code: String): Run {
        val dir = File(context.cacheDir, "packaged-python-test").apply { deleteRecursively(); mkdirs() }
        try {
            val stdout = File(dir, "stdout")
            val stderr = File(dir, "stderr")
            val builder = ProcessBuilder(python.launcher.path, "-c", code)
                .directory(dir)
                .redirectOutput(stdout)
                .redirectError(stderr)
            builder.environment().clear()
            builder.environment().putAll(python.environment())
            builder.environment()["HOME"] = dir.path
            builder.environment()["TMPDIR"] = dir.path
            val process = builder.start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("打包 Python 60 秒内未结束")
            }
            return Run(process.exitValue(), stdout.readText(), stderr.readText())
        } finally { dir.deleteRecursively() }
    }

    private companion object {
        val SMOKE = """
            import ctypes, decimal, hashlib, importlib.util, json, os, socket, sqlite3, ssl, sys, zlib, _ssl
            db = sqlite3.connect(':memory:')
            db.execute('create table t(x)')
            db.execute("insert into t values ('中文')")
            s = socket.socket()
            s.close()
            print(json.dumps({
                'version': sys.version.split()[0],
                'ssl_ext': _ssl.__file__,
                'openssl': ssl.OPENSSL_VERSION,
                'sqlite': db.execute('select x from t').fetchone()[0],
                'sha256': hashlib.sha256(b'infinity').hexdigest(),
                'zlib': zlib.decompress(zlib.compress(b'x' * 1000)) == b'x' * 1000,
                'ctypes': ctypes.CDLL(None).getpid() == os.getpid(),
                'decimal': str(decimal.Decimal('0.1') + decimal.Decimal('0.2')),
                'socket': True,
                'test_absent': importlib.util.find_spec('test') is None,
            }))
        """.trimIndent()
    }
}
