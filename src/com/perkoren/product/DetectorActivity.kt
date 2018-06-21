/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/DetectorActivity.java
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

import android.graphics.*
import android.graphics.Bitmap.Config
import android.graphics.Paint.Style
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import com.perkoren.product.OverlayView.DrawCallback
import com.perkoren.product.env.BorderedText
import com.perkoren.product.env.ImageUtils
import com.perkoren.product.tracking.MultiBoxTracker
import java.io.IOException
import java.util.*

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
class DetectorActivity : CameraActivity(), OnImageAvailableListener {

    private var sensorOrientation: Int? = null

    private var detector: Classifier? = null

    private var lastProcessingTimeMs: Long = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var computingDetection = false

    private var timestamp: Long = 0

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var tracker: MultiBoxTracker? = null

    private var luminanceCopy: ByteArray? = null

    private var borderedText: BorderedText? = null

    lateinit var trackingOverlay: OverlayView

    override val desiredPreviewFrameSize: Size
        get() = DESIRED_PREVIEW_SIZE

    public override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)

        tracker = MultiBoxTracker(this)

        var cropSize = TF_OD_API_INPUT_SIZE

        try {
            detector = TensorFlowObjectDetectionAPIModel.create(
                    assets, TF_OD_API_MODEL_FILE, TF_OD_API_LABELS_FILE, TF_OD_API_INPUT_SIZE)
            cropSize = TF_OD_API_INPUT_SIZE
        } catch (e: IOException) {
            Log.e(ImageUtils.TAG,"Exception initializing classifier!", e)
            val toast = Toast.makeText(
                    applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT)
            toast.show()
            finish()
        }


        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - screenOrientation
        Log.i(ImageUtils.TAG,"Camera orientation relative to screen canvas: $sensorOrientation")

        Log.i(ImageUtils.TAG,"Initializing at size $previewWidth x $previewHeight")
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                cropSize, cropSize,
                sensorOrientation!!, MAINTAIN_ASPECT)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay) as OverlayView

        val drawCall = object: DrawCallback{
            override fun drawCallback(canvas: Canvas){
                tracker!!.draw(canvas)
                if (isDebug) {
                    tracker!!.drawDebug(canvas)
                }
            }
        }
        trackingOverlay.addCallback(drawCall)


        val thisDrawCall = object: DrawCallback{

            override fun drawCallback(canvas: Canvas){
                    if (!isDebug) {
                        return
                    }
                    val copy = cropCopyBitmap ?: return

                    val backgroundColor = Color.argb(100, 0, 0, 0)
                    canvas.drawColor(backgroundColor)

                    val matrix = Matrix()
                    val scaleFactor = 2f
                    matrix.postScale(scaleFactor, scaleFactor)
                    matrix.postTranslate(
                            canvas.width - copy.width * scaleFactor,
                            canvas.height - copy.height * scaleFactor)
                    canvas.drawBitmap(copy, matrix, Paint())

                    val lines = Vector<String>()
                    if (detector != null) {
                        val statString = detector!!.statString
                        val statLines = statString.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        for (line in statLines) {
                            lines.add(line)
                        }
                    }
                    lines.add("")

                    lines.add("Frame: " + previewWidth + "x" + previewHeight)
                    lines.add("Crop: " + copy.width + "x" + copy.height)
                    lines.add("View: " + canvas.width + "x" + canvas.height)
                    lines.add("Rotation: " + sensorOrientation!!)
                    lines.add("Inference time: " + lastProcessingTimeMs + "ms")

                    borderedText!!.drawLines(canvas, 10f, (canvas.height - 10).toFloat(), lines)
            }
        }

        addCallback(thisDrawCall)

    }

    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        val originalLuminance = getLuminance()
        tracker!!.onFrame(
                previewWidth,
                previewHeight,
                luminanceStride,
                sensorOrientation!!,
                originalLuminance,
                timestamp)
        trackingOverlay.postInvalidate()

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage()
            return
        }
        computingDetection = true
        Log.i(ImageUtils.TAG,"Preparing image $currTimestamp for detection in bg thread.")

        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)

        if (luminanceCopy == null) {
            luminanceCopy = ByteArray(originalLuminance!!.size)
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy!!, 0, originalLuminance!!.size)
        readyForNextImage()

        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap!!)
        }

        runInBackground(
                Runnable {
                    Log.i(ImageUtils.TAG,"Running detection on image $currTimestamp")
                    val startTime = SystemClock.uptimeMillis()
                    val results = detector!!.recognizeImage(croppedBitmap!!)
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

                    cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
                    val canvas = Canvas(cropCopyBitmap!!)
                    val paint = Paint()
                    paint.color = Color.RED
                    paint.style = Style.STROKE
                    paint.strokeWidth = 2.0f

                    val minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API

                    val mappedRecognitions = LinkedList<Classifier.Recognition>()

                    for (result in results) {
                        val location = result.location
                        if (location != null && result.confidence >= minimumConfidence) {
                            canvas.drawRect(location, paint)

                            cropToFrameTransform!!.mapRect(location)
                            result.location = location
                            mappedRecognitions.add(result)
                        }
                    }

                    tracker!!.trackResults(mappedRecognitions, luminanceCopy!!, currTimestamp)
                    trackingOverlay.postInvalidate()

                    requestRender()
                    computingDetection = false
                })
    }

    override fun onSetDebug(debug: Boolean) {
        detector!!.enableStatLogging(debug)
    }

    companion object {
        private val TF_OD_API_INPUT_SIZE = 300
        private val TF_OD_API_MODEL_FILE = "file:///android_asset/frozen_inference_graph.pb"
        private val TF_OD_API_LABELS_FILE = "file:///android_asset/frozen_inference_labels.txt"

        // Minimum detection confidence to track a detection.
        private val MINIMUM_CONFIDENCE_TF_OD_API = 0.6f

        private val MAINTAIN_ASPECT = false

        private val DESIRED_PREVIEW_SIZE = Size(640, 480)

        private val SAVE_PREVIEW_BITMAP = false
        private val TEXT_SIZE_DIP = 10f
    }
}
