package com.nasller.codeglance.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorKind
import com.intellij.util.Range
import com.nasller.codeglance.panel.GlancePanel
import com.nasller.codeglance.util.MySoftReference
import java.awt.Color
import java.awt.image.BufferedImage

abstract class BaseMinimap(protected val glancePanel: GlancePanel) : Disposable {
	protected val editor
		get() = glancePanel.editor
	protected val config
		get() = glancePanel.config
	protected val scrollState
		get() = glancePanel.scrollState
	protected val rangeList by lazy(LazyThreadSafetyMode.NONE) { mutableListOf<Pair<Int, Range<Int>>>() }
	private val scaleBuffer = FloatArray(4)
	private var imgReference = MySoftReference.create(getBufferedImage(), useSoftReference())

	abstract fun update()

	fun getImage() : BufferedImage? {
		return imgReference.get()
	}

	open fun getMyRenderVisualLine(y: Int): Int {
		var minus = 0
		for (pair in rangeList) {
			if (y in pair.second.from..pair.second.to) {
				return pair.first
			} else if (pair.second.to < y) {
				minus += pair.second.to - pair.second.from
			} else break
		}
		return (y - minus) / config.pixelsPerLine
	}

	open fun getMyRenderLine(lineStart: Int, lineEnd: Int): Pair<Int, Int> {
		var startAdd = 0
		var endAdd = 0
		for (pair in rangeList) {
			if (pair.first in (lineStart + 1) until lineEnd) {
				endAdd += pair.second.to - pair.second.from
			} else if (pair.first < lineStart) {
				val i = pair.second.to - pair.second.from
				startAdd += i
				endAdd += i
			}else if(pair.first == lineStart && lineStart != lineEnd){
				endAdd += pair.second.to - pair.second.from
			} else break
		}
		return startAdd to endAdd
	}

	protected fun getMinimapImage() : BufferedImage? {
		var curImg = imgReference.get()
		if (curImg == null || curImg.height < scrollState.documentHeight || curImg.width < glancePanel.width) {
			curImg?.flush()
			curImg = getBufferedImage()
			imgReference = MySoftReference.create(curImg, useSoftReference())
		}
		return if (editor.isDisposed || editor.document.lineCount <= 0) return null else curImg
	}

	protected fun getHighlightColor(startOffset:Int,endOffset:Int):MutableList<RangeHighlightColor>{
		val list = mutableListOf<RangeHighlightColor>()
		editor.filteredDocumentMarkupModel.processRangeHighlightersOverlappingWith(startOffset,endOffset) {
			val foregroundColor = it.getTextAttributes(editor.colorsScheme)?.foregroundColor
			if (foregroundColor != null) list.add(RangeHighlightColor(it.startOffset,it.endOffset,foregroundColor))
			return@processRangeHighlightersOverlappingWith true
		}
		return list
	}

	protected fun BufferedImage.renderImage(x: Int, y: Int, char: Int, consumer: (() -> Unit)? = null) {
		if (char !in 0..32 && x in 0 until width && 0 <= y && y + config.pixelsPerLine < height) {
			consumer?.invoke()
			if (config.clean) {
				renderClean(x, y, char)
			} else {
				renderAccurate(x, y, char)
			}
		}
	}

	private fun BufferedImage.renderClean(x: Int, y: Int, char: Int) {
		val weight = when (char) {
			in 33..126 -> 0.8f
			else -> 0.4f
		}
		when (config.pixelsPerLine) {
			// Can't show space between lines anymore. This looks rather ugly...
			1 -> setPixel(x, y + 1, weight * 0.6f)
			// Two lines we make the top line a little lighter to give the illusion of space between lines.
			2 -> {
				setPixel(x, y, weight * 0.3f)
				setPixel(x, y + 1, weight * 0.6f)
			}
			// Three lines we make the top nearly empty, and fade the bottom a little too
			3 -> {
				setPixel(x, y, weight * 0.1f)
				setPixel(x, y + 1, weight * 0.6f)
				setPixel(x, y + 2, weight * 0.6f)
			}
			// Empty top line, Nice blend for everything else
			4 -> {
				setPixel(x, y + 1, weight * 0.6f)
				setPixel(x, y + 2, weight * 0.6f)
				setPixel(x, y + 3, weight * 0.6f)
			}
		}
	}

	private fun BufferedImage.renderAccurate(x: Int, y: Int, char: Int) {
		val topWeight = getTopWeight(char)
		val bottomWeight = getBottomWeight(char)
		when (config.pixelsPerLine) {
			// Can't show space between lines anymore. This looks rather ugly...
			1 -> setPixel(x, y + 1, (topWeight + bottomWeight) / 2)
			// Two lines we make the top line a little lighter to give the illusion of space between lines.
			2 -> {
				setPixel(x, y, topWeight * 0.5f)
				setPixel(x, y + 1, bottomWeight)
			}
			// Three lines we make the top nearly empty, and fade the bottom a little too
			3 -> {
				setPixel(x, y, topWeight * 0.3f)
				setPixel(x, y + 1, (topWeight + bottomWeight) / 2)
				setPixel(x, y + 2, bottomWeight * 0.7f)
			}
			// Empty top line, Nice blend for everything else
			4 -> {
				setPixel(x, y + 1, topWeight)
				setPixel(x, y + 2, (topWeight + bottomWeight) / 2)
				setPixel(x, y + 3, bottomWeight)
			}
		}
	}

	/**
	 * mask out the alpha component and set it to the given value.
	 * *
	 * @param alpha     alpha percent from 0-1.
	 */
	private fun BufferedImage.setPixel(x: Int, y: Int, alpha: Float) {
		scaleBuffer[3] = alpha * 0xFF
		raster.setPixel(x, y, scaleBuffer)
	}

	protected fun Color.setColorRgba() {
		scaleBuffer[0] = red.toFloat()
		scaleBuffer[1] = green.toFloat()
		scaleBuffer[2] = blue.toFloat()
	}

	private fun useSoftReference() = EditorKind.MAIN_EDITOR != editor.editorKind

	override fun dispose() {
		imgReference.clear()
	}

	@Suppress("UndesirableClassUsage")
	private fun getBufferedImage() = BufferedImage(glancePanel.getConfigSize().width, glancePanel.scrollState.documentHeight + (100 * config.pixelsPerLine), BufferedImage.TYPE_4BYTE_ABGR)

	protected data class RangeHighlightColor(val startOffset: Int,val endOffset: Int,val foregroundColor: Color)

	companion object{
		fun EditorKind.getMinimap(glancePanel: GlancePanel): BaseMinimap = when(this){
			EditorKind.CONSOLE -> ConsoleMinimap(glancePanel)
			else -> MainMinimap(glancePanel)
		}
	}
}