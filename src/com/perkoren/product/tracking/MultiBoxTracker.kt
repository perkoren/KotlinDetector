/*
This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/tracking/MultiBoxTracker.java

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.perkoren.product.tracking

import android.content.Context
import android.graphics.*
import android.graphics.Paint.*
import android.text.TextUtils
import android.util.Log
import android.util.Pair
import android.util.TypedValue
import android.widget.Toast
import com.perkoren.product.Classifier.Recognition
import com.perkoren.product.env.BorderedText
import com.perkoren.product.env.ImageUtils
import java.util.*

/**
 * A tracker wrapping ObjectTracker that also handles non-max suppression and matching existing
 * objects to new detections.
 */
class MultiBoxTracker(private val context: Context) {
    private val availableColors = LinkedList<Int>()

    var objectTracker: ObjectTracker? = null

    internal val screenRects: MutableList<Pair<Float, RectF>> = LinkedList()

    private val trackedObjects = LinkedList<TrackedRecognition>()

    private val boxPaint = Paint()

    private val textSizePx: Float
    private val borderedText: BorderedText

    lateinit private var frameToCanvasMatrix: Matrix

    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    private var sensorOrientation: Int = 0

    private var initialized = false

    private class TrackedRecognition {
        internal var trackedObject: ObjectTracker.TrackedObject? = null
        internal var location: RectF? = null
        internal var detectionConfidence: Float = 0.toFloat()
        internal var color: Int = 0
        internal var title: String? = null
    }

    init {
        for (color in COLORS) {
            availableColors.add(color)
        }

        boxPaint.color = Color.RED
        boxPaint.style = Style.STROKE
        boxPaint.strokeWidth = 12.0f
        boxPaint.strokeCap = Cap.ROUND
        boxPaint.strokeJoin = Join.ROUND
        boxPaint.strokeMiter = 100f

        textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
    }

    @Synchronized
    fun drawDebug(canvas: Canvas) {
        val textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.textSize = 60.0f

        val boxPaint = Paint()
        boxPaint.color = Color.RED
        boxPaint.alpha = 200
        boxPaint.style = Style.STROKE

        for (detection in screenRects) {
            val rect = detection.second
            canvas.drawRect(rect, boxPaint)
            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint)
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first)
        }

        if (objectTracker == null) {
            return
        }

        // Draw correlations.
        for (recognition in trackedObjects) {
            val trackedObject = recognition.trackedObject

            val trackedPos = trackedObject!!.trackedPositionInPreviewFrame

            if (frameToCanvasMatrix!!.mapRect(trackedPos)) {
                val labelString = String.format("%.2f", trackedObject.currentCorrelation)
                borderedText.drawText(canvas, trackedPos!!.right, trackedPos.bottom, labelString)
            }
        }

        val matrix = frameToCanvasMatrix
        objectTracker!!.drawDebug(canvas, matrix)
    }

    @Synchronized
    fun trackResults(
            results: List<Recognition>, frame: ByteArray, timestamp: Long) {
        Log.i(ImageUtils.TAG,"Processing ${results.size} results from $timestamp")
        processResults(timestamp, results, frame)
    }

    @Synchronized
    fun draw(canvas: Canvas) {
        val rotated = sensorOrientation % 180 == 90
        val multiplier = Math.min(canvas.height / (if (rotated) frameWidth else frameHeight).toFloat(),
                canvas.width / (if (rotated) frameHeight else frameWidth).toFloat())
        frameToCanvasMatrix = ImageUtils.getTransformationMatrix(
                frameWidth,
                frameHeight,
                (multiplier * if (rotated) frameHeight else frameWidth).toInt(),
                (multiplier * if (rotated) frameWidth else frameHeight).toInt(),
                sensorOrientation,
                false)
        for (recognition in trackedObjects) {
            val trackedPos = if (objectTracker != null)
                recognition.trackedObject!!.trackedPositionInPreviewFrame
            else
                RectF(recognition.location)

            frameToCanvasMatrix!!.mapRect(trackedPos)
            boxPaint.color = recognition.color

            val cornerSize = Math.min(trackedPos!!.width(), trackedPos.height()) / 8.0f
            canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint)

            val labelString = if (!TextUtils.isEmpty(recognition.title))
                String.format("%s %.2f", recognition.title, recognition.detectionConfidence)
            else
                String.format("%.2f", recognition.detectionConfidence)
            borderedText.drawText(canvas, trackedPos.left + cornerSize, trackedPos.bottom, labelString)
        }
    }

    @Synchronized
    fun onFrame(
            w: Int,
            h: Int,
            rowStride: Int,
            sensorOrientation: Int,
            frame: ByteArray?,
            timestamp: Long) {
        if (objectTracker == null && !initialized) {
            ObjectTracker.clearInstance()

            Log.i(ImageUtils.TAG,"Initializing ObjectTracker: $w x $h")
            objectTracker = ObjectTracker.getInstance(w, h, rowStride, true)
            frameWidth = w
            frameHeight = h
            this.sensorOrientation = sensorOrientation
            initialized = true

            if (objectTracker == null) {
                val message = "Object tracking support not found. " + "See tensorflow/examples/android/README.md for details."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                Log.e(ImageUtils.TAG,message)
            }
        }

        if (objectTracker == null) {
            return
        }

        objectTracker!!.nextFrame(frame, null, timestamp, null, true)

        // Clean up any objects not worth tracking any more.
        val copyList = LinkedList(trackedObjects)
        for (recognition in copyList) {
            val trackedObject = recognition.trackedObject
            val correlation = trackedObject!!.currentCorrelation
            if (correlation < MIN_CORRELATION) {
                Log.v(ImageUtils.TAG,"Removing tracked object $trackedObject because NCC is $correlation")
                trackedObject.stopTracking()
                trackedObjects.remove(recognition)

                availableColors.add(recognition.color)
            }
        }
    }

    private fun processResults(
            timestamp: Long, results: List<Recognition>, originalFrame: ByteArray) {
        val rectsToTrack = LinkedList<Pair<Float, Recognition>>()

        screenRects.clear()
        val rgbFrameToScreen = Matrix(frameToCanvasMatrix)

        for (result in results) {
            if (result.location == null) {
                continue
            }
            val detectionFrameRect = RectF(result.location)

            val detectionScreenRect = RectF()
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect)

            Log.v(ImageUtils.TAG,
                    "Result! Frame: " + result.location + " mapped to screen:" + detectionScreenRect)

            screenRects.add(Pair(result.confidence, detectionScreenRect))

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                Log.w(ImageUtils.TAG,"Degenerate rectangle! $detectionFrameRect")
                continue
            }

            rectsToTrack.add(Pair(result.confidence, result))
        }

        if (rectsToTrack.isEmpty()) {
            Log.v(ImageUtils.TAG,"Nothing to track, aborting.")
            return
        }

        if (objectTracker == null) {
            trackedObjects.clear()
            for (potential in rectsToTrack) {
                val trackedRecognition = TrackedRecognition()
                trackedRecognition.detectionConfidence = potential.first
                trackedRecognition.location = RectF(potential.second.location)
                trackedRecognition.trackedObject = null
                trackedRecognition.title = potential.second.title
                trackedRecognition.color = COLORS[trackedObjects.size]
                trackedObjects.add(trackedRecognition)

                if (trackedObjects.size >= COLORS.size) {
                    break
                }
            }
            return
        }

        Log.i(ImageUtils.TAG,"${rectsToTrack.size} rects to track")
        for (potential in rectsToTrack) {
            handleDetection(originalFrame, timestamp, potential)
        }
    }

    private fun handleDetection(
            frameCopy: ByteArray, timestamp: Long, potential: Pair<Float, Recognition>) {
        val potentialObject = objectTracker!!.trackObject(potential.second.location, timestamp, frameCopy)

        val potentialCorrelation = potentialObject.currentCorrelation
        Log.v(ImageUtils.TAG,
                "Tracked object went from ${potential.second} to ${potentialObject.trackedPositionInPreviewFrame} with correlation ${potentialCorrelation}")

        if (potentialCorrelation < MARGINAL_CORRELATION) {
            Log.v(ImageUtils.TAG,"Correlation too low to begin tracking $potentialObject.")
            potentialObject.stopTracking()
            return
        }

        val removeList = LinkedList<TrackedRecognition>()

        var maxIntersect = 0.0f

        // This is the current tracked object whose color we will take. If left null we'll take the
        // first one from the color queue.
        var recogToReplace: TrackedRecognition? = null

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (trackedRecognition in trackedObjects) {
            val a = trackedRecognition.trackedObject!!.trackedPositionInPreviewFrame
            val b = potentialObject.trackedPositionInPreviewFrame
            val intersection = RectF()
            val intersects = intersection.setIntersect(a, b)

            val intersectArea = intersection.width() * intersection.height()
            val totalArea = a!!.width() * a.height() + b!!.width() * b.height() - intersectArea
            val intersectOverUnion = intersectArea / totalArea

            // If there is an intersection with this currently tracked box above the maximum overlap
            // percentage allowed, either the new recognition needs to be dismissed or the old
            // recognition needs to be removed and possibly replaced with the new one.
            if (intersects && intersectOverUnion > MAX_OVERLAP) {
                if (potential.first < trackedRecognition.detectionConfidence && trackedRecognition.trackedObject!!.currentCorrelation > MARGINAL_CORRELATION) {
                    // If track for the existing object is still going strong and the detection score was
                    // good, reject this new object.
                    potentialObject.stopTracking()
                    return
                } else {
                    removeList.add(trackedRecognition)

                    // Let the previously tracked object with max intersection amount donate its color to
                    // the new object.
                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion
                        recogToReplace = trackedRecognition
                    }
                }
            }
        }

        // If we're already tracking the max object and no intersections were found to bump off,
        // pick the worst current tracked object to remove, if it's also worse than this candidate
        // object.
        if (availableColors.isEmpty() && removeList.isEmpty()) {
            for (candidate in trackedObjects) {
                if (candidate.detectionConfidence < potential.first) {
                    if (recogToReplace == null || candidate.detectionConfidence < recogToReplace.detectionConfidence) {
                        // Save it so that we use this color for the new object.
                        recogToReplace = candidate
                    }
                }
            }
            if (recogToReplace != null) {
                Log.v(ImageUtils.TAG,"Found non-intersecting object to remove.")
                removeList.add(recogToReplace)
            } else {
                Log.v(ImageUtils.TAG,"No non-intersecting object found to remove")
            }
        }

        // Remove everything that got intersected.
        for (trackedRecognition in removeList) {
            Log.v(ImageUtils.TAG,
                    "Removing tracked object ${trackedRecognition.trackedObject} with detection confidence ${trackedRecognition.detectionConfidence}, correlation ${trackedRecognition.trackedObject!!.currentCorrelation}")
            trackedRecognition.trackedObject!!.stopTracking()
            trackedObjects.remove(trackedRecognition)
            if (trackedRecognition !== recogToReplace) {
                availableColors.add(trackedRecognition.color)
            }
        }

        if (recogToReplace == null && availableColors.isEmpty()) {
            Log.e(ImageUtils.TAG,"No room to track this object, aborting.")
            potentialObject.stopTracking()
            return
        }

        // Finally safe to say we can track this object.
        Log.v(ImageUtils.TAG,
                "Tracking object $potentialObject (${potential.second.title}) with detection confidence ${potential.first} at position ${potential.second.location}")
        val trackedRecognition = TrackedRecognition()
        trackedRecognition.detectionConfidence = potential.first
        trackedRecognition.trackedObject = potentialObject
        trackedRecognition.title = potential.second.title

        // Use the color from a replaced object before taking one from the color queue.
        trackedRecognition.color = if (recogToReplace != null) recogToReplace.color else availableColors.poll()
        trackedObjects.add(trackedRecognition)
    }

    companion object {

        private val TEXT_SIZE_DIP = 18f

        // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
        // the lower scored box (new or old) will be removed.
        private val MAX_OVERLAP = 0.2f

        private val MIN_SIZE = 16.0f

        // Allow replacement of the tracked box with new results if
        // correlation has dropped below this level.
        private val MARGINAL_CORRELATION = 0.75f

        // Consider object to be lost if correlation falls below this threshold.
        private val MIN_CORRELATION = 0.3f

        private val COLORS = intArrayOf(Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.parseColor("#55FF55"), Color.parseColor("#FFA500"), Color.parseColor("#FF8888"), Color.parseColor("#AAAAFF"), Color.parseColor("#FFFFAA"), Color.parseColor("#55AAAA"), Color.parseColor("#AA33AA"), Color.parseColor("#0D0068"))
    }
}
