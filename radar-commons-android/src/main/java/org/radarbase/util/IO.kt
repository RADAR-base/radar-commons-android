package org.radarbase.util

import java.io.IOException
import java.io.InputStream

object IO {
    inline fun requireIO(predicate: Boolean, message: () -> String) {
        if (!predicate) throw IOException(message())
    }

    fun ByteArray.checkOffsetAndCount(offset: Int, length: Int) {
        if (offset < 0) throw IndexOutOfBoundsException("offset < 0")
        if (length < 0) throw IndexOutOfBoundsException("length < 0")
        if (offset + length > size) throw IndexOutOfBoundsException(
            "extent of offset and length larger than buffer length"
        )
    }

    fun InputStream.skipFully(n: Long) {
        var numRead = 0L
        do {
            numRead += skip(n - numRead)
        } while (numRead < n)
    }
}
