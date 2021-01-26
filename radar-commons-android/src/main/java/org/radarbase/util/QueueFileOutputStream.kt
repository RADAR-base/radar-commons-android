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

import org.radarbase.util.IO.requireIO
import org.radarbase.util.IO.checkOffsetAndCount
import org.radarbase.util.QueueFileElement.Companion.ELEMENT_HEADER_LENGTH
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * An OutputStream that can write multiple elements. After finished writing one element, call
 * [.next] to start writing the next.
 *
 *
 * It is very important to [.close] this OutputStream, as this is the only way that the
 * data is actually committed to file.
 */
class QueueFileOutputStream internal constructor(
        private val queue: QueueFile,
        private val header: QueueFileHeader,
        private val storage: QueueStorage,
        position: Long,
) : OutputStream() {
    private var storagePosition: Long = storage.wrapPosition(position)

    private var isClosed: Boolean = false

    /** Current element being written to. */
    private val current = QueueFileElement(storagePosition, 0)
    /** Previous element being written to. */
    private val newLast = QueueFileElement()
    /**
     * First element in the queue. If that first element was not
     * generated in the current stream, it is kept empty.
     */
    private val newFirst = QueueFileElement()

    /** Number of elements written in this stream. */
    private var elementsWritten: Int = 0

    private val singleByteBuffer = ByteArray(1)

    /** Number of bytes that have been committed to file by this stream. */
    private var streamBytesUsed: Long = 0L

    /** Buffer to write an element header to. */
    private val elementHeaderBuffer = ByteBuffer.allocate(ELEMENT_HEADER_LENGTH)

    @Throws(IOException::class)
    override fun write(byteValue: Int) {
        singleByteBuffer[0] = (byteValue and 0xFF).toByte()
        write(singleByteBuffer, 0, 1)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        bytes.checkOffsetAndCount(offset, count)
        if (count == 0) return  // no action needed
        checkNotClosed()

        writeInitialHeader(count)

        storagePosition = storage.writeFully(storagePosition, ByteBuffer.wrap(bytes, offset, count))
        current.length += count
    }

    /** If the current element has not yet been written to, first write a header.
     * This should only be called if it is sure that the current element will be written to,
     * otherwise an empty element will be persisted.*/
    private fun writeInitialHeader(count: Int) {
        if (current.isEmpty) {
            ensureCapacity(ELEMENT_HEADER_LENGTH + count.toLong())
            storagePosition = writeHeader(storagePosition, 0, 0, false)
        } else {
            ensureCapacity(count.toLong())
        }
    }

    private fun writeHeader(position: Long, count: Int, crc: Byte, mayIgnoreBuffer: Boolean): Long {
        elementHeaderBuffer.clear()
        elementHeaderBuffer.putInt(count)
        elementHeaderBuffer.put(crc)
        elementHeaderBuffer.flip()
        return storage.writeFully(position, elementHeaderBuffer, mayIgnoreBuffer)
    }

    @Throws(IOException::class)
    private fun checkNotClosed() {
        requireIO(!isClosed) { "Cannot write to storage $storage, output stream is closed." }
        requireIO(!storage.isClosed) { "Cannot write to storage $storage, it is closed." }
    }

    /**
     * Proceed writing the next element. Zero length elements are not written, so always write
     * at least one byte to store an element.
     * @throws IOException if the QueueFileStorage cannot be written to
     */
    @Throws(IOException::class)
    operator fun next() {
        checkNotClosed()
        // No data was written in this element. Skipping.
        if (current.isEmpty) return

        newLast.update(current)
        if (newFirst.isEmpty && queue.isEmpty) {
            newFirst.update(current)
        }

        current.position = storagePosition
        current.length = 0

        writeHeader(newLast.position, newLast.length, newLast.crc, true)

        elementsWritten++
    }

    /**
     * Size of the storage that will be used if the OutputStream is closed.
     * @return number of bytes used
     */
    internal val usedSize: Long
        get() = queue.usedBytes + streamBytesUsed

    /**
     * Expands the storage if necessary, updating the queue length if needed.
     * @throws IllegalStateException if the queue is full.
     */
    @Throws(IOException::class)
    private fun ensureCapacity(length: Long) {
        val newStreamBytesUsed = streamBytesUsed + length
        val bytesNeeded = queue.usedBytes + newStreamBytesUsed

        check(bytesNeeded <= queue.maximumFileSize) {
            // reset current element
            current.length = 0
            "Data does not fit in queue"
        }

        streamBytesUsed = newStreamBytesUsed
        val oldLength = header.length
        // no resize needed
        if (bytesNeeded <= oldLength) return

        logger.debug("Extending {}", queue)

        // Double the length until we can fit the new data.
        val newLength = generateSequence(oldLength) { it * 2 }
            .first { it >= bytesNeeded }
            .coerceAtMost(queue.maximumFileSize)

        val beginningOfFirstElement = if (!newFirst.isEmpty) {
            newFirst.position
        } else {
            header.firstPosition
        }

        queue.growStorage(newLength, storagePosition, beginningOfFirstElement)

        if (storagePosition <= beginningOfFirstElement) {
            val positionUpdate = oldLength - QueueFileHeader.QUEUE_HEADER_LENGTH

            if (current.position <= beginningOfFirstElement) {
                current.position += positionUpdate
            }
            storagePosition += positionUpdate
        }
    }

    override fun flush() {
        storage.flush()
    }

    /**
     * Closes the stream and commits it to file.
     * @throws IOException if the output stream cannot be written to.
     */
    @Throws(IOException::class)
    override fun close() {
        try {
            next()
            flush()
            if (elementsWritten > 0) {
                queue.commitOutputStream(newFirst, newLast, elementsWritten)
            }
        } finally {
            isClosed = true
        }
    }

    override fun toString(): String {
        return ("QueueFileOutputStream[current=" + current
                + ",total=" + streamBytesUsed + ",used=" + usedSize + "]")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueueFileOutputStream::class.java)
    }
}
