package org.radarbase.util

import org.junit.Assert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

class QueueFileOutputStreamTest  {
    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun testWrite() {
        val tmpFile = tempDir.newFile()
        Assert.assertTrue(tmpFile.delete())
        val size = 6000
        val directQueue = DirectQueueFileStorage(tmpFile, 4096, 16000)
        val expected = ByteArray(size).apply {
            ThreadLocalRandom.current().nextBytes(this)
        }
        val actual = ByteArray(size)

        val queueFile = QueueFile(directQueue)
        queueFile.elementOutputStream().use { output ->
            output.write(expected)
            output.next()
            output.write(expected)
            output.next()
        }
        val actualHeader = ByteArray(5)
        val expectedHeader = ByteArray(5).apply {
            this[0] = 112.toByte()
            this[1] = 23.toByte()
            this[4] = (-54).toByte()
        }
        assertEquals(QUEUE_HEADER_LENGTH + 5L, directQueue.read(QUEUE_HEADER_LENGTH, ByteBuffer.wrap(actualHeader)))
        assertArrayEquals(expectedHeader, actualHeader)
        assertEquals(QUEUE_HEADER_LENGTH + 5L + size, directQueue.read(QUEUE_HEADER_LENGTH + 5L, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)

        assertEquals(QUEUE_HEADER_LENGTH + 10L + size, directQueue.read(QUEUE_HEADER_LENGTH + 5L + size, ByteBuffer.wrap(actualHeader)))
        assertArrayEquals(expectedHeader, actualHeader)
        assertEquals(QUEUE_HEADER_LENGTH + 10L + 2*size, directQueue.read(QUEUE_HEADER_LENGTH + 10L + size, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)
    }
}
