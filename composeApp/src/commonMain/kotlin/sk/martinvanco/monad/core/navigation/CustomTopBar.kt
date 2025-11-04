package sk.martinvanco.monad.core.navigation
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.account_icon
import monad.composeapp.generated.resources.monad_logo_dark
import monad.composeapp.generated.resources.profile
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import sk.martinvanco.monad.ui.theme.lightBackground

@OptIn(ExperimentalResourceApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CustomTopBar(
    onProfileIconClick: () -> Unit
) {
    val containerColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.background
    } else {
        lightBackground
    }

    val topBarModifier = if (isSystemInDarkTheme()) {
        Modifier.displayCutoutPadding().height(90.dp)
    } else {
        Modifier.displayCutoutPadding().height(90.dp).shadow(
            elevation = 16.dp, spotColor = Color(0x40E9E9E9), ambientColor = Color(0x40E9E9E9)
        )
    }

    TopAppBar(
        navigationIcon = {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(Res.drawable.monad_logo_dark),
                    contentDescription = "Monad Logo",
                    modifier = Modifier
                        .height(30.dp)
                        .padding(start = 24.dp)
                )
            }
        },
        title = { },
        actions = {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        onProfileIconClick()
                    },
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .size(size = 32.dp)
                ) {
                    Icon(
                        modifier = Modifier.size(24.dp),
                        imageVector = vectorResource(Res.drawable.account_icon),
                        contentDescription = stringResource(Res.string.profile),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        modifier = topBarModifier
    )
}