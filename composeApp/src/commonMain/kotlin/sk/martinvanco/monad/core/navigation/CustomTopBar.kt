package sk.martinvanco.monad.core.navigation
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.unit.sp
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.account_icon
import monad.composeapp.generated.resources.monad_logo_dark
import monad.composeapp.generated.resources.profile
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import sk.martinvanco.monad.ui.theme.lightBackground

/**
 * The home bar: logo, the inbox bell, the account.
 *
 * [unreadCount] comes from the process-wide inbox (IP-157) so the badge is right on a phone with
 * no route out. Capped at "9+" because a number that needs three digits is not a count anyone acts
 * on; the list is one tap away.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CustomTopBar(
    onProfileIconClick: () -> Unit,
    unreadCount: Int = 0,
    onNotificationsClick: () -> Unit = {},
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
            Row(
                modifier = Modifier.fillMaxHeight().padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .size(size = 32.dp)
                ) {
                    BadgedBox(
                        badge = {
                            if (unreadCount > 0) {
                                Badge(containerColor = Color(0xFFDC2626), contentColor = Color.White) {
                                    Text(
                                        text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            modifier = Modifier.size(24.dp),
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = if (unreadCount > 0) {
                                "Notifications, $unreadCount unread"
                            } else {
                                "Notifications"
                            },
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                IconButton(
                    onClick = {
                        onProfileIconClick()
                    },
                    modifier = Modifier
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
