package kr.jjw.graft.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The tab icons and the status icons, drawn from Material path data.
 *
 * Declared here instead of pulling in `material-icons-extended`: a handful of icons is not worth an
 * artifact, and `Icon` tints them, so the fill colour only needs to exist.
 */
object GraftIcons {
    val Link: ImageVector by lazy { vector("Link", LINK) }
    val Files: ImageVector by lazy { vector("Files", SEND) }
    val Alerts: ImageVector by lazy { vector("Alerts", BELL) }
    val Check: ImageVector by lazy { vector("Check", CHECK) }
    val Sync: ImageVector by lazy { vector("Sync", SYNC) }
    val Warning: ImageVector by lazy { vector("Warning", WARNING) }
    val ArrowBack: ImageVector by lazy { vector("ArrowBack", ARROW_BACK) }
    val ChevronRight: ImageVector by lazy { vector("ChevronRight", CHEVRON_RIGHT) }
    val Settings: ImageVector by lazy { vector("Settings", SETTINGS) }
    val QrCode: ImageVector by lazy { vector("QrCode", QR_CODE) }
    val Clipboard: ImageVector by lazy { vector("Clipboard", CLIPBOARD) }
    val Power: ImageVector by lazy { vector("Power", POWER) }
    val Delete: ImageVector by lazy { vector("Delete", DELETE) }
    val Battery: ImageVector by lazy { vector("Battery", BATTERY) }
    val Info: ImageVector by lazy { vector("Info", INFO) }
    val Add: ImageVector by lazy { vector("Add", ADD) }
    val Close: ImageVector by lazy { vector("Close", CLOSE) }
    val Upload: ImageVector by lazy { vector("Upload", UPLOAD) }
    val Copy: ImageVector by lazy { vector("Copy", COPY) }
    val OpenInNew: ImageVector by lazy { vector("OpenInNew", OPEN_IN_NEW) }

    /** The icon that reads as the given status, so no screen has to pick one itself. */
    fun forTone(tone: StatusTone): ImageVector = when (tone) {
        StatusTone.Positive -> Check
        StatusTone.Neutral -> Link
        StatusTone.Warning -> Sync
        StatusTone.Negative -> Warning
    }

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

    private const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

    private const val SYNC =
        "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46" +
            "C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6" +
            " 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3" +
            "l4,-4 -4,-4v3z"

    private const val WARNING = "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z"

    private const val BELL =
        "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32" +
            "V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5" +
            "l-2,2v1h16v-1l-2,-2z"

    private const val ARROW_BACK =
        "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

    private const val CHEVRON_RIGHT =
        "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z"

    private const val SETTINGS =
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
            "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22" +
            "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41" +
            " -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92" +
            " 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34" +
            " 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58" +
            "c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96" +
            "c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84" +
            "c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96" +
            "c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z" +
            "M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"

    private const val QR_CODE =
        "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9z" +
            "M19,19h2v2h-2V19zM13,13h2v2h-2V13zM15,15h2v2h-2V15zM13,17h2v2h-2V17zM15,19h2v2h-2V19z" +
            "M17,17h2v2h-2V17zM17,13h2v2h-2V13zM19,15h2v2h-2V15z"

    private const val CLIPBOARD =
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v16" +
            "c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1" +
            "s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z"

    private const val POWER =
        "M13,3h-2v10h2V3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7" +
            "s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12" +
            "c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z"

    private const val DELETE =
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"

    private const val BATTERY =
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33" +
            "c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4zM13,18h-2v-2h2v2zM13,14h-2V9h2v5z"

    private const val INFO =
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6z" +
            "M13,9h-2V7h2v2z"

    private const val ADD = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"

    private const val CLOSE =
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19" +
            " 19,17.59 13.41,12z"

    private const val UPLOAD = "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z"

    private const val COPY =
        "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11" +
            "c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z"

    private const val OPEN_IN_NEW =
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
            "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z"
}
