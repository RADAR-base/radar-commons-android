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
import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import org.radarbase.util.QueueStorage.Companion.withAvailable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
            randomAccessFile.length()
                .also { requireIO(it >= QUEUE_HEADER_LENGTH) { "File length $it of $file is smaller than queue header length $QUEUE_HEADER_LENGTH" } }
        } else {
            randomAccessFile.setLength(initialLength)
            initialLength
        }
        channel = randomAccessFile.channel
    }

    @Throws(IOException::class)
    override fun read(position: Long, data: ByteBuffer): Long {
        requireNotClosed()
        require(position >= 0) { "Read position $position in storage $this must be positive." }
        require(position < length)  { "Read position $position in storage $this must be less than length $length." }

        channel.position(position)
        val numRead = data.withAvailable(length - position) {
            channel.read(it)
        }

        if (numRead == -1) throw EOFException()
        return wrapPosition(position + numRead)
    }

    /** Sets the length of the file.  */
    @Throws(IOException::class)
    override fun resize(size: Long) {
        requireNotClosed()
        if (size == length) {
            return
        }
        require(size <= length || size <= maximumLength) {
            "New length $size of $this exceeds maximum length $maximumLength"
        }
        require(size >= minimumLength) {
            "New length $size of $this is less than minimum length $QUEUE_HEADER_LENGTH"
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
        require(position >= 0) { "Write position $position in storage $this must be positive." }
        require(position < length)  { "Write position $position in storage $this must be less than length $length." }

        channel.position(position)
        val numWritten = data.withAvailable(length - position) {
            channel.write(it)
        }
        if (numWritten == -1) throw EOFException()
        return wrapPosition(position + numWritten)
    }

    @Throws(IOException::class)
    override fun move(srcPosition: Long, dstPosition: Long, count: Long) {
        requireNotClosed()
        require(srcPosition >= 0
                && dstPosition >= 0
                && count > 0
                && srcPosition + count <= length
                && dstPosition + count <= length) {
            "Movement specification src=$srcPosition, count=$count, dst=$dstPosition is invalid for storage $this"
        }
        flush()
        channel.position(dstPosition)
        val bytesTransferred = channel.transferTo(srcPosition, count, channel)
        requireIO(bytesTransferred == count) { "Cannot move all data in $this from $srcPosition to $dstPosition with size $count. Only moved $bytesTransferred." }
    }

    @Throws(IOException::class)
    private fun requireNotClosed() {
        requireIO(!isClosed) { "Queue storage $this is already closed." }
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
