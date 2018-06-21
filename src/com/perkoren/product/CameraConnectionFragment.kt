/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/CameraConnectionFragment.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.perkoren.product

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import com.perkoren.product.env.ImageUtils
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


@SuppressLint("ValidFragment")
class CameraConnectionFragment : Fragment {

    private constructor(
            connectionCallback: ConnectionCallback?,
            imageListener: OnImageAvailableListener?,
            layout: Int?,
            inputSize: Size?):super(){
        this.cameraConnectionCallback = connectionCallback
        this.imageListener = imageListener
        this.layout = layout
        this.inputSize = inputSize
    }

    companion object {

        private val MINIMUM_PREVIEW_SIZE = 320
        private val ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        fun newInstance(cameraConnectionCallback: ConnectionCallback?,
            imageListener: OnImageAvailableListener?,
            layout: Int?,
            inputSize: Size?): CameraConnectionFragment {

            return CameraConnectionFragment(cameraConnectionCallback,imageListener, layout,inputSize)
        }

        fun chooseOptimalSize(choices: Array<Size?>, width: Int, height: Int): Size? {
            val minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE)
            val desiredSize = Size(width, height)

            // Collect the supported resolutions that are at least as big as the preview Surface
            var exactSizeFound = false
            val bigEnough = ArrayList<Size>()
            val tooSmall = ArrayList<Size>()
            for (option in choices) {
                if(option!=null) {
                    if (option == desiredSize) {
                        // Set the size but don't return yet so that remaining sizes will still be logged.
                        exactSizeFound = true
                    }

                    if (option.height >= minSize && option.width >= minSize) {
                        bigEnough.add(option)
                    } else {
                        tooSmall.add(option)
                    }
                }
            }

            Log.i(ImageUtils.TAG,"Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize)
            Log.i(ImageUtils.TAG,"Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]")
            Log.i(ImageUtils.TAG,"Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]")

            if (exactSizeFound) {
                Log.i(ImageUtils.TAG,"Exact size match found.")
                return desiredSize
            }

            if (bigEnough.size > 0) {
                val chosenSize = Collections.min(bigEnough, CompareSizesByArea())
                Log.i(ImageUtils.TAG,"Chosen size: " + chosenSize.width + "x" + chosenSize.height)
                return chosenSize
            } else {
                Log.e(ImageUtils.TAG,"Couldn't find any suitable preview size")
                return choices[0]
            }
        }
    }

    private var cameraConnectionCallback: CameraConnectionFragment.ConnectionCallback? = null
    private var imageListener: OnImageAvailableListener? = null
    private var layout: Int? = null
    private var inputSize: Size? = null

    fun setCameraConnectionCallback(cameraConnectionCallback: ConnectionCallback){
        this.cameraConnectionCallback = cameraConnectionCallback
    }

    fun setImageListener(imageListener:OnImageAvailableListener){
        this.imageListener = imageListener
    }

    fun setLayout(layout:Int?){
        this.layout = layout
    }

    fun setInputSize(inputSize: Size){
        this.inputSize = inputSize
    }

    /**
     * [android.view.TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /**
     * ID of the current [CameraDevice].
     */
    private var cameraId: String? = null

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private var textureView: AutoFitTextureView? = null

    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened [CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * The rotation in degrees of the camera sensor from the display.
     */
    private var sensorOrientation: Int? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private var previewSize: Size? = null

    /**
     * [android.hardware.camera2.CameraDevice.StateCallback]
     * is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = cd
            createCameraPreviewSession()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
        }

        override fun onError(cd: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
            val activity = activity
            activity?.finish()
        }
    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An [ImageReader] that handles preview frame capture.
     */
    private var previewReader: ImageReader? = null

    /**
     * [android.hardware.camera2.CaptureRequest.Builder] for the camera preview
     */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    private var previewRequest: CaptureRequest? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult) {
        }

        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
        }
    }

    /**
     * Callback for Activities to use to initialize their data once the
     * selected preview size is known.
     */
    interface ConnectionCallback {
        fun onPreviewSizeChosen(size: Size, cameraRotation: Int)
    }

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        val activity = activity
        activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(layout!!, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture) as AutoFitTextureView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView!!.isAvailable) {
            openCamera(textureView!!.width, textureView!!.height)
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    fun setCamera(cameraId: String?) {
        this.cameraId = cameraId
    }

    /**
     * Sets up member variables related to camera.
     */
    private fun setUpCameraOutputs() {
        val activity = activity
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                    inputSize!!.width,
                    inputSize!!.height)

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
            } else {
                textureView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
            }
        } catch (e: CameraAccessException) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            // TODO(andrewharp): abstract ErrorDialog/RuntimeException handling out into new method and
            // reuse throughout app.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            throw RuntimeException(getString(R.string.camera_error))
        }

        cameraConnectionCallback!!.onPreviewSizeChosen(previewSize!!, sensorOrientation!!)
    }

    /**
     * Opens the camera specified by [CameraConnectionFragment.cameraId].
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        val activity = activity
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }

    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != previewReader) {
                previewReader!!.close()
                previewReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
        }

    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView!!.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            Log.i(ImageUtils.TAG,"Opening camera preview: " + previewSize!!.width + "x" + previewSize!!.height)

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2)

            previewReader!!.setOnImageAvailableListener(imageListener, backgroundHandler)
            previewRequestBuilder!!.addTarget(previewReader!!.surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
                    Arrays.asList(surface, previewReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder!!.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // Flash is automatically enabled when necessary.
                                previewRequestBuilder!!.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder!!.build()
                                captureSession!!.setRepeatingRequest(
                                        previewRequest!!, captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                Log.e(ImageUtils.TAG, "Exception! ${e.message}")
                            }

                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            showToast("Failed")
                        }
                    }, null)
        } catch (e: CameraAccessException) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
        }

    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == textureView || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize!!.height,
                    viewWidth.toFloat() / previewSize!!.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView!!.setTransform(matrix)
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                    lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            val activity = activity
            return AlertDialog.Builder(activity)
                    .setMessage(arguments.getString(ARG_MESSAGE))
                    .setPositiveButton(
                            android.R.string.ok
                    ) { dialogInterface, i -> activity.finish() }
                    .create()
        }

        companion object {
            private val ARG_MESSAGE = "message"

            fun newInstance(message: String): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }
    }

}
