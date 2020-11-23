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
class DirectQueueFileStorage(file: File, initialLength: Long, maximumLength: Long) : QueueStorage {
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
            if (currentLength < QueueFileHeader.QUEUE_HEADER_LENGTH) {
                throw IOException("File length " + length + " is smaller than queue header length " + QueueFileHeader.QUEUE_HEADER_LENGTH)
            }
            currentLength
        } else {
            randomAccessFile.setLength(initialLength)
            initialLength
        }
        channel = randomAccessFile.channel
    }

    @Throws(IOException::class)
    override fun read(position: Long, buffer: ByteBuffer): Long {
        requireNotClosed()
        val count = buffer.remaining()
        require(count + QueueFileHeader.QUEUE_HEADER_LENGTH <= length) {
            "buffer count ${buffer.remaining()} exceeds storage length $length"
        }

        val wrappedPosition = wrapPosition(position)
        channel.position(wrappedPosition.toLong())
        return if (position + count <= length) {
            channel.read(buffer)
            wrapPosition(wrappedPosition + count.toLong()).toLong()
        } else {
            // The read overlaps the EOF.
            // # of bytes to read before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
            val firstPart = (length - wrappedPosition).toInt()
            readFully(buffer, firstPart)
            channel.position(QueueFileHeader.QUEUE_HEADER_LENGTH.toLong())
            readFully(buffer, count - firstPart)
            (QueueFileHeader.QUEUE_HEADER_LENGTH + count - firstPart).toLong()
        }
    }

    @Throws(IOException::class)
    private fun readFully(buffer: ByteBuffer, count: Int) {
        var n = 0
        while (n < count) {
            val numRead = channel.read(buffer).toLong()
            if (numRead == -1L) {
                throw EOFException()
            }
            n += numRead.toInt()
        }
    }

    /** Wraps the position if it exceeds the end of the file.  */
    private fun wrapPosition(position: Long): Int {
        val newPosition = if (position < length) position else QueueFileHeader.QUEUE_HEADER_LENGTH + position - length
        require(newPosition < length && position >= 0) { "Position $position invalid outside of storage length $length" }
        return newPosition.toInt()
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
            "New length $size is less than minimum length ${QueueFileHeader.QUEUE_HEADER_LENGTH}"
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
    override fun write(position: Long, buffer: ByteBuffer): Long {
        requireNotClosed()
        val count = buffer.remaining()
        require(count + QueueFileHeader.QUEUE_HEADER_LENGTH <= length) {
            "buffer count ${buffer.remaining()} exceeds storage length $length"
        }
        val wrappedPosition = wrapPosition(position)
        channel.position(wrappedPosition.toLong())
        val linearPart = (length - wrappedPosition).toInt()
        return if (linearPart >= count) {
            writeFully(buffer, count)
            wrapPosition(wrappedPosition + count.toLong()).toLong()
        } else {
            // The write overlaps the EOF.
            // # of bytes to write before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
            if (linearPart > 0) {
                writeFully(buffer, linearPart)
            }
            channel.position(QueueFileHeader.QUEUE_HEADER_LENGTH.toLong())
            writeFully(buffer, count - linearPart)
            (QueueFileHeader.QUEUE_HEADER_LENGTH + count - linearPart).toLong()
        }
    }

    @Throws(IOException::class)
    private fun writeFully(buffer: ByteBuffer, count: Int) {
        var n = 0
        val writeBuffer: ByteBuffer
        if (buffer.remaining() == count) {
            writeBuffer = buffer
        } else if (buffer.remaining() > count) {
            writeBuffer = buffer.slice()
            writeBuffer.limit(count)
        } else {
            throw BufferUnderflowException()
        }
        while (n < count) {
            val numWritten = channel.write(writeBuffer)
            if (numWritten == -1) {
                throw EOFException()
            }
            n += numWritten
        }
        if (writeBuffer !== buffer) {
            buffer.position(buffer.position() + count)
        }
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
        if (isClosed) {
            throw IOException("closed")
        }
    }

    @Throws(IOException::class)
    override fun close() {
        isClosed = true
        channel.close()
        randomAccessFile.close()
    }

    override fun toString(): String {
        return "DirectQueueFileStorage<$fileName>[length=$length]"
    }

    companion object {
        /** Initial file size in bytes.  */
        const val MINIMUM_LENGTH = 4096L // one file system block
    }
}
