package com.promenar.infinity.share.core

import android.net.Uri
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** 只标记来源读取错误，目标写入和限额异常保留自己的错误类型。 */
internal fun SharedContentSource.openForShare(uri: Uri): InputStream {
    fun <T> read(action: () -> T): T = try { action() }
    catch (failure: IOException) { throw ShareSourceReadException(failure) }
    catch (failure: IllegalArgumentException) { throw ShareSourceReadException(IOException(failure)) }
    val input = read { open(uri) }
    return object : FilterInputStream(input) {
        override fun read(): Int = read { `in`.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read { `in`.read(bytes, offset, length) }
        override fun close() = read { `in`.close() }
    }
}

internal class ShareSourceReadException(cause: IOException) : IOException(cause)
internal open class ShareContentMismatchException : IOException("文件内容与可解析格式不符")
