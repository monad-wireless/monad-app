package sk.martinvanco.monad.main.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.TabNavigator
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import sk.martinvanco.monad.core.navigation.TabScreen

/**
 * Main container screen that holds the bottom navigation bar
 * and displays the selected tab content with swipe navigation
 */
class MainContainerScreen : Screen {
    @Composable
    override fun Content() {
        TabNavigator(TabScreen.HomeTab) { tabNavigator ->
            val pagerState = rememberPagerState(
                initialPage = TabScreen.tabs.indexOf(tabNavigator.current),
                pageCount = { TabScreen.tabs.size }
            )
            val coroutineScope = rememberCoroutineScope()

            // Sync pager state with tab navigator
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    val newTab = TabScreen.tabs[page]
                    if (tabNavigator.current != newTab) {
                        tabNavigator.current = newTab
                    }
                }
            }

            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TabScreen.tabs.forEachIndexed { index, tab ->
                                TabNavigationItem(
                                    tab = tab,
                                    selected = tabNavigator.current.key == tab.key,
                                    onClick = {
                                        tabNavigator.current = tab
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            ) { paddingValues ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) { page ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        TabScreen.tabs[page].Content()
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabNavigationItem(
    tab: TabScreen,
    selected: Boolean,
    onClick: () -> Unit
) {
    val inactiveColor = Color(0xFF888888)
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp)) // Rounded corners for ripple
            .clickable(
                onClick = onClick,
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = primaryColor.copy(alpha = 0.3f)
                ),
                role = Role.Tab
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(tab.icon),
            contentDescription = tab.label,
            tint = if (selected) primaryColor else inactiveColor,
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) primaryColor else inactiveColor
        )
    }
}
