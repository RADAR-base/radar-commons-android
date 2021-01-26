package org.radarbase.util

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class QueueFileInputStream(
    element: QueueFileElement,
    private val storage: QueueStorage,
    private val modificationCount: AtomicInteger,
) : InputStream() {
    private val totalLength: Int = element.length
    private val expectedModCount: Int = modificationCount.get()
    private val singleByteArray = ByteArray(1)
    private var storagePosition: Long = storage.wrapPosition(element.dataPosition)
    private var bytesRead: Int = 0

    private val elementAvailable: Int
        get() = totalLength - bytesRead

    override fun available() = elementAvailable

    override fun skip(byteCount: Long): Long {
        val countAvailable = byteCount.coerceAtMost(elementAvailable.toLong()).toInt()
        bytesRead += countAvailable
        storagePosition = storage.wrapPosition(storagePosition + countAvailable)
        return countAvailable.toLong()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (read(singleByteArray, 0, 1) != 1) {
            throw IOException("Cannot read byte")
        }
        return singleByteArray[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        if (elementAvailable == 0) return -1
        if (count < 0) throw IndexOutOfBoundsException("length < 0")
        if (count == 0) return 0
        checkForCoModification()

        val countAvailable = count.coerceAtMost(elementAvailable)
        val buffer = ByteBuffer.wrap(bytes, offset, countAvailable)
        storagePosition = storage.read(storagePosition, buffer)

        val numRead = buffer.position() - offset
        bytesRead += numRead
        return numRead
    }

    @Throws(IOException::class)
    private fun checkForCoModification() {
        if (modificationCount.get() != expectedModCount) {
            throw IOException("Buffer modified while reading InputStream")
        }
    }

    override fun toString(): String = "QueueFileInputStream[length=$totalLength,bytesRead=$bytesRead]"
}
