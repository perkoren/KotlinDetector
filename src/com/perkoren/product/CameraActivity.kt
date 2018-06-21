/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/CameraActivity.java
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

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.*
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import com.perkoren.product.env.ImageUtils

abstract class CameraActivity : Activity(), OnImageAvailableListener, Camera.PreviewCallback {

    var isDebug = false
        private set

    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    protected var luminanceStride: Int = 0
        private set

    protected var previewWidth = 0
    protected var previewHeight = 0

    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null

    private var lastPreviewFrame: ByteArray? = null


    protected val screenOrientation: Int
        get() {
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_270 -> return 270
                Surface.ROTATION_180 -> return 180
                Surface.ROTATION_90 -> return 90
                else -> return 0
            }
        }
    //private val layoutId: Int =
    protected abstract val desiredPreviewFrameSize: Size

    fun getLuminance(): ByteArray? {
        return yuvBytes[0]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(ImageUtils.TAG, "onCreate " + this)
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_camera)

        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }
    }

    protected fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    /**
     * Callback for android.hardware.Camera API
     */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        if (isProcessingFrame) {
            Log.w(ImageUtils.TAG, "Dropping frame!")
            return
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                val previewSize = camera.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
            return
        }

        isProcessingFrame = true
        lastPreviewFrame = bytes
        yuvBytes[0] = bytes
        luminanceStride = previewWidth

        imageConverter = Runnable { ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes!!) }

        postInferenceCallback = Runnable {
            camera.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    /**
     * Callback for Camera2 API
     */
    override fun onImageAvailable(reader: ImageReader) {
        //We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return

            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            luminanceStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                        yuvBytes[0]!!,
                        yuvBytes[1]!!,
                        yuvBytes[2]!!,
                        previewWidth,
                        previewHeight,
                        luminanceStride,
                        uvRowStride,
                        uvPixelStride,
                        rgbBytes!!)
            }

            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }

            processImage()
        } catch (e: Exception) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
            Trace.endSection()
            return
        }

        Trace.endSection()
    }

    @Synchronized
    public override fun onStart() {
        Log.d(ImageUtils.TAG, "onStart " + this)
        super.onStart()
    }

    @Synchronized
    public override fun onResume() {
        Log.d(ImageUtils.TAG, "onResume " + this)
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    @Synchronized
    public override fun onPause() {
        Log.d(ImageUtils.TAG, "onPause " + this)

        if (!isFinishing) {
            Log.d(ImageUtils.TAG, "Requesting finish")
            finish()
        }

        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            Log.e(ImageUtils.TAG, "Exception! ${e.message}")
        }

        super.onPause()
    }

    @Synchronized
    public override fun onStop() {
        Log.d(ImageUtils.TAG, "onStop " + this)
        super.onStop()
    }

    @Synchronized
    public override fun onDestroy() {
        Log.d(ImageUtils.TAG, "onDestroy " + this)
        super.onDestroy()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable) {
        if (handler != null) {
            handler!!.post(r)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.size > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) || shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(this@CameraActivity,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA, PERMISSION_STORAGE), PERMISSIONS_REQUEST)
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private fun isHardwareLevelSupported(
            characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                return cameraId
            }
        } catch (e: CameraAccessException) {
            Log.e(ImageUtils.TAG, "Not allowed to access camera")
        }

        return null
    }

    protected fun setFragment() {
        val cameraId = chooseCamera()
        if (cameraId == null) {
            Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show()
            finish()
        }

        val fragment: Fragment


        val connCallback = object : CameraConnectionFragment.ConnectionCallback {

            override fun onPreviewSizeChosen(size: Size, rotation: Int) {
                previewHeight = size.height
                previewWidth = size.width
                this@CameraActivity.onPreviewSizeChosen(size, rotation)
            }
        }

        val camera2Fragment = CameraConnectionFragment.newInstance(connCallback,
                this,
                R.layout.camera_connection_fragment_tracking,
                desiredPreviewFrameSize)

        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment


        fragmentManager
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                Log.d(ImageUtils.TAG, "Initializing buffer $i at size ${buffer.capacity()}")
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    fun requestRender() {
        val overlay = findViewById(R.id.debug_overlay) as OverlayView
        overlay?.postInvalidate()
    }

    fun addCallback(callback: OverlayView.DrawCallback) {
        val overlay = findViewById(R.id.debug_overlay) as OverlayView
        overlay?.addCallback(callback)
    }

    open fun onSetDebug(debug: Boolean) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            isDebug = !isDebug
            requestRender()
            onSetDebug(isDebug)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    protected fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }

    protected abstract fun processImage()

    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)

    companion object {
        private val PERMISSIONS_REQUEST = 1

        private val PERMISSION_CAMERA = Manifest.permission.CAMERA
        private val PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
