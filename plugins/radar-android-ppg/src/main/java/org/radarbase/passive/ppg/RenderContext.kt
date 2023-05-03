/*
 * Copyright 2018 The Hyve
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

package org.radarbase.passive.ppg

import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.RenderScript.CREATE_FLAG_LOW_POWER
import android.renderscript.RenderScript.ContextType.DEBUG
import android.renderscript.Type
import android.util.Size
import android.view.Surface
import com.vl.recordaf.ScriptC_yuv2rgb
import org.radarbase.android.util.SafeHandler
import org.radarbase.util.CountedReference
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Render context to accept images from a camera preview and convert them to RGB.
 * This context requires that the camera record image data in [ImageFormat.YUV_420_888]
 * format.
 * @param context rendering context.
 * @param dimensions preview image dimension size.
 */
internal class RenderContext(context: Context, dimensions: Size) : Closeable {

    private val rs: RenderScript
    private val input: Allocation
    private val output: Allocation
    private var buffer: ByteArray? = null
    private val yuvToRgbIntrinsic: ScriptC_yuv2rgb

    /**
     * Get surface to write YUV data to.
     */
    val surface: Surface
        get() = input.surface

    /**
     * Converts the current image in the input buffer to an RGB byte array. The byte array
     * should not be stored, but immediately analyzed or copied.
     */
    private val rgb: ByteArray
        get() {
            val bytes = checkNotNull(buffer) { "RenderContext already closed" }
            input.ioReceive()
            yuvToRgbIntrinsic._gCurrentFrame = input
            yuvToRgbIntrinsic.forEach_yuv2rgb(output)
            output.copyTo(bytes)
            return bytes
        }

    init {
        RENDER_CONTEXT_RELEASER.acquire()
        this.rs = RenderScript.create(context, DEBUG, CREATE_FLAG_LOW_POWER)

        val inType = Type.Builder(rs, Element.U8(rs))
                .setX(dimensions.width)
                .setY(dimensions.height)
                .setYuvFormat(ImageFormat.YUV_420_888)
                .create()

        input = Allocation.createTyped(rs, inType,
                Allocation.USAGE_SCRIPT or Allocation.USAGE_IO_INPUT)

        val outType = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(dimensions.width)
                .setY(dimensions.height)
                .create()

        output = Allocation.createTyped(rs, outType, Allocation.USAGE_SCRIPT)

        buffer = ByteArray(dimensions.width * dimensions.height * 4)

        yuvToRgbIntrinsic = ScriptC_yuv2rgb(rs)
    }

    /**
     * Set callback to handle each RGB image.
     *
     * Receive an RGB image with time in milliseconds since the Unix Epoch that the preview image
     * was captured and linear array of RGBA values. Index 4*i + 0 = r, 4*i + 1 = g, 4*i + 2 = b,
     * 4*i + 3 = a.
     *
     * @param listener callback
     * @param handler thread to call the callback on.
     */
    fun setImageHandler(handler: SafeHandler, listener: (time: Long, rgba: ByteArray) -> Unit) {
        input.setOnBufferAvailableListener {
            val time = System.currentTimeMillis()
            handler.execute { listener(time, rgb) }
        }
    }

    /**
     * Close the context and destroy any resources associated.
     */
    override fun close() {
        input.setOnBufferAvailableListener(null)
        input.destroy()
        output.destroy()
        yuvToRgbIntrinsic.destroy()
        buffer = null
        rs.destroy()
        RENDER_CONTEXT_RELEASER.release()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RenderContext::class.java)

        val RENDER_CONTEXT_RELEASER: CountedReference<Unit> = CountedReference(
                creator = {},
                destroyer = {
                    logger.info("Releasing RenderScript context")
                    RenderScript.releaseAllContexts()
                })
    }
}
