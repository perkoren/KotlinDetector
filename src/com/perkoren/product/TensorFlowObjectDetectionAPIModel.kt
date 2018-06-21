/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/TensorFlowObjectDetectionAPIModel.java

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

package com.perkoren.product

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Trace
import org.tensorflow.contrib.android.TensorFlowInferenceInterface
import java.io.IOException
import java.util.*

/**
 * Wrapper for frozen detection models trained using the Tensorflow Object Detection API:
 * github.com/tensorflow/models/tree/master/research/object_detection
 */
class TensorFlowObjectDetectionAPIModel private constructor() : Classifier {

    // Config values.
    private var inputName: String? = null
    private var inputSize: Int = 0

    // Pre-allocated buffers.
    private val labels = Vector<String>()
    private var intValues: IntArray? = null
    lateinit private var byteValues: ByteArray
    private var outputLocations: FloatArray? = null
    private var outputScores: FloatArray? = null
    private var outputClasses: FloatArray? = null
    private var outputNumDetections: FloatArray? = null
    private var outputNames: Array<String>? = null

    private var logStats = false

    private var inferenceInterface: TensorFlowInferenceInterface? = null

    override val statString: String
        get() = inferenceInterface!!.statString

    override fun recognizeImage(bitmap: Bitmap): List<Classifier.Recognition> {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage")

        Trace.beginSection("preprocessBitmap")
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in intValues!!.indices) {
            byteValues[i * 3 + 2] = (intValues!![i] and 0xFF).toByte()
            byteValues[i * 3 + 1] = (intValues!![i] shr 8 and 0xFF).toByte()
            byteValues[i * 3 + 0] = (intValues!![i] shr 16 and 0xFF).toByte()
        }
        Trace.endSection() // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed")
        inferenceInterface!!.feed(inputName, byteValues, 1L, inputSize.toLong(), inputSize.toLong(), 3)
        Trace.endSection()

        // Run the inference call.
        Trace.beginSection("run")
        inferenceInterface!!.run(outputNames, logStats)
        Trace.endSection()

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch")
        outputLocations = FloatArray(MAX_RESULTS * 4)
        outputScores = FloatArray(MAX_RESULTS)
        outputClasses = FloatArray(MAX_RESULTS)
        outputNumDetections = FloatArray(1)
        inferenceInterface!!.fetch(outputNames!![0], outputLocations)
        inferenceInterface!!.fetch(outputNames!![1], outputScores)
        inferenceInterface!!.fetch(outputNames!![2], outputClasses)
        inferenceInterface!!.fetch(outputNames!![3], outputNumDetections)
        Trace.endSection()

        // Find the best detections.
        val pq = PriorityQueue(
                1,
                Comparator<Classifier.Recognition> { lhs, rhs ->
                    // Intentionally reversed to put high confidence at the head of the queue.
                    java.lang.Float.compare(rhs.confidence!!, lhs.confidence!!)
                })

        // Scale them back to the input size.
        for (i in outputScores!!.indices) {
            val detection = RectF(
                    outputLocations!![4 * i + 1] * inputSize,
                    outputLocations!![4 * i] * inputSize,
                    outputLocations!![4 * i + 3] * inputSize,
                    outputLocations!![4 * i + 2] * inputSize)
            pq.add(
                    Classifier.Recognition("" + i, labels[outputClasses!![i].toInt()], outputScores!![i], detection))
        }

        val recognitions = ArrayList<Classifier.Recognition>()
        for (i in 0 until Math.min(pq.size, MAX_RESULTS)) {
            recognitions.add(pq.poll())
        }
        Trace.endSection() // "recognizeImage"
        return recognitions
    }

    override fun enableStatLogging(logStats: Boolean) {
        this.logStats = logStats
    }

    override fun close() {
        inferenceInterface!!.close()
    }

    companion object {
        // Only return this many results.
        private val MAX_RESULTS = 100


        @Throws(IOException::class)
        private fun loadFile(assetManager: AssetManager, fileName: String): List<String> {
            return assetManager.open(fileName).bufferedReader().useLines { it.toList() }
        }

        /**
         * Initializes a native TensorFlow session for classifying images.
         *
         * @param assetManager The asset manager to be used to load assets.
         * @param modelFilename The filepath of the model GraphDef protocol buffer.
         * @param labelFilename The filepath of label file for classes.
         */
        @Throws(IOException::class)
        fun create(
                assetManager: AssetManager,
                modelFilename: String,
                labelFilename: String,
                inputSize: Int): Classifier {
            val d = TensorFlowObjectDetectionAPIModel()

            val actualFilename = labelFilename.split("file:///android_asset/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
            d.labels.addAll(loadFile(assetManager,actualFilename))

            d.inferenceInterface = TensorFlowInferenceInterface(assetManager, modelFilename)

            d.inferenceInterface!!.graph()

            d.inputName = "image_tensor"
            d.inputSize = inputSize
            d.outputNames = arrayOf("detection_boxes", "detection_scores", "detection_classes", "num_detections")
            d.intValues = IntArray(d.inputSize * d.inputSize)
            d.byteValues = ByteArray(d.inputSize * d.inputSize * 3)
            d.outputScores = FloatArray(MAX_RESULTS)
            d.outputLocations = FloatArray(MAX_RESULTS * 4)
            d.outputClasses = FloatArray(MAX_RESULTS)
            d.outputNumDetections = FloatArray(1)
            return d
        }
    }
}
