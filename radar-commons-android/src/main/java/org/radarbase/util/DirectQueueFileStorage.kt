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

import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A storage backend for a QueueFile
 * @param file file to use
 * @param initialLength initial length if the file does not exist.
 * @param maximumLength maximum length that the file may have.
 * @throws NullPointerException if file is null
 * @throws IllegalArgumentException if the initialLength or maximumLength is smaller than
 *                                  `QueueFileHeader.ELEMENT_HEADER_LENGTH`.
 * @throws IOException if the file could not be accessed or was smaller than
 *                     `QueueFileHeader.ELEMENT_HEADER_LENGTH`
 */
class DirectQueueFileStorage(
        file: File,
        initialLength: Long,
        maximumLength: Long,
) : QueueStorage {
    /**
     * The underlying file. Uses a ring buffer to store entries.
     * <pre>
     * Format:
     * QueueFileHeader.ELEMENT_HEADER_LENGTH bytes    Header
     * length bytes                           Data
    </pre> *
     */
    private val channel: FileChannel
    private val randomAccessFile: RandomAccessFile

    /** Filename, for toString purposes  */
    private val fileName: String = file.name

    override var isClosed: Boolean = false
        private set

    override val isPreExisting: Boolean = file.exists()

    /** File size in bytes.  */
    override var length: Long = 0L
        private set

    override val minimumLength: Long = MINIMUM_LENGTH

    override var maximumLength: Long = maximumLength
        set(value) {
            require(value <= Int.MAX_VALUE) {
                "Maximum cache size out of range $value <= ${Int.MAX_VALUE}"
            }
            field = value.coerceAtLeast(MINIMUM_LENGTH)
        }

    init {
        require(initialLength >= minimumLength) { "Initial length $initialLength is smaller than minimum length $minimumLength" }
        require(maximumLength <= Int.MAX_VALUE) { "Maximum cache size out of range $maximumLength <= ${Int.MAX_VALUE}" }
        require(initialLength <= maximumLength) { "Initial length $initialLength exceeds maximum length $maximumLength" }

        randomAccessFile = RandomAccessFile(file, "rw")
        length = if (isPreExisting) {
            // Read header from file
            val currentLength = randomAccessFile.length()
            if (currentLength < QUEUE_HEADER_LENGTH) {
                throw IOException("File length $length is smaller than queue header length $QUEUE_HEADER_LENGTH")
            }
            currentLength
        } else {
            randomAccessFile.setLength(initialLength)
            initialLength
        }
        channel = randomAccessFile.channel
    }

    @Throws(IOException::class)
    override fun read(position: Long, data: ByteBuffer): Long {
        requireNotClosed()
        val count = data.remaining()
        require(count <= length - position.coerceAtMost(QUEUE_HEADER_LENGTH)) {
            "buffer count ${data.remaining()} exceeds storage length $length"
        }

        val wrappedPosition = wrapPosition(position)
        channel.position(wrappedPosition)
        return if (wrappedPosition + count <= length) {
            channel.read(data)
            wrapPosition(wrappedPosition + count.toLong())
        } else {
            // The read overlaps the EOF.
            // # of bytes to read before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
            val firstPart = (length - wrappedPosition).toInt()
            readFully(data, firstPart)
            channel.position(QUEUE_HEADER_LENGTH)
            readFully(data, count - firstPart)
            QUEUE_HEADER_LENGTH + count - firstPart
        }
    }

    @Throws(IOException::class)
    private fun readFully(buffer: ByteBuffer, count: Int) {
        var n = 0
        while (n < count) {
            val numRead = channel.read(buffer).toLong()
            if (numRead == -1L) throw EOFException()
            n += numRead.toInt()
        }
    }

    /** Wraps the position if it exceeds the end of the file.  */
    override fun wrapPosition(position: Long): Long {
        val newPosition = if (position < length) position else QUEUE_HEADER_LENGTH + position - length
        require(newPosition < length && position >= 0) {
            "Position $position invalid outside of storage length $length"
        }
        return newPosition
    }

    /** Sets the length of the file.  */
    @Throws(IOException::class)
    override fun resize(size: Long) {
        requireNotClosed()
        if (size == length) {
            return
        }
        require(size <= length || size <= maximumLength) {
            "New length $size exceeds maximum length $maximumLength"
        }
        require(size >= MINIMUM_LENGTH) {
            "New length $size is less than minimum length $QUEUE_HEADER_LENGTH"
        }
        flush()
        randomAccessFile.setLength(size)
        channel.force(true)
        length = size
    }

    @Throws(IOException::class)
    override fun flush() {
        channel.force(false)
    }

    @Throws(IOException::class)
    override fun write(position: Long, data: ByteBuffer, mayIgnoreBuffer: Boolean): Long {
        requireNotClosed()
        val count = data.remaining()
        require(count + QUEUE_HEADER_LENGTH <= length) {
            "buffer count ${data.remaining()} exceeds storage length $length"
        }
        val wrappedPosition = wrapPosition(position)
        channel.position(wrappedPosition)
        val linearPart = (length - wrappedPosition).toInt()
        return if (linearPart >= count) {
            writeFully(data, count)
            wrapPosition(wrappedPosition + count.toLong())
        } else {
            // The write overlaps the EOF.
            // # of bytes to write before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
            if (linearPart > 0) {
                writeFully(data, linearPart)
            }
            channel.position(QUEUE_HEADER_LENGTH)
            writeFully(data, count - linearPart)
            QUEUE_HEADER_LENGTH + count - linearPart
        }
    }

    @Throws(IOException::class)
    private fun writeFully(buffer: ByteBuffer, count: Int) {
        var n = 0
        if (buffer.remaining() < count) {
            throw BufferUnderflowException()
        }
        val oldLimit = buffer.limit()
        if (buffer.remaining() > count) {
            buffer.limit(buffer.position() + count)
        }
        while (n < count) {
            val numWritten = channel.write(buffer)
            if (numWritten == -1) throw EOFException()
            n += numWritten
        }
        buffer.limit(oldLimit)
    }

    @Throws(IOException::class)
    override fun move(srcPosition: Long, dstPosition: Long, count: Long) {
        requireNotClosed()
        require(srcPosition >= 0
                && dstPosition >= 0
                && count > 0
                && srcPosition + count <= length
                && dstPosition + count <= length) {
            "Movement specification src=$srcPosition, count=$count, dst=$dstPosition is invalid for storage of length $length"
        }
        flush()
        channel.position(dstPosition)

        if (channel.transferTo(srcPosition, count, channel) != count) {
            throw IOException("Cannot move all data")
        }
    }

    @Throws(IOException::class)
    private fun requireNotClosed() {
        if (isClosed) throw IOException("closed")
    }

    @Throws(IOException::class)
    override fun close() {
        isClosed = true
        channel.close()
        randomAccessFile.close()
    }

    override fun toString() = "DirectQueueFileStorage<$fileName>[length=$length]"

    companion object {
        /** Initial file size in bytes.  */
        const val MINIMUM_LENGTH = 4096L // one file system block
    }
}
