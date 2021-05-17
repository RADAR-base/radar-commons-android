/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.Is.`is`
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.radarbase.util.DirectQueueFileStorage.Companion.MINIMUM_LENGTH
import org.radarbase.util.QueueFileElement.Companion.ELEMENT_HEADER_LENGTH
import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ln

class QueueFileTest {

    @Rule
    @JvmField
    var folder = TemporaryFolder()

    @Test
    @Throws(Exception::class)
    fun elementOutputStream() {
        val queue = createQueue()
        val buffer = ByteArray((MAX_SIZE / 4).toInt())
        queue.elementOutputStream().use { out ->
            out.write(buffer)
            out.next()
            out.write(buffer)
            out.next()
            out.write(buffer)
            out.next()
            assertThrows(IllegalStateException::class.java) { out.write(buffer) }
        }
    }

    @Test
    fun testExactSize() {
        val file = folder.newFile()
        assertTrue(file.delete())
        val buffer = ByteArray(244)
        ThreadLocalRandom.current().nextBytes(buffer)

        QueueFile.newDirect(file, 40000000).use { queue ->
            queue.elementOutputStream().use { out ->
                repeat(30) {
                    out.write(buffer)
                    out.next()
                }
            }
            queue.elementOutputStream().use { out ->
                repeat(37) {
                    out.write(buffer)
                    out.next()
                }
            }
            queue.remove(1)
            queue.elementOutputStream().use { out ->
                out.write(buffer)
                out.next()
            }
            queue.remove(66)
        }

        QueueFile.newDirect(file, 40000000).use {
            assertThat(it.fileSize, `is`(32768L))
        }
    }

    @Test
    @Throws(Exception::class)
    fun elementOutputStreamCircular() {
        val queue = createQueue()
        assertEquals(0, queue.size)
        val buffer = ByteArray((MAX_SIZE / 4).toInt())
        queue.elementOutputStream().use { out ->
            out.write(buffer)
            out.next()
            out.write(buffer)
            out.next()
            out.write(buffer)
            out.next()
        }
        assertEquals(3, queue.size)
        queue.remove(2)
        assertEquals(1, queue.size)
        try {
            queue.elementOutputStream().use { out ->
                out.write(buffer)
                out.next()
                out.write(buffer)
                out.next()
                assertThrows(IllegalStateException::class.java) { out.write(buffer) }
            }
        } catch (ex: IOException) {
            logger.info("Queue file cannot be written to {}", queue)
            throw ex
        }

    }

    @Test
    fun testPartialRead() {
        val queue = createQueue()
        queue.elementOutputStream().use {
            it.write(1)
            it.write(3)
            it.next()
            it.write(4)
            it.write(5)
        }
        val iter = queue.iterator()
        iter.next().use { assertEquals(1, it.read()) }
        iter.next().use { assertEquals(4, it.read()) }
    }

    @Throws(IOException::class)
    private fun createQueue(size: Long = MAX_SIZE): QueueFile {
        val file = folder.newFile()
        assertTrue(file.delete())
        return QueueFile.newDirect(file, size)
    }

    @Test
    @Throws(Exception::class)
    fun isEmpty() {
        val queueFile = createQueue()
        assertTrue(queueFile.isEmpty)
        val value = 1
        queueFile.elementOutputStream().use { out -> out.write(value) }
        assertFalse(queueFile.isEmpty)
        assertEquals(1, queueFile.size)
        val `in` = queueFile.peek()
        assertNotNull(`in`)
        `in`!!.close()
        assertFalse(queueFile.isEmpty)
        assertEquals(1, queueFile.size)
        queueFile.remove(1)
        assertTrue(queueFile.isEmpty)
        assertEquals(0, queueFile.size)
    }

    @Test
    @Throws(Exception::class)
    fun peek() {
        val queueFile = createQueue()
        assertNull(queueFile.peek())
        val random = Random()
        val buffer = ByteArray(16)
        val v1 = random.nextInt(255)
        val v2 = random.nextInt(255)
        random.nextBytes(buffer)
        val expectedBuffer = ByteArray(buffer.size)
        System.arraycopy(buffer, 0, expectedBuffer, 0, buffer.size)
        queueFile.elementOutputStream().use { out ->
            out.write(v1)
            out.next()
            out.write(v2)
            out.next()
            out.write(buffer)
        }
        assertEquals(3, queueFile.size)
        queueFile.peek()!!.use { `in` ->
            assertNotNull(`in`)
            assertEquals(1, `in`.available())
            assertEquals(v1, `in`.read())
        }
        queueFile.peek()!!.use { `in` ->
            assertNotNull(`in`)
            assertEquals(1, `in`.available())
            assertEquals(v1, `in`.read())
        }
        queueFile.remove(1)
        queueFile.peek()!!.use { `in` ->
            assertNotNull(`in`)
            assertEquals(1, `in`.available())
            assertEquals(v2, `in`.read())
        }
        queueFile.remove(1)
        queueFile.peek()!!.use { `in` ->
            assertNotNull(`in`)
            assertEquals(16, `in`.available())
            val actualBuffer = ByteArray(20)
            assertEquals(16, `in`.read(actualBuffer))
            val actualBufferShortened = ByteArray(16)
            System.arraycopy(actualBuffer, 0, actualBufferShortened, 0, 16)
            assertArrayEquals(expectedBuffer, actualBufferShortened)
        }
        queueFile.remove(1)
        assertNull(queueFile.peek())
    }

    @Test
    @Throws(Exception::class)
    operator fun iterator() {
        val queueFile = createQueue()
        assertNull(queueFile.peek())
        queueFile.elementOutputStream().use { out ->
            out.write(1)
            out.next()
            out.write(2)
        }
        val iter = queueFile.iterator()
        assertTrue(iter.hasNext())
        iter.next().use { `in` -> assertEquals(1, `in`.read()) }
        assertTrue(iter.hasNext())
        iter.next().use { `in` -> assertEquals(2, `in`.read()) }
        assertFalse(iter.hasNext())

        assertThrows(NoSuchElementException::class.java) {
            iter.next()
        }
    }

    @Test
    @Throws(Exception::class)
    fun clear() {
        val queue = createQueue()
        queue.elementOutputStream().use { out ->
            out.write(1)
            out.next()
            out.write(2)
        }
        assertEquals(2, queue.size)
        queue.clear()
        assertTrue(queue.isEmpty)
    }

    @Throws(IOException::class)
    private fun writeAssertFileSize(
        expectedSize: Long,
        expectedUsed: Long,
        buffer: ByteArray,
        queue: QueueFile,
    ) {
        queue.elementOutputStream().use { out -> out.write(buffer) }
        assertEquals(expectedUsed, queue.usedBytes)
        assertEquals(expectedSize, queue.fileSize)
    }

    @Test
    @Throws(Exception::class)
    fun fileSize() {
        val queue = createQueue()
        assertEquals(MINIMUM_LENGTH, queue.fileSize)
        val bufSize = MAX_SIZE / 16 - QUEUE_HEADER_LENGTH
        val buffer = ByteArray(bufSize.toInt())
        // write buffer, assert that the file size increases with the stored size
        writeAssertFileSize(MINIMUM_LENGTH, bufSize + ELEMENT_HEADER_LENGTH + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH, (bufSize + ELEMENT_HEADER_LENGTH) * 2 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 2, (bufSize + ELEMENT_HEADER_LENGTH) * 3 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 2, (bufSize + ELEMENT_HEADER_LENGTH) * 4 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 4, (bufSize + ELEMENT_HEADER_LENGTH) * 5 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 4, (bufSize + ELEMENT_HEADER_LENGTH) * 6 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 4, (bufSize + ELEMENT_HEADER_LENGTH) * 7 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 4, (bufSize + ELEMENT_HEADER_LENGTH) * 8 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 9 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 10 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 11 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 12 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 13 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 14 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 15 + QUEUE_HEADER_LENGTH, buffer, queue)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 16 + QUEUE_HEADER_LENGTH, buffer, queue)

        // queue is full now
        var actualException: Exception? = null
        try {
            writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 17 + QUEUE_HEADER_LENGTH, buffer, queue)
        } catch (ex: IllegalStateException) {
            actualException = ex
        }

        assertNotNull(actualException)
        // queue is full, remove elements to add new ones
        queue.remove(1)
        // this buffer is written in a circular way
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 16 + QUEUE_HEADER_LENGTH, buffer, queue)
        queue.remove(1)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 16 + QUEUE_HEADER_LENGTH, buffer, queue)
        queue.remove(1)
        writeAssertFileSize(MINIMUM_LENGTH * 8, (bufSize + ELEMENT_HEADER_LENGTH) * 16 + QUEUE_HEADER_LENGTH, buffer, queue)
        queue.remove(14)
        assertEquals(2, queue.size)
        assertEquals((bufSize + ELEMENT_HEADER_LENGTH) * 2 + QUEUE_HEADER_LENGTH, queue.usedBytes)
        assertEquals(MINIMUM_LENGTH * 2, queue.fileSize)
    }

    private enum class Operation {
        REOPEN, WRITE, READ, CLEAR, REMOVE
    }

    @Test(timeout = 30_000L)
    @Throws(Throwable::class)
    fun enduranceTest() {
        val numberOfOperations = 1_000
        val size = MINIMUM_LENGTH * 4
        val random = Random()
        val buffer = ByteArray((size * 2 / 3).toInt()) { it.toByte() }
        val file = folder.newFile()
        assertTrue(file.delete())
        var queue = QueueFile.newDirect(file, size)
        val list = LinkedList<Element>()
        var bytesUsed = 36L

        try {
            repeat(numberOfOperations) {
                val operation = when(random.nextDouble()) {
                    in 0.0 .. 0.05 -> Operation.REOPEN
                    in 0.05 .. 0.1 -> Operation.CLEAR
                    in 0.1 .. 0.4 -> if (!queue.isEmpty) Operation.REMOVE else Operation.WRITE
                    in 0.4 .. 0.7 -> if (!queue.isEmpty) Operation.READ else Operation.WRITE
                    else -> Operation.WRITE
                }

                when (operation) {
                    Operation.REOPEN -> {
                        logger.info("Running {} operation", operation)
                        queue.close()
                        queue = QueueFile.newDirect(file, size)
                    }
                    Operation.CLEAR -> {
                        logger.info("Running {} operation", operation)
                        queue.clear()
                        list.clear()
                        bytesUsed = 36
                    }
                    Operation.REMOVE -> bytesUsed -= remove(list, queue, random)
                    Operation.READ -> read(list, queue, buffer, random)
                    Operation.WRITE -> bytesUsed += write(list, queue, buffer, random, size)
                }
                assertEquals("Bytes used does not match after operation $operation", bytesUsed, queue.usedBytes)
                assertEquals("Queue size does not match after operation $operation", list.size, queue.size)
            }
        } catch (ex: Throwable) {
            logger.error("Current list: {} with used bytes {}; QueueFile {}", list, bytesUsed, queue)
            throw ex
        }

    }

    /**
     * Remove a random number of elements from a queue and a verification list
     * @return bytes removed
     */
    @Throws(IOException::class)
    private fun remove(
        list: LinkedList<Element>,
        queue: QueueFile,
        random: Random,
    ): Long {
        val numRemove = random.nextInt(queue.size) + 1
        logger.info("Removing {} elements", numRemove)
        queue.remove(numRemove)
        return generateSequence { (list.removeFirst().length + ELEMENT_HEADER_LENGTH).toLong() }
            .take(numRemove)
            .sum()
    }

    /**
     * Read a random number of elements from a queue and a verification list, using given buffer.
     * The sizes read must match the verification list.
     */
    @Throws(Throwable::class)
    private fun read(
        list: LinkedList<Element>,
        queue: QueueFile,
        buffer: ByteArray,
        random: Random,
    ) {
        val numRead = random.nextInt(queue.size) + 1
        assertTrue(queue.size >= numRead)
        logger.info("Reading {} elements", numRead)
        val readBuffer = ByteArray(buffer.size)
        val iterator = queue.iterator()
        repeat (numRead) { j ->
            val expectedElement = list[j]
            val `in` = iterator.next()
            try {
                var readLength = 0
                var newlyRead = `in`.read(readBuffer, 0, readBuffer.size)
                while (newlyRead != -1) {
                    readLength += newlyRead
                    newlyRead = `in`.read(readBuffer, readLength, readBuffer.size - readLength)
                }
                assertEquals(expectedElement.length, readLength)
                assertArrayEquals(
                    buffer.copyOf(expectedElement.length),
                    readBuffer.copyOf(expectedElement.length)
                )
            } catch (ex: Throwable) {
                logger.error("Inputstream {} of queuefile {} does not match element {}",
                        `in`, queue, expectedElement)
                throw ex
            } finally {
                `in`.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun write(
        list: LinkedList<Element>,
        queue: QueueFile,
        buffer: ByteArray,
        random: Random,
        size: Long,
    ): Long {
        val numAdd = random.nextInt(16) + 1
        logger.info("Writing {} elements", numAdd)
        var bytesUsed = 0L
        val lambda = lambda(buffer.size.toDouble())
        queue.elementOutputStream().use { out ->
            repeat(numAdd) {
                val numBytes = (random.nextExponential(lambda).toInt() + 1).coerceAtMost(buffer.size)
                if (numBytes + out.usedSize + ELEMENT_HEADER_LENGTH > size) {
                    return@repeat
                }
                val next = Element(0, numBytes)
                if (list.isEmpty()) {
                    next.position = QUEUE_HEADER_LENGTH
                } else if (out.usedSize + numBytes + ELEMENT_HEADER_LENGTH > queue.fileSize) {
                    val firstPosition = list.first.position
                    for (el in list) {
                        if (el.position < firstPosition) {
                            el.position += (queue.fileSize - QUEUE_HEADER_LENGTH).toInt()
                        }
                    }
                    val last = list.last
                    next.position = last.position + last.length + ELEMENT_HEADER_LENGTH
                    if (next.position >= queue.fileSize * 2) {
                        next.position += (QUEUE_HEADER_LENGTH - queue.fileSize * 2).toInt()
                    }
                } else {
                    val last = list.last
                    next.position = last.position + last.length + ELEMENT_HEADER_LENGTH
                    if (next.position >= queue.fileSize) {
                        next.position += (QUEUE_HEADER_LENGTH - queue.fileSize).toInt()
                    }
                }
                bytesUsed += next.length + ELEMENT_HEADER_LENGTH
                list.add(next)
                out.write(buffer, 0, numBytes)
                out.next()
            }
        }
        return bytesUsed
    }

    private fun Random.nextExponential(lambda: Double): Double = - ln(1 - nextDouble()) / lambda

    data class Element constructor(var position: Long, val length: Int) {
        override fun toString(): String = "[$position, $length]"
    }

    companion object {
        private const val MAX_SIZE = 8 * MINIMUM_LENGTH
        private val logger = LoggerFactory.getLogger(QueueFileTest::class.java)
        private fun lambda(target: Double) = - ln(0.001) / target
    }
}
