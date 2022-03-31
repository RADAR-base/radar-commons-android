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
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * An efficient, file-based, FIFO queue. Additions and removals are O(1). Writes are
 * synchronous; data will be written to disk before an operation returns.
 * The underlying file is structured to survive process and even system crashes. If an I/O
 * exception is thrown during a mutating change, the change is aborted. It is safe to continue to
 * use a `QueueFile` instance after an exception.
 *
 *
 * **Note that this implementation is not synchronized.**
 *
 *
 * In a traditional queue, the remove operation returns an element. In this queue,
 * [peek] and [remove] are used in conjunction. Use
 * [peek] to retrieve the first element, and then [remove] to remove it after
 * successful processing. If the system crashes after [peek] and during processing, the
 * element will remain in the queue, to be processed when the system restarts.
 *
 * This class is an adaptation of com.squareup.tape2, allowing multi-element writes. It also
 * removes legacy support.
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Joris Borgdorff (joris@thehyve.nl)
 */
class QueueFile @Throws(IOException::class)
constructor(private val storage: QueueStorage) : Closeable, Iterable<InputStream> {
    /**
     * The underlying file. Uses a ring buffer to store entries. Designed so that a modification
     * isn't committed or visible until we write the header. The header is much smaller than a
     * segment. Storing the file length ensures we can recover from a failed expansion
     * (i.e. if setting the file length succeeds but the process dies before the data can be
     * copied).
     * <pre>
     * Format:
     * 36 bytes         Header
     * ...              Data
     *
     * Header:
     * 4 bytes          Version
     * 8 bytes          File length
     * 4 bytes          Element count
     * 8 bytes          Head element position
     * 8 bytes          Tail element position
     * 4 bytes          Header checksum
     *
     * Element:
     * 4 bytes          Data length `n`
     * 1 byte           Element header length checksum
     * `n` bytes          Data
    </pre> *
     */
    private val header: QueueFileHeader = QueueFileHeader(storage)

    /** Returns the number of elements in this queue.  */
    val size: Int
        get() = header.count

    /** File size in bytes  */
    val fileSize: Long
        get() = header.length

    /** Pointer to first (or eldest) element.  */
    private val firstElements = ArrayDeque<QueueFileElement>()

    /** Pointer to last (or newest) element.  */
    private val last: QueueFileElement

    /**
     * The number of times this file has been structurally modified - it is incremented during
     * [.remove] and [.elementOutputStream]. Used by [ElementIterator]
     * to guard against concurrent modification.
     */
    private val modCount = AtomicInteger(0)

    private val elementHeaderBuffer = ByteBuffer.allocate(QueueFileElement.ELEMENT_HEADER_LENGTH)

    /** Returns true if this queue contains no entries.  */
    val isEmpty: Boolean
        get() = size == 0

    var maximumFileSize: Long
        get() = storage.maximumLength
        set(newSize) {
            storage.maximumLength = newSize
        }

    init {
        try {
            if (header.length < storage.length) {
                this.storage.resize(header.length)
            }

            readElement(storage.wrapPosition(header.firstPosition))
                .takeUnless { it.isEmpty }
                ?.let { firstElements += it }

            last = readElement(storage.wrapPosition(header.lastPosition))
        } catch (ex: IllegalArgumentException) {
            throw IOException("Cannot initialize queue with header $header", ex)
        }
    }

    /**
     * Read element header data into given element.
     *
     * @param position position of the element
     * @param elementToUpdate element to update with found information
     * @throws IOException if the header is incorrect and so the file is corrupt.
     */
    @Throws(IOException::class)
    private fun readElement(position: Long, elementToUpdate: QueueFileElement) {
        if (position == 0L) {
            elementToUpdate.reset()
            return
        }

        elementHeaderBuffer.rewind()
        storage.readFully(position, elementHeaderBuffer)
        elementHeaderBuffer.flip()

        elementToUpdate.position = position
        elementToUpdate.length = elementHeaderBuffer.int

        val crc = elementHeaderBuffer.get()

        if (crc != elementToUpdate.crc) {
            logger.error("Failed to verify {}: crc {} does not match stored checksum {}. "
                    + "QueueFile is corrupt.",
                elementToUpdate, elementToUpdate.crc, crc)

            close()
            throw IOException("Element is not correct; queue file is corrupted")
        }
    }

    /**
     * Read a new element.
     *
     * @param position position of the element header
     * @return element with information found at position
     * @throws IOException if the header is incorrect and so the file is corrupt.
     */
    @Throws(IOException::class)
    private fun readElement(position: Long): QueueFileElement {
        return QueueFileElement().apply {
            readElement(position, this)
        }
    }

    /**
     * Adds an element to the end of the queue.
     */
    @Throws(IOException::class)
    fun elementOutputStream(): QueueFileOutputStream {
        requireNotClosed()
        return QueueFileOutputStream(this, header, storage, last.nextPosition)
    }

    /** Number of bytes used in the file.  */
    val usedBytes: Long
        get() {
            if (isEmpty) {
                return QUEUE_HEADER_LENGTH
            }

            val firstPosition = firstElements.first.position
            return last.nextPosition - firstPosition + if (last.position >= firstPosition) {
                QUEUE_HEADER_LENGTH
            } else {
                // tail < head. The queue wraps.
                header.length
            }
        }

    /** Returns an InputStream to read the eldest element. Returns null if the queue is empty.  */
    @Throws(IOException::class)
    fun peek(): InputStream? {
        requireNotClosed()
        return if (!isEmpty) QueueFileInputStream(firstElements.first, storage, modCount) else null
    }

    /**
     * Returns an iterator over elements in this QueueFile.
     *
     *
     * The iterator disallows modifications to be made to the QueueFile during iteration.
     */
    override fun iterator(): Iterator<InputStream> = ElementIterator()

    internal inner class ElementIterator : Iterator<InputStream> {
        /** Index of element to be returned by subsequent call to next.  */
        private var nextElementIndex: Int = 0

        /** Position of element to be returned by subsequent call to next.  */
        private var nextElementPosition: Long = if (firstElements.isEmpty()) 0 else firstElements.first.position

        /**
         * The [.modCount] value that the iterator believes that the backing QueueFile should
         * have. If this expectation is violated, the iterator has detected concurrent modification.
         */
        private val expectedModCount = modCount.get()

        private var cacheIterator: Iterator<QueueFileElement>? = firstElements.iterator()
        private var previousCached: QueueFileElement? = QueueFileElement()

        private fun checkConditions() {
            check(!storage.isClosed) { "storage is closed" }
            if (modCount.get() != expectedModCount) {
                throw ConcurrentModificationException()
            }
        }

        override fun hasNext(): Boolean {
            checkConditions()
            return nextElementIndex != header.count
        }

        override fun next(): InputStream {
            checkConditions()
            if (nextElementIndex >= header.count) {
                throw NoSuchElementException()
            }

            val current: QueueFileElement
            val currentIterator = cacheIterator
            if (currentIterator != null && currentIterator.hasNext()) {
                current = currentIterator.next()
                val previousCached = checkNotNull(previousCached) { "Missing previous value in iterator" }
                current.updateIfMoved(previousCached, header)
                previousCached.update(current)
            } else {
                cacheIterator = null
                previousCached = null
                try {
                    current = readElement(nextElementPosition)
                } catch (ex: IOException) {
                    throw IllegalStateException("Cannot read element", ex)
                }

                firstElements += current
            }
            val input = QueueFileInputStream(current, storage, modCount)

            // Update the pointer to the next element.
            nextElementPosition = storage.wrapPosition(current.nextPosition)
            nextElementIndex++

            // Return the read element.
            return input
        }

        override fun toString(): String {
            return "QueueFile[position=$nextElementPosition, index=$nextElementIndex]"
        }
    }

    /**
     * Removes the eldest `n` elements.
     *
     * @throws NoSuchElementException if more than the available elements are requested to be removed
     */
    @Throws(IOException::class)
    fun remove(n: Int) {
        requireNotClosed()
        require(n >= 0) { "Cannot remove negative ($n) number of elements." }
        if (n == 0) {
            return
        }
        if (n == header.count) {
            clear()
            return
        }
        if (n > header.count) {
            throw NoSuchElementException(
                    "Cannot remove more elements (" + n + ") than present in queue (" + header.count + ").")
        }

        // Read the position and length of the new first element.
        var newFirst = QueueFileElement()
        val previous = QueueFileElement()

        var i = 0
        // remove from cache first
        while (i < n && !firstElements.isEmpty()) {
            newFirst.update(firstElements.removeFirst())
            newFirst.updateIfMoved(previous, header)
            previous.update(newFirst)
            i++
        }

        if (firstElements.isEmpty()) {
            // if the cache contained less than n elements, skip from file
            // read one additional element to become the first element of the cache.
            while (i <= n) {
                readElement(storage.wrapPosition(newFirst.nextPosition), newFirst)
                i++
            }
            // the next element was read from file and will become the next first element
            firstElements += newFirst
        } else {
            newFirst = firstElements.first
            newFirst.updateIfMoved(previous, header)
        }

        // Commit the header.
        modCount.incrementAndGet()
        header.firstPosition = newFirst.position
        header.count -= n
        truncateIfNeeded()
        header.write()
    }

    /**
     * Truncate file if a lot of space is empty and no copy operations are needed.
     */
    @Throws(IOException::class)
    private fun truncateIfNeeded() {
        if (
            header.lastPosition >= header.firstPosition
            && last.nextPosition <= maximumFileSize
        ) {
            var newLength = header.length
            var goalLength = newLength / 2
            val bytesUsed = usedBytes
            val maxExtent = last.nextPosition

            while (
                goalLength >= storage.minimumLength
                && maxExtent <= goalLength
                && bytesUsed <= goalLength / 2
            ) {
                newLength = goalLength
                goalLength /= 2
            }
            if (newLength < header.length) {
                logger.debug("Truncating {} from {} to {}", this, header.length, newLength)
                storage.resize(newLength)
                header.length = newLength
            }
        }
    }

    /** Clears this queue. Truncates the file to the initial size.  */
    @Throws(IOException::class)
    fun clear() {
        requireNotClosed()

        firstElements.clear()
        last.reset()
        header.clear()

        if (header.length != storage.minimumLength) {
            storage.resize(storage.minimumLength)
            header.length = storage.minimumLength
        }

        header.write()

        modCount.incrementAndGet()
    }

    @Throws(IOException::class)
    private fun requireNotClosed() {
        requireIO(!storage.isClosed) { "storage $header is closed" }
    }

    @Throws(IOException::class)
    override fun close() {
        storage.close()
    }

    override fun toString(): String {
        return "QueueFile[storage=$storage, header=$header, first=$firstElements, last=$last]"
    }

    @Throws(IOException::class)
    internal fun commitOutputStream(newFirst: QueueFileElement, newLast: QueueFileElement, count: Int) {
        if (!newLast.isEmpty) {
            last.update(newLast)
            header.lastPosition = newLast.position
        }
        if (!newFirst.isEmpty && firstElements.isEmpty()) {
            firstElements += newFirst
            header.firstPosition = newFirst.position
        }
        header.count += count
        header.write()
        modCount.incrementAndGet()
    }

    @Throws(IOException::class)
    fun growStorage(size: Long, position: Long, beginningOfFirstElement: Long) {
        val oldLength = header.length
        require(size >= oldLength) { "File length may not be decreased" }
        require(size <= maximumFileSize) { "File length may not exceed maximum file length" }
        require(beginningOfFirstElement < oldLength) { "First element position may not exceed file size, not $beginningOfFirstElement" }
        require(beginningOfFirstElement >= 0) { "First element position must be positive, not $beginningOfFirstElement" }
        require(position <= oldLength) { "Position may not exceed file size, not $position" }
        require(position >= 0) { "Position must be positive, not $position" }

        storage.resize(size)
        header.length = size

        compact(position, beginningOfFirstElement, oldLength)

        header.write()
    }

    // Calculate the position of the tail end of the data in the ring buffer
    // If the buffer is split, we need to make it contiguous
    private fun compact(position: Long, beginningOfFirstElement: Long, newBufferPosition: Long) {
        if (position <= beginningOfFirstElement) {
            if (position > QUEUE_HEADER_LENGTH) {
                val count = position - QUEUE_HEADER_LENGTH
                storage.move(QUEUE_HEADER_LENGTH, newBufferPosition, count)
            }
            modCount.incrementAndGet()

            // Last position was moved forward in the copy
            val positionUpdate = newBufferPosition - QUEUE_HEADER_LENGTH
            if (header.lastPosition < beginningOfFirstElement) {
                header.lastPosition += positionUpdate
                last.position = header.lastPosition
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueueFile::class.java)

        @Throws(IOException::class)
        fun newDirect(file: File, maxSize: Long): QueueFile {
            return try {
                QueueFile(
                    BufferedQueueStorage(
                        DirectQueueFileStorage(file, DirectQueueFileStorage.MINIMUM_LENGTH, maxSize)
                    )
                )
            } catch (ex: IllegalArgumentException) {
                throw IOException("Cannot create queue", ex)
            }
        }
    }
}
