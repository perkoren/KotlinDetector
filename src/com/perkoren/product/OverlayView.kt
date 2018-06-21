/*
 * This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/OverlayView.java
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

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

/**
 * A simple View providing a render callback to other classes.
 */
class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val callbacks = LinkedList<DrawCallback>()

    /**
     * Interface defining the callback for client classes.
     */
    interface DrawCallback {
        fun drawCallback(canvas: Canvas)
    }

    fun addCallback(callback: DrawCallback) {
        callbacks.add(callback)
    }

    @Synchronized
    override fun draw(canvas: Canvas) {
        for (callback in callbacks) {
            callback.drawCallback(canvas)
        }
    }
}
