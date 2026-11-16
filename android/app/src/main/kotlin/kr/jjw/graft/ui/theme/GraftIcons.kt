package kr.jjw.graft.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The three tab icons, drawn from Material path data.
 *
 * Declared here instead of pulling in `material-icons-extended`: three icons are not worth an
 * artifact, and `Icon` tints them, so the fill colour only needs to exist.
 */
object GraftIcons {
    val Link: ImageVector by lazy { vector("Link", LINK) }
    val Files: ImageVector by lazy { vector("Files", SEND) }
    val Alerts: ImageVector by lazy { vector("Alerts", BELL) }

    private fun vector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()

    private const val LINK =
        "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7" +
            "c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1" +
            "s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z"

    private const val SEND = "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"

    private const val BELL =
        "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32" +
            "V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5" +
            "l-2,2v1h16v-1l-2,-2z"
}
