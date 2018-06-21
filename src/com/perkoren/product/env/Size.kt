/*
This code is based on https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/env/Size.java

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
import android.text.TextUtils
import java.io.Serializable
import java.util.*

/**
 * Size class independent of a Camera object.
 */
class Size : Comparable<Size>, Serializable {

    val width: Int
    val height: Int

    constructor(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    constructor(bmp: Bitmap) {
        this.width = bmp.width
        this.height = bmp.height
    }

    fun aspectRatio(): Float {
        return width.toFloat() / height.toFloat()
    }

    override fun compareTo(other: Size): Int {
        return width * height - other.width * other.height
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) {
            return false
        }

        if (other !is Size) {
            return false
        }

        val otherSize = other as Size?
        return width == otherSize!!.width && height == otherSize.height
    }

    override fun hashCode(): Int {
        return width * 32713 + height
    }

    override fun toString(): String {
        return dimensionsAsString(width, height)
    }

    companion object {

        // 1.4 went out with this UID so we'll need to maintain it to preserve pending queries when
        // upgrading.
        const val serialVersionUID = 7689808733290872361L

        /**
         * Rotate a size by the given number of degrees.
         * @param size Size to rotate.
         * @param rotation Degrees {0, 90, 180, 270} to rotate the size.
         * @return Rotated size.
         */
        fun getRotatedSize(size: Size, rotation: Int): Size {
            return if (rotation % 180 != 0) {
                // The phone is portrait, therefore the camera is sideways and frame should be rotated.
                Size(size.height, size.width)
            } else size
        }

        fun parseFromString(sizeString: String): Size? {
            var sizeString = sizeString
            if (TextUtils.isEmpty(sizeString)) {
                return null
            }

            sizeString = sizeString.trim { it <= ' ' }

            // The expected format is "<width>x<height>".
            val components = sizeString.split("x".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
            return if (components.size == 2) {
                try {
                    val width = Integer.parseInt(components[0])
                    val height = Integer.parseInt(components[1])
                    Size(width, height)
                } catch (e: NumberFormatException) {
                    null
                }

            } else {
                null
            }
        }

        fun sizeStringToList(sizes: String?): List<Size> {
            val sizeList = ArrayList<Size>()
            if (sizes != null) {
                val pairs = sizes.split(",".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                for (pair in pairs) {
                    val size = Size.parseFromString(pair)
                    if (size != null) {
                        sizeList.add(size)
                    }
                }
            }
            return sizeList
        }

        fun sizeListToString(sizes: List<Size>?): String {
            var sizesString = ""
            if (sizes != null && sizes.size > 0) {
                sizesString = sizes[0].toString()
                for (i in 1 until sizes.size) {
                    sizesString += "," + sizes[i].toString()
                }
            }
            return sizesString
        }

        fun dimensionsAsString(width: Int, height: Int): String {
            return width.toString() + "x" + height
        }
    }
}
