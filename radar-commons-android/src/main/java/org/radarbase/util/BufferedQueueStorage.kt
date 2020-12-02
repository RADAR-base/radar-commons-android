package org.radarbase.util

import org.radarbase.util.QueueFileHeader.Companion.QUEUE_HEADER_LENGTH
import org.radarbase.util.QueueStorage.Companion.withAvailable
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
        checkPosition(position, data)
        if (data.remaining() == 0) return position

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
                val bufPosition = (position / bufferSize) * bufferSize
                state.initialize(bufPosition, BufferStatus.WRITE, position)
                bufferHardRef = state
            }
            // ensure the buffer can be written to
            !state.isWritable(position, data.remaining()) -> {
                // If data cannot be written to the buffer and [mayIgnoreBuffer] is indicated, avoid
                // resetting the buffer so writing can continue at the previous mark after this header
                // has been written.
                if (mayIgnoreBuffer) {
                    if (state.isWritable(wrapPosition(position + data.remaining() - 1), 1)) {
                        state.flush()
                    }
                    return storage.write(position, data)
                } else {
                    state.flush()
                    val bufPosition = (position / bufferSize) * bufferSize
                    state.initialize(bufPosition, BufferStatus.WRITE, position)
                }
            }
        }

        // allow writing inside the current buffer
        val previousPosition = state.buffer.position()
        state.position = position

        val newPosition = data.withAvailable(state.buffer.remaining().toLong()) {
            state.buffer.put(it)
            state.position
        }

        // when writing inside the buffer, ensure that the final position of the buffer
        // is put back to where it was originally writing to.
        if (state.buffer.position() < previousPosition) {
            state.buffer.position(previousPosition)
        }

        return wrapPosition(newPosition)
    }

    private fun checkPosition(position: Long, data: ByteBuffer) {
        require(position >= 0 && position < length) { "position $position out of range [0, $length)." }
        require(data.remaining() <= dataLength)
    }

    override fun read(position: Long, data: ByteBuffer): Long {
        checkPosition(position, data)
        if (data.remaining() == 0) return position

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
        val bytesRead = state.buffer.withAvailable(data.remaining().toLong()) {
            val result = it.remaining()
            data.put(it)
            result
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
            get() = buffer.position() + startPosition
            set(value) {
                val newPosition = translateToBufferPosition(value)
                buffer.position(newPosition)
                if (status == BufferStatus.WRITE && newPosition < mark) {
                    mark = newPosition
                }
            }

        var limit: Long
            get() = startPosition + buffer.limit()
            set(value) {
                buffer.limit(translateToBufferPosition(value))
            }

        private var mark: Int = 0

        var markPosition: Long
            get() = startPosition + mark
            set(value) {
                require(value >= startPosition)
                mark = (value - startPosition).toInt()
            }


        fun initialize(bufferPosition: Long, status: BufferStatus, writePosition: Long = bufferPosition) {
            this.startPosition = bufferPosition
            this.status = status
            this.markPosition = writePosition
            val newLimit = buffer.capacity().coerceAtMost((length - bufferPosition).toInt())
            require(newLimit > mark)
            buffer.limit(newLimit)
            buffer.position(mark)
        }

        fun translateToBufferPosition(position: Long): Int {
            require(position in this) { "value $position does not fall in range [$startPosition, $limit)"}
            return (position - startPosition).toInt()
        }

        operator fun contains(position: Long): Boolean = position >= markPosition && position < limit

        fun isWritable(position: Long, count: Int): Boolean = (
                position >= startPosition
                        && position + count >= markPosition
                        && position <= this.position
                        && position < limit)

        fun flush() {
            if (status == BufferStatus.WRITE) {
                status = if (buffer.position() > mark) {
                    buffer.limit(buffer.position())
                    buffer.position(mark)
                    storage.writeFully(markPosition, buffer)

                    // the written buffer can directly be read from mark to the new limit
                    // (the old position).
                    buffer.position(mark)
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
