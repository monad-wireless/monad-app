package sk.martinvanco.monad.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.account_icon
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.vectorResource
import sk.martinvanco.monad.ui.theme.lightBackground

@OptIn(ExperimentalResourceApi::class)
@Composable
fun ScreenWithBackNavigation(
    title: String,
    onBackClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.background

    val topBarModifier = Modifier.displayCutoutPadding()
        .statusBarsPadding()
        .fillMaxWidth()
        .height(70.dp)

    if (isSystemInDarkTheme()) {
        topBarModifier.background(containerColor)
    } else {
        topBarModifier.shadow(
            elevation = 16.dp,
            spotColor = Color(0x40E9E9E9),
            ambientColor = Color(0x40E9E9E9)
        ).background(Color(0xFFFFFFFF))
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = topBarModifier
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = Color(0xFF0F142F),
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClick = onBackClick,
                            interactionSource = interactionSource,
                            indication = ripple(bounded = false, radius = 24.dp)
                        )
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color =  Color(0xFF0F142F),
                    letterSpacing = (-0.5).sp
                )
            }
        }

        // Screen content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}
