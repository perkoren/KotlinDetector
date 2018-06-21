/*
This code is based on: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/examples/android/src/org/tensorflow/demo/env/BorderedText.java

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

import android.graphics.*
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import java.util.*

/**
 * A class that encapsulates the tedious bits of rendering legible, bordered text onto a canvas.
 */
class BorderedText
/**
 * Create a bordered text object with the specified interior and exterior colors, text size and
 * alignment.
 *
 * @param interiorColor the interior text color
 * @param exteriorColor the exterior text color
 * @param textSize text size in pixels
 */
(interiorColor: Int, exteriorColor: Int, val textSize: Float) {
    private val interiorPaint: Paint
    private val exteriorPaint: Paint

    /**
     * Creates a left-aligned bordered text object with a white interior, and a black exterior with
     * the specified text size.
     *
     * @param textSize text size in pixels
     */
    constructor(textSize: Float) : this(Color.WHITE, Color.BLACK, textSize) {}

    init {
        interiorPaint = Paint()
        interiorPaint.textSize = textSize
        interiorPaint.color = interiorColor
        interiorPaint.style = Style.FILL
        interiorPaint.isAntiAlias = false
        interiorPaint.alpha = 255

        exteriorPaint = Paint()
        exteriorPaint.textSize = textSize
        exteriorPaint.color = exteriorColor
        exteriorPaint.style = Style.FILL_AND_STROKE
        exteriorPaint.strokeWidth = textSize / 8
        exteriorPaint.isAntiAlias = false
        exteriorPaint.alpha = 255
    }

    fun setTypeface(typeface: Typeface) {
        interiorPaint.typeface = typeface
        exteriorPaint.typeface = typeface
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String) {
        canvas.drawText(text, posX, posY, exteriorPaint)
        canvas.drawText(text, posX, posY, interiorPaint)
    }

    fun drawLines(canvas: Canvas, posX: Float, posY: Float, lines: Vector<String>) {
        var lineNum = 0
        for (line in lines) {
            drawText(canvas, posX, posY - textSize * (lines.size - lineNum - 1), line)
            ++lineNum
        }
    }

    fun setInteriorColor(color: Int) {
        interiorPaint.color = color
    }

    fun setExteriorColor(color: Int) {
        exteriorPaint.color = color
    }

    fun setAlpha(alpha: Int) {
        interiorPaint.alpha = alpha
        exteriorPaint.alpha = alpha
    }

    fun getTextBounds(
            line: String, index: Int, count: Int, lineBounds: Rect) {
        interiorPaint.getTextBounds(line, index, count, lineBounds)
    }

    fun setTextAlign(align: Align) {
        interiorPaint.textAlign = align
        exteriorPaint.textAlign = align
    }
}
