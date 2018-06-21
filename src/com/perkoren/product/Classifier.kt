/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/Classifier.java
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

import android.graphics.Bitmap
import android.graphics.RectF

interface Classifier {

    val statString: String


    class Recognition(

            val id: String,
            val title: String,
            val confidence: Float,
            var location: RectF) {


        override fun toString(): String {
            var resultString = "[$id] "
            resultString += "$title "
            resultString += String.format("(%.1f%%) ", confidence * 100.0f)

            resultString += location!!.toString() + " "

            return resultString.trim { it <= ' ' }
        }
    }

    fun recognizeImage(bitmap: Bitmap): List<Recognition>

    fun enableStatLogging(debug: Boolean)

    fun close()
}
