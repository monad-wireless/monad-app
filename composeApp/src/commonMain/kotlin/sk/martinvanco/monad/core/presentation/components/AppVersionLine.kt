package sk.martinvanco.monad.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.config.BuildIdentity

/**
 * Which build of the app this is.
 *
 * Two lines, and the second is the one that matters when something is wrong. `1.2.0 (5)` identifies
 * a release; `1.2.0+5.g6362151c.dirtyca1997ef` identifies the **binary**, including which commit it
 * was made from and whether the tree was dirty when it was built. The second is what the session
 * sidecar records as `build_id`, so a bug report that quotes it can be joined to the recordings
 * that build produced.
 *
 * The build id is behind a tap rather than on screen by default. A participant needs "1.2.0 (5)";
 * a forty-character provenance string in a student's account screen is noise until the moment
 * somebody asks for it, and then it has to be exact — which is why it is copyable text and not a
 * number read aloud.
 *
 * Nothing here is typed by hand. Everything comes from [BuildIdentity], generated from the single
 * `monad.version` property both platforms build against. The app used to carry a hand-maintained
 * `0.3.0-lab` that no build system used and that therefore identified nothing.
 */
@Composable
fun AppVersionLine(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF94A3B8),
) {
    var showBuildId by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showBuildId = !showBuildId },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Monad Scan ${AppConfig.APP_VERSION} (${AppConfig.APP_BUILD})",
            fontSize = 12.sp,
            color = color,
            textAlign = TextAlign.Center,
        )
        if (showBuildId) {
            Text(
                text = AppConfig.BUILD_ID,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = color,
                textAlign = TextAlign.Center,
            )
            if (BuildIdentity.DIRTY) {
                // Worth saying plainly. A dirty build cannot be reproduced from the repository, so
                // a recording it made is not attributable to a commit — only to this one machine.
                Text(
                    text = "Built from an uncommitted tree — not reproducible.",
                    fontSize = 10.sp,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
