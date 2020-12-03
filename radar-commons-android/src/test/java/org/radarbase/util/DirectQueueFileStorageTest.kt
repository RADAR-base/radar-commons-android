package org.radarbase.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

class DirectQueueFileStorageTest {
    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun testRead() {
        val tmpFile = tempDir.newFile()
        assertTrue(tmpFile.delete())
        val directQueue = DirectQueueFileStorage(tmpFile, 4096, 4096)
        val expected = ByteArray(10).apply {
            ThreadLocalRandom.current().nextBytes(this)
        }
        val actual = ByteArray(10)
        assertEquals(QUEUE_HEADER_LENGTH + 10L, directQueue.writeFully(QUEUE_HEADER_LENGTH, ByteBuffer.wrap(expected)))
        assertEquals(QUEUE_HEADER_LENGTH + 10L, directQueue.readFully(QUEUE_HEADER_LENGTH, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)
    }

    @Test
    fun testWrap() {
        val tmpFile = tempDir.newFile()
        assertTrue(tmpFile.delete())
        val directQueue = DirectQueueFileStorage(tmpFile, 4096, 4096)
        val expected = ByteArray(10).apply {
            ThreadLocalRandom.current().nextBytes(this)
        }
        val actual = ByteArray(10)
        assertEquals(4L + QUEUE_HEADER_LENGTH, directQueue.writeFully(4090, ByteBuffer.wrap(expected)))
        assertEquals(4L + QUEUE_HEADER_LENGTH, directQueue.readFully(4090, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)
    }

    @Test
    fun testResize() {
        val tmpFile = tempDir.newFile()
        assertTrue(tmpFile.delete())
        val directQueue = DirectQueueFileStorage(tmpFile, 4096, 8192)
        val expected = ByteArray(10).apply {
            ThreadLocalRandom.current().nextBytes(this)
        }
        val actual = ByteArray(10)
        assertEquals(4L + QUEUE_HEADER_LENGTH, directQueue.writeFully(4090, ByteBuffer.wrap(expected)))
        assertEquals(4L + QUEUE_HEADER_LENGTH, directQueue.readFully(4090, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)

        directQueue.resize(2 * 4096)
        directQueue.move(QUEUE_HEADER_LENGTH, 4096L, 4L)
        assertEquals(4100L, directQueue.readFully(4090L, ByteBuffer.wrap(actual)))
        assertArrayEquals(expected, actual)
    }
}
