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
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.LENS_FACING
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.os.Process.THREAD_PRIORITY_FOREGROUND
import android.renderscript.Allocation
import android.util.Size
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener.Status.*
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.ppg.RenderContext.Companion.RENDER_CONTEXT_RELEASER
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.ppg.PhoneCameraPpg
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * AppSource manager initialization. After initialization, be sure to call
 * [.setName].
 *
 * @param service service that the manager is started by
 */
class PhonePpgManager(service: PhonePpgService) : AbstractSourceManager<PhonePpgService, PhonePpgState>(service), PhonePpgState.OnActionListener {
    private val ppgTopic: DataCache<ObservationKey, PhoneCameraPpg> = createCache("android_phone_ppg", PhoneCameraPpg())
    private val cameraManager: CameraManager = checkNotNull(service.getSystemService(Context.CAMERA_SERVICE) as CameraManager?)

    @get:Synchronized
    private lateinit var preferredDimensions: Size
    private val mHandler: SafeHandler
    private var mCameraDevice: CameraDevice? = null
    private var mPreviewSession: CameraCaptureSession? = null
    private var doStop: Boolean = false
    private val mProcessor: SafeHandler
    private val cameraOpenCloseLock = Semaphore(1)
    private var mRenderContext: RenderContext? = null
    @get:Synchronized
    private var measurementTime = 60_000L

    init {
        status = READY

        state.stateChangeListener = object : PhonePpgState.OnStateChangeListener {
            override fun release() {
                RENDER_CONTEXT_RELEASER.release()
            }

            override fun acquire() {
                RENDER_CONTEXT_RELEASER.acquire()
            }
        }

        mHandler = SafeHandler.getInstance("PPG", THREAD_PRIORITY_FOREGROUND)
        mProcessor = SafeHandler.getInstance("PPG processing", THREAD_PRIORITY_FOREGROUND)
    }

    override fun start(acceptableIds: Set<String>) {
        logger.debug("Starting PPG manager")
        register()
        state.actionListener = this
        mHandler.start()
        mProcessor.start()
    }


    private fun openCamera(): Boolean {
        try {
            // Do not open the camera if the camera open operation is still in progress
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                logger.error("Failed to acquire camera open lock")
                return false
            }
            val cameraId = backFacingCamera(cameraManager)

            if (cameraId == null) {
                logger.error("Cannot get back-facing camera.")
                disconnect()
                return false
            }

            val videoSize = getImageSize(cameraId)
            if (videoSize == null) {
                logger.error("Cannot determine PPG image size.")
                disconnect()
                return false
            }

            mRenderContext = RenderContext(service, videoSize).apply {
                setImageHandler(mProcessor, this@PhonePpgManager::updatePreview)
            }

            status = CONNECTING

            logger.debug("Opening camera")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    mCameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    mCameraDevice = null
                    disconnect()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    mCameraDevice = null
                    disconnect()
                }
            }, mHandler.handler)

        } catch (e: CameraAccessException) {
            logger.error("Cannot access the camera.", e)
            cameraOpenCloseLock.release()
            return false
        } catch (e: SecurityException) {
            logger.error("No access to camera device", e)
            cameraOpenCloseLock.release()
            return false
        } catch (e: InterruptedException) {
            logger.error("Could not acquire lock to camera", e)
            return false
        }

        return true
    }

    /** Start the preview session. This should only be called once the camera is open.  */
    private fun startPreview() {
        val camera = mCameraDevice ?: return
        val context = mRenderContext ?: return

        try {
            logger.debug("Starting capture session")
            camera.createCaptureSession(listOf(context.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            mPreviewSession = cameraCaptureSession
                            state.recordingStartedNow()
                            status = CONNECTED
                            logger.info("Started PPG capture session")

                            try {
                                // Make a capture request, sending images to the RenderContext and enabling the torch
                                val captureRequest = camera.createCaptureRequest(TEMPLATE_PREVIEW).apply {
                                    addTarget(context.surface)
                                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                }.build()

                                cameraCaptureSession.setRepeatingRequest(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        logger.debug("Completed a capture")
                                        pollDisconnect()
                                    }

                                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                        pollDisconnect()
                                    }
                                }, mHandler.handler)
                            } catch (e: IllegalStateException) {
                                logger.error("Failed to create capture request", e)
                                disconnect()
                            } catch (e: CameraAccessException) {
                                logger.error("Failed to access camera for requesting preview", e)
                                disconnect()
                            }
                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            logger.error("Create capture session failed")
                            disconnect()
                        }
                    }, mHandler.handler)
        } catch (e: CameraAccessException) {
            logger.error("Failed to access camera to make a preview request", e)
            disconnect()
        }

    }

    // Poll whether the preview session should stop
    private fun pollDisconnect() {
        if (state.recordingTime > measurementTime || doStop) {
            disconnect()
        }
    }

    /**
     * Process an image and send it.
     * @param time time in milliseconds since the Unix Epoch that the preview image was captured.
     * @param rgba a linear array of RGBA values. Index 4*i + 0 = r, 4*i + 1 = g, 4*i + 2 = b,
     * 4*i + 3 = a.
     */
    private fun updatePreview(time: Long, rgba: ByteArray) {
        var totalR: Long = 0
        var totalG: Long = 0
        var totalB: Long = 0

        var i = 0
        while (i < rgba.size) {
            totalR += (rgba[i].toInt() and 0xFF).toLong()
            totalG += (rgba[i + 1].toInt() and 0xFF).toLong()
            totalB += (rgba[i + 2].toInt() and 0xFF).toLong()
            i += 4
        }

        val sampleSize = rgba.size / 4

        val range = 255.0 * sampleSize

        val r = (totalR / range).toFloat()
        val g = (totalG / range).toFloat()
        val b = (totalB / range).toFloat()

        send(ppgTopic, PhoneCameraPpg(time / 1000.0, currentTime, sampleSize, r, g, b))
    }

    /** Get the first back-facing camera in the list of cameras returned by the camera manager.  */
    @Throws(CameraAccessException::class)
    private fun backFacingCamera(manager: CameraManager): String? {
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (characteristics.get(LENS_FACING) == LENS_FACING_BACK) {
                return cameraId
            }
        }
        return null
    }

    /**
     * Get the optimal supported image size for preferred statistics. This minimises
     * the Euclidian distance metric between possible sizes and the preferred size.
     */
    @Throws(CameraAccessException::class)
    private fun getImageSize(cameraId: String): Size? {
        val sizes = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(Allocation::class.java)
                ?: emptyArray()

        return if (sizes.isNotEmpty()) {
            chooseOptimalSize(sizes)
        } else {
            disconnect()
            null
        }
    }

    /**
     * Get the optimal image size out of the given sizes. This minimises
     * the Euclidian distance metric between possible sizes and the preferred size.
     */
    private fun chooseOptimalSize(sizes: Array<Size>): Size {
        val prefSize = preferredDimensions

        val minSize = sizes.asSequence().minBy {
            logger.debug("Available preview size {}x{}", it.width, it.height)
            val wDiff = (it.width - prefSize.width).toLong()
            val hDiff = (it.height - prefSize.height).toLong()

            wDiff * wDiff + hDiff * hDiff
        } ?: throw IllegalStateException("Optimal image size cannot be determined.")

        logger.debug("Chosen preview size {}x{}", minSize.width, minSize.height)
        return minSize
    }

    override fun onClose() {
        logger.debug("Closing PPG manager")
        mHandler.stop {
            doStop = true
            mPreviewSession?.let {
                try {
                    it.stopRepeating()
                    it.close()
                } catch (ex: java.lang.IllegalStateException) {
                    logger.info("Failed to stop preview session: {}", ex.toString())
                } catch (ex: CameraAccessException) {
                    logger.info("Failed to access camera to stop preview session: {}", ex.toString())
                } finally {
                    mPreviewSession = null
                }
            }
            mCameraDevice?.close()

            mProcessor.stop {
                mRenderContext?.close()
                mRenderContext = null
            }
        }
    }

    override fun startCamera() {
        mHandler.execute {
            doStop = false
            if (!openCamera()) {
                disconnect()
            }
        }
    }

    override fun stopCamera() {
        mHandler.execute { doStop = true }
    }

    @Synchronized
    internal fun configure(measurementTime: Long, measurementDimensions: Size) {
        this.measurementTime = TimeUnit.SECONDS.toMillis(measurementTime)
        this.preferredDimensions = measurementDimensions
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhonePpgManager::class.java)
    }
}
