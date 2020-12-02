package org.radarbase.util

import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import java.lang.ref.SoftReference
import java.nio.ByteBuffer

/**
 * QueueStorage that provides a buffer around the underlying storage system. This implementation
 * uses a [bufferSize] of 8192, which is the maximum size of a file system block.
 */
class BufferedQueueStorage(
    private val storage: QueueStorage,
    private val bufferSize: Int = 8192,
) : QueueStorage {
    /**
     * Soft reference to the buffer. This may be garbage collected if there is high memory pressure.
     * This is only possible if there is no other hard reference such as a local
     * variable or propery.
     */
    private var bufferSoftRef = SoftReference<BufferState>(null)

    /**
     * Hard reference to the buffer. This can be used to avoid having the buffer garbage collected
     * if there is still critical state in it.
     */
    private var bufferHardRef: BufferState? = null

    /** Get the current state in cache or create a new one. */
    private fun retrieveState(): BufferState = bufferSoftRef.get()
            ?: BufferState(ByteBuffer.allocateDirect(bufferSize), 0L, BufferStatus.INITIAL)
                .also { bufferSoftRef = SoftReference(it) }

    override val length: Long
        get() = storage.length

    override val minimumLength: Long
        get() = storage.minimumLength

    override var maximumLength: Long
        get() = storage.maximumLength
        set(value) {
            storage.maximumLength = value
        }

    override val isClosed: Boolean
        get() = storage.isClosed

    override val isPreExisting: Boolean
        get() = storage.isPreExisting

    private val dataLength
        get() = length - QUEUE_HEADER_LENGTH

    override fun write(position: Long, data: ByteBuffer, mayIgnoreBuffer: Boolean): Long {
        require(data.remaining() <= dataLength)
        val state = retrieveState()

        when {
            // write large buffers without buffering
            data.remaining() > state.buffer.capacity() -> {
                state.flush()
                return storage.write(position, data)
            }
            // write header without buffering
            position < QUEUE_HEADER_LENGTH -> return storage.write(position, data)
            // create a new buffer if not in writing mode
            state.status != BufferStatus.WRITE -> {
                state.initialize(position, BufferStatus.WRITE)
                bufferHardRef = state
            }
            // ensure the buffer can be written to
            !state.isWritable(position) || !state.buffer.hasRemaining() -> {
                // If data cannot be written to the buffer and [mayIgnoreBuffer] is indicated, avoid
                // resetting the buffer so writing can continue at the previous mark after this header
                // has been written.
                if (mayIgnoreBuffer) {
                    if (state.isWritable(wrapPosition(position + data.remaining() - 1))) {
                        state.flush()
                    }
                    return storage.write(position, data)
                } else {
                    state.flush()
                    state.initialize(position, BufferStatus.WRITE)
                }
            }
        }

        // allow writing inside the current buffer
        val previousPosition = state.buffer.position()
        state.position = position

        // Number of bytes that cannot be written using the current buffer
        val numBytesExcluded = data.remaining() - state.buffer.remaining()
        return if (numBytesExcluded > 0) {
            val previousLimit = data.limit()
            data.limit(previousLimit - numBytesExcluded)
            state.buffer.put(data)
            data.limit(previousLimit)
            state.position
        } else {
            state.buffer.put(data)
            val newPosition = state.position
            // when writing inside the buffer, ensure that the final position of the buffer
            // is put back to where it was originally writing to.
            if (state.buffer.position() < previousPosition) {
                state.buffer.position(previousPosition)
            }
            newPosition
        }
    }

    override fun read(position: Long, data: ByteBuffer): Long {
        require(position >= 0 && position < length) { "position $position out of range [0, $length)." }

        val state = retrieveState()
        // Ensure that any write data is written to file and synchronized before attempting to read
        if (state.status == BufferStatus.WRITE) {
            flush()
        }
        // If the buffer does not contain the start position for reading, read it now
        if (
            state.status != BufferStatus.READ
            || position !in state
        ) {
            if (data.remaining() >= state.buffer.capacity()) {
                return storage.read(position, data)
            } else {
                // Align buffer with filesystem block boundaries
                var bufPosition = (position / bufferSize) * bufferSize
                state.initialize(bufPosition, BufferStatus.READ)
                do {
                    bufPosition = storage.read(bufPosition, state.buffer)
                } while (state.buffer.position() == 0)
                state.buffer.flip()
            }
        }

        state.position = position
        val dataCount = data.remaining()
        val bufferCount = state.buffer.remaining()
        val bytesRead = if (dataCount < bufferCount) {
            val previousLimit = state.buffer.limit()
            state.buffer.limit(previousLimit - bufferCount + dataCount)
            data.put(state.buffer)
            state.buffer.limit(previousLimit)
            dataCount
        } else {
            // The buffer may contain too few bytes, resulting in a partial read
            data.put(state.buffer)
            bufferCount
        }
        return wrapPosition(position + bytesRead)
    }

    override fun move(srcPosition: Long, dstPosition: Long, count: Long) {
        retrieveState().clear()
        storage.move(srcPosition, dstPosition, count)
    }

    override fun resize(size: Long) {
        retrieveState().clear()
        storage.resize(size)
    }

    override fun wrapPosition(position: Long): Long = storage.wrapPosition(position)

    override fun close() {
        retrieveState().flush()
        bufferSoftRef = SoftReference(null)
        storage.close()
    }

    override fun flush() {
        retrieveState().flush()
        storage.flush()
    }

    private inner class BufferState(
        val buffer: ByteBuffer,
        var startPosition: Long,
        var status: BufferStatus,
    ) {
        var position: Long
            get() = wrapPosition(buffer.position() + startPosition)
            set(value) {
                buffer.position(translateToBufferPosition(value))
            }

        var limit: Long
            get() = wrapPosition(startPosition + buffer.limit())
            set(value) {
                buffer.limit(translateToBufferPosition(value))
            }

        fun initialize(startPosition: Long, status: BufferStatus) {
            buffer.position(0)
            buffer.limit(buffer.capacity().coerceAtMost((length - startPosition).toInt()))
            this.startPosition = startPosition
            this.status = status
        }

        fun translateToBufferPosition(position: Long): Int {
            require(position in this) { "value $position does not fall in range [$startPosition, $limit)"}
            return (position - startPosition).toInt()
        }

        operator fun contains(position: Long): Boolean = position >= startPosition && position < startPosition + buffer.limit()

        fun isWritable(position: Long): Boolean = position >= startPosition && position <= startPosition + buffer.position()

        fun flush() {
            if (status == BufferStatus.WRITE) {
                status = if (buffer.position() > 0) {
                    buffer.flip()
                    storage.writeFully(startPosition, buffer)

                    // the written buffer can directly be read from 0 to the new flipped limit
                    // (the old position).
                    buffer.position(0)
                    BufferStatus.READ
                } else {
                    BufferStatus.INITIAL
                }
                // without a hard reference, the buffer may be cleared if memory pressure is high
                bufferHardRef = null
            }
        }

        fun clear() {
            flush()
            initialize(0L, BufferStatus.INITIAL)
        }
    }

    private enum class BufferStatus {
        INITIAL, WRITE, READ
    }
}
