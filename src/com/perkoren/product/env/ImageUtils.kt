/*
This code is based on https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/env/ImageUtils.java

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

package com.perkoren.product.env

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class for manipulating images.
 */
object ImageUtils {
    const val TAG = "ObjectTracker"
    // This value is 2 ^ 18 - 1, and is used to clamp the RGB values before their ranges
    // are normalized to eight bits.
    internal val kMaxChannelValue = 262143

    // Always prefer the native implementation if available.
    private var useNativeConversion = true

    init {
        try {
            System.loadLibrary("tensorflow_demo")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG,"Native library not found, native RGB -> YUV conversion may be unavailable.")
        }

    }

    /**
     * Utility method to compute the allocated size in bytes of a YUV420SP image
     * of the given dimensions.
     */
    fun getYUVByteSize(width: Int, height: Int): Int {
        // The luminance plane requires 1 byte per pixel.
        val ySize = width * height

        // The UV plane works on 2x2 blocks, so dimensions with odd size must be rounded up.
        // Each 2x2 block takes 2 bytes to encode, one each for U and V.
        val uvSize = (width + 1) / 2 * ((height + 1) / 2) * 2

        return ySize + uvSize
    }

    /**
     * Saves a Bitmap object to disk for analysis.
     *
     * @param bitmap The bitmap to save.
     * @param filename The location to save the bitmap to.
     */
    @JvmOverloads
    fun saveBitmap(bitmap: Bitmap, filename: String = "preview.png") {
        val root = Environment.getExternalStorageDirectory().absolutePath + File.separator + "tensorflow"
        Log.i(TAG,"Saving ${bitmap.width}x${bitmap.height} bitmap to $root.")
        val myDir = File(root)

        if (!myDir.mkdirs()) {
            Log.i(TAG,"Make dir failed")
        }

        val file = File(myDir, filename)
        if (file.exists()) {
            file.delete()
        }
        try {
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 99, out)
            out.flush()
            out.close()
        } catch (e: Exception) {
            Log.e(TAG, "Exception!")
        }

    }

    fun convertYUV420SPToARGB8888(
            input: ByteArray,
            width: Int,
            height: Int,
            output: IntArray) {
        if (useNativeConversion) {
            try {
                ImageUtils.convertYUV420SPToARGB8888(input, output, width, height, false)
                return
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG,"Native YUV420SP -> RGB implementation not found, falling back to Java implementation")
                useNativeConversion = false
            }

        }

        // Java implementation of YUV420SP to ARGB8888 converting
        val frameSize = width * height
        var j = 0
        var yp = 0
        while (j < height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0

            var i = 0
            while (i < width) {
                val y = 0xff and input[yp].toInt()
                if (i and 1 == 0) {
                    v = 0xff and input[uvp++].toInt()
                    u = 0xff and input[uvp++].toInt()
                }

                output[yp] = YUV2RGB(y, u, v)
                i++
                yp++
            }
            j++
        }
    }

    private fun YUV2RGB(y: Int, u: Int, v: Int): Int {
        var y = y
        var u = u
        var v = v
        // Adjust and check YUV values
        y = if (y - 16 < 0) 0 else y - 16
        u -= 128
        v -= 128

        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 2.018 * nU);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 1.596 * nV);
        val y1192 = 1192 * y
        var r = y1192 + 1634 * v
        var g = y1192 - 833 * v - 400 * u
        var b = y1192 + 2066 * u

        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        r = if (r > kMaxChannelValue) kMaxChannelValue else if (r < 0) 0 else r
        g = if (g > kMaxChannelValue) kMaxChannelValue else if (g < 0) 0 else g
        b = if (b > kMaxChannelValue) kMaxChannelValue else if (b < 0) 0 else b

        return -0x1000000 or (r shl 6 and 0xff0000) or (g shr 2 and 0xff00) or (b shr 10 and 0xff)
    }


    fun convertYUV420ToARGB8888(
            yData: ByteArray,
            uData: ByteArray,
            vData: ByteArray,
            width: Int,
            height: Int,
            yRowStride: Int,
            uvRowStride: Int,
            uvPixelStride: Int,
            out: IntArray) {
        if (useNativeConversion) {
            try {
                convertYUV420ToARGB8888(
                        yData, uData, vData, out, width, height, yRowStride, uvRowStride, uvPixelStride, false)
                return
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG,
                        "Native YUV420 -> RGB implementation not found, falling back to Java implementation")
                useNativeConversion = false
            }

        }

        var yp = 0
        for (j in 0 until height) {
            val pY = yRowStride * j
            val pUV = uvRowStride * (j shr 1)

            for (i in 0 until width) {
                val uv_offset = pUV + (i shr 1) * uvPixelStride

                out[yp++] = YUV2RGB(
                        0xff and yData[pY + i].toInt(),
                        0xff and uData[uv_offset].toInt(),
                        0xff and vData[uv_offset].toInt())
            }
        }
    }


    /**
     * Converts YUV420 semi-planar data to ARGB 8888 data using the supplied width and height. The
     * input and output must already be allocated and non-null. For efficiency, no error checking is
     * performed.
     *
     * @param input The array of YUV 4:2:0 input data.
     * @param output A pre-allocated array for the ARGB 8:8:8:8 output data.
     * @param width The width of the input image.
     * @param height The height of the input image.
     * @param halfSize If true, downsample to 50% in each dimension, otherwise not.
     */
    private external fun convertYUV420SPToARGB8888(
            input: ByteArray, output: IntArray, width: Int, height: Int, halfSize: Boolean)

    /**
     * Converts YUV420 semi-planar data to ARGB 8888 data using the supplied width
     * and height. The input and output must already be allocated and non-null.
     * For efficiency, no error checking is performed.
     *
     * @param y
     * @param u
     * @param v
     * @param uvPixelStride
     * @param width The width of the input image.
     * @param height The height of the input image.
     * @param halfSize If true, downsample to 50% in each dimension, otherwise not.
     * @param output A pre-allocated array for the ARGB 8:8:8:8 output data.
     */
    private external fun convertYUV420ToARGB8888(
            y: ByteArray,
            u: ByteArray,
            v: ByteArray,
            output: IntArray,
            width: Int,
            height: Int,
            yRowStride: Int,
            uvRowStride: Int,
            uvPixelStride: Int,
            halfSize: Boolean)

    /**
     * Converts YUV420 semi-planar data to RGB 565 data using the supplied width
     * and height. The input and output must already be allocated and non-null.
     * For efficiency, no error checking is performed.
     *
     * @param input The array of YUV 4:2:0 input data.
     * @param output A pre-allocated array for the RGB 5:6:5 output data.
     * @param width The width of the input image.
     * @param height The height of the input image.
     */
    private external fun convertYUV420SPToRGB565(
            input: ByteArray, output: ByteArray, width: Int, height: Int)

    /**
     * Converts 32-bit ARGB8888 image data to YUV420SP data.  This is useful, for
     * instance, in creating data to feed the classes that rely on raw camera
     * preview frames.
     *
     * @param input An array of input pixels in ARGB8888 format.
     * @param output A pre-allocated array for the YUV420SP output data.
     * @param width The width of the input image.
     * @param height The height of the input image.
     */
    private external fun convertARGB8888ToYUV420SP(
            input: IntArray, output: ByteArray, width: Int, height: Int)

    /**
     * Converts 16-bit RGB565 image data to YUV420SP data.  This is useful, for
     * instance, in creating data to feed the classes that rely on raw camera
     * preview frames.
     *
     * @param input An array of input pixels in RGB565 format.
     * @param output A pre-allocated array for the YUV420SP output data.
     * @param width The width of the input image.
     * @param height The height of the input image.
     */
    private external fun convertRGB565ToYUV420SP(
            input: ByteArray, output: ByteArray, width: Int, height: Int)

    /**
     * Returns a transformation matrix from one reference frame into another.
     * Handles cropping (if maintaining aspect ratio is desired) and rotation.
     *
     * @param srcWidth Width of source frame.
     * @param srcHeight Height of source frame.
     * @param dstWidth Width of destination frame.
     * @param dstHeight Height of destination frame.
     * @param applyRotation Amount of rotation to apply from one frame to another.
     * Must be a multiple of 90.
     * @param maintainAspectRatio If true, will ensure that scaling in x and y remains constant,
     * cropping the image if necessary.
     * @return The transformation fulfilling the desired requirements.
     */
    fun getTransformationMatrix(
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int,
            dstHeight: Int,
            applyRotation: Int,
            maintainAspectRatio: Boolean): Matrix {
        val matrix = Matrix()

        if (applyRotation != 0) {
            if (applyRotation % 90 != 0) {
                Log.w(TAG,"Rotation of $applyRotation % 90 != 0")
            }

            // Translate so center of image is at origin.
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f)

            // Rotate around origin.
            matrix.postRotate(applyRotation.toFloat())
        }

        // Account for the already applied rotation, if any, and then determine how
        // much scaling is needed for each axis.
        val transpose = (Math.abs(applyRotation) + 90) % 180 == 0

        val inWidth = if (transpose) srcHeight else srcWidth
        val inHeight = if (transpose) srcWidth else srcHeight

        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            val scaleFactorX = dstWidth / inWidth.toFloat()
            val scaleFactorY = dstHeight / inHeight.toFloat()

            if (maintainAspectRatio) {
                // Scale by minimum factor so that dst is filled completely while
                // maintaining the aspect ratio. Some image may fall off the edge.
                val scaleFactor = Math.max(scaleFactorX, scaleFactorY)
                matrix.postScale(scaleFactor, scaleFactor)
            } else {
                // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY)
            }
        }

        if (applyRotation != 0) {
            // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f)
        }

        return matrix
    }
}
/**
 * Saves a Bitmap object to disk for analysis.
 *
 * @param bitmap The bitmap to save.
 */
