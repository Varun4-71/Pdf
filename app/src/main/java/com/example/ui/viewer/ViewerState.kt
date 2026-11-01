package com.example.ui.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.example.engine.SearchHit

enum class ViewMode(val label: String) {
    CONTINUOUS("Continuous"),
    SINGLE("Single page"),
    TWO_PAGE("Two pages")
}

enum class ColorMode(val label: String) {
    NORMAL("Normal"),
    NIGHT("Night"),
    SEPIA("Sepia"),
    HIGH_CONTRAST("High contrast")
}

/** Everything the reader screen remembers about how the document is being shown. */
@Stable
class ViewerState(startPage: Int) {
    var viewMode by mutableStateOf(ViewMode.CONTINUOUS)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var rotation by mutableIntStateOf(0)
    var currentPage by mutableIntStateOf(startPage)
    var colorMode by mutableStateOf(ColorMode.NORMAL)
    var chromeVisible by mutableStateOf(true)
    var fullscreen by mutableStateOf(false)
    var reflow by mutableStateOf(false)
    var reflowTextSize by mutableFloatStateOf(18f)
    var presentation by mutableStateOf(false)

    /** Set to a 0-based page to make the visible view scroll there; the view clears it. */
    var jumpTo by mutableStateOf<Int?>(null)

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
}

@Stable
class SearchState {
    var active by mutableStateOf(false)
    var query by mutableStateOf("")
    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var current by mutableIntStateOf(-1)
    val hits = mutableStateListOf<SearchHit>()

    fun clearResults() {
        hits.clear()
        current = -1
        running = false
        progress = 0f
    }
}

private fun matrix(vararg values: Float): ColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(*values)))

/** Page tint applied to the picture of the page (not to search highlights drawn above it). */
fun colorFilterFor(mode: ColorMode): ColorFilter? {
    return when (mode) {
        ColorMode.NORMAL -> null
        ColorMode.NIGHT -> matrix(
            -0.88f, 0f, 0f, 0f, 240f,
            0f, -0.88f, 0f, 0f, 240f,
            0f, 0f, -0.88f, 0f, 240f,
            0f, 0f, 0f, 1f, 0f
        )
        ColorMode.SEPIA -> matrix(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        ColorMode.HIGH_CONTRAST -> matrix(
            0.598f, 1.174f, 0.228f, 0f, -140f,
            0.598f, 1.174f, 0.228f, 0f, -140f,
            0.598f, 1.174f, 0.228f, 0f, -140f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}

fun backdropFor(mode: ColorMode): Color {
    return when (mode) {
        ColorMode.NIGHT -> Color(0xFF000000)
        ColorMode.SEPIA -> Color(0xFF2B2418)
        ColorMode.HIGH_CONTRAST -> Color(0xFF1A1A1A)
        ColorMode.NORMAL -> Color(0xFF151B28)
    }
}

/** Background and text colours for the reflow (text-only) reading view. */
fun reflowColors(mode: ColorMode): Pair<Color, Color> {
    return when (mode) {
        ColorMode.NORMAL -> Pair(Color(0xFFF8FAFC), Color(0xFF0F172A))
        ColorMode.NIGHT -> Pair(Color(0xFF000000), Color(0xFFE2E8F0))
        ColorMode.SEPIA -> Pair(Color(0xFFF4ECD8), Color(0xFF3B2F1E))
        ColorMode.HIGH_CONTRAST -> Pair(Color(0xFFFFFFFF), Color(0xFF000000))
    }
}
