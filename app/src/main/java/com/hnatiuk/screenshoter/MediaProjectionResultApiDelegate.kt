package com.hnatiuk.screenshoter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.activity.ComponentActivity
import com.hnatiuk.screenshoter.result.ScreenResult
import com.hnatiuk.screenshoter.result.Screenshot
import java.lang.ref.WeakReference

/**
 * Based on MediaProjectionDelegateV21
 */
class MediaProjectionResultApiDelegate(
    activity: ComponentActivity,
    private val permissionRequestCode: Int
) {

    private val activityReference = WeakReference(activity)

    private val projectionManager = activity.getMediaProjectionManager()

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null

    private var pendingResult: ScreenResult? = null

    private val getMediaProjection = activity.registerForActivityResult(GetMediaProjection(activity)) {
        onActivityResult(permissionRequestCode, it.resultCode, it.data)
    }

    fun makeScreenshot(): ScreenResult {
        Utils.checkOnMainThread()
        val result = pendingResult
        if (result != null) {
            return result
        }
        val activity = activityReference.get()
        if (activity == null) {
            val exception = ScreenshotException.NoActivityReference()
            return ScreenResult.error(exception)
        }
        val screenshotSpec = ScreenshotSpec(activity)
        val newResult = ScreenResult(screenshotSpec)
        val projection = LAST_ACCESS_DATA?.let(::getMediaProjection)
        if (projection == null) {
            //activity.startActivityForResult(projectionManager.createScreenCaptureIntent(), permissionRequestCode)
            getMediaProjection.launch(Unit)
        } else {
            captureInBackground(projection, screenshotSpec)
        }
        pendingResult = newResult
        return newResult
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val screenshotSpec = pendingResult?.spec ?: return
        if (requestCode == permissionRequestCode) {
            val accessData = ProjectionAccessData(resultCode, data)
            val mediaProjection = getMediaProjection(accessData)
            if (mediaProjection != null) {
                captureInBackground(mediaProjection, screenshotSpec, DIALOG_CLOSED_DELAY_MS)
                LAST_ACCESS_DATA = accessData
            } else {
                val exception = ScreenshotException.FailedToCreateMediaProjection()
                onScreenshotCaptureFailed(exception)
            }
        }
    }

    private fun captureInBackground(projection: MediaProjection, screenshotSpec: ScreenshotSpec, delayMs: Long = 0L) {
        val captureThread = startCaptureThread()
        val captureThreadHandler = Handler(captureThread.looper)
        captureThreadHandler.postDelayed({
            doCapture(projection, screenshotSpec, captureThreadHandler)
        }, delayMs)
    }

    private fun doCapture(projection: MediaProjection, spec: ScreenshotSpec, handler: Handler) {
        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        try {
            imageReader = createImageReader(projection, spec, handler)
            virtualDisplay = createVirtualDisplay(projection, imageReader.surface, spec, handler)
            val callback = ReleaseOnStopCallback(projection, imageReader, virtualDisplay)
            projection.registerCallback(callback, handler)
        } catch (e: Exception) {
            Utils.releaseSafely(virtualDisplay)
            Utils.closeSafely(imageReader)
            Utils.stopSafely(projection)
            onScreenshotCaptureFailed(ScreenshotException.ScreenTakeException(e))
        }

    }

    private fun createImageReader(projection: MediaProjection, spec: ScreenshotSpec, callbackHandler: Handler): ImageReader {
        //Lint forces to use ImageFormat (API 23+) instead of PixelFormat, even though ImageReader docs say that PixelFormat is supported
        //noinspection WrongConstant
        val imageReader = ImageReader.newInstance(spec.width, spec.height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(ImageAvailableListener(projection, spec.width, spec.height), callbackHandler)
        return imageReader
    }

    private fun createVirtualDisplay(projection: MediaProjection, surface: Surface, spec: ScreenshotSpec, callbackHandler: Handler): VirtualDisplay {
        return projection.createVirtualDisplay(
            CAPTURE_THREAD_NAME, spec.width, spec.height, spec.densityDpi,
            VIRTUAL_DISPLAY_FLAGS, surface, null,
            callbackHandler
        )
    }

    private fun onScreenshotCaptured(bitmap: Bitmap) {
        mainThreadHandler.post {
            val screenshot = Screenshot.ScreenshotBitmap(bitmap)
            pendingResult?.onSuccess(screenshot)
            pendingResult = null
            stopCaptureThread()
        }
    }

    private fun onScreenshotCaptureFailed(cause: ScreenshotException) {
        mainThreadHandler.post {
            pendingResult?.onError(cause)
            pendingResult = null
            stopCaptureThread()
        }
    }

    private fun startCaptureThread(): HandlerThread {
        Utils.checkOnMainThread()
        var thread = captureThread
        if (thread == null) {
            thread = HandlerThread(CAPTURE_THREAD_NAME)
            thread.start()
            captureThread = thread
        }
        return thread
    }

    private fun stopCaptureThread() {
        captureThread?.let {
            Utils.interruptSafely(it)
            captureThread = null
        }
    }

    private fun getMediaProjection(accessData: ProjectionAccessData): MediaProjection? {
        return accessData.data?.let { data -> projectionManager.getMediaProjection(accessData.resultCode, data) }
    }

    private fun Activity.getMediaProjectionManager(): MediaProjectionManager = requireNotNull(
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    )

    private inner class ImageAvailableListener(
        private val projection: MediaProjection,
        private val width: Int,
        private val height: Int
    ) : ImageReader.OnImageAvailableListener {
        private var processed = false

        override fun onImageAvailable(reader: ImageReader) {
            if (processed) return
            processed = true
            var image: Image? = null
            var bitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap?.copyPixelsFromBuffer(buffer)
                    onScreenshotCaptured(bitmap)
                } else {
                    val exception = ScreenshotException.FailedToAcquireImage()
                    onScreenshotCaptureFailed(exception)
                }
            } catch (e: Exception) {
                onScreenshotCaptureFailed(ScreenshotException.ScreenTakeException(e))
                bitmap?.recycle()
            } finally {
                Utils.closeSafely(image)
                Utils.stopSafely(projection)
            }
        }
    }

    private class ReleaseOnStopCallback constructor(
        private val projection: MediaProjection,
        private val imageReader: ImageReader,
        private val virtualDisplay: VirtualDisplay
    ) : MediaProjection.Callback() {

        override fun onStop() {
            Utils.releaseSafely(virtualDisplay)
            Utils.closeSafely(imageReader)
            projection.unregisterCallback(this)
        }
    }

    private class ProjectionAccessData(
        val resultCode: Int,
        val data: Intent?
    )

    companion object {
        private const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val DIALOG_CLOSED_DELAY_MS = 150L

        private const val CAPTURE_THREAD_NAME = "screenshotty"

        private var LAST_ACCESS_DATA: ProjectionAccessData? = null
    }
}