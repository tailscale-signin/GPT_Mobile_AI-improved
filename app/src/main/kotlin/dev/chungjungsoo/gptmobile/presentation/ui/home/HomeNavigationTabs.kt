package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

internal enum class HomeDestination { CHATS, GATEWAY, FAVORITES }

/** Navigation exists independently of connection discovery; asynchronous status cannot move a tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeNavigationTabs(
    selected: HomeDestination,
    onChats: () -> Unit,
    onGateway: () -> Unit,
    onFavorites: () -> Unit
) {
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selected.ordinal, matchContentSize = true),
                width = Dp.Unspecified,
                color = MaterialTheme.colorScheme.primary
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)) }
    ) {
        Tab(
            selected = selected == HomeDestination.CHATS,
            onClick = onChats,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            text = { Text(stringResource(R.string.chats)) },
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) }
        )
        Tab(
            modifier = Modifier.testTag("home_gateway"),
            selected = selected == HomeDestination.GATEWAY,
            onClick = onGateway,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            text = { Text(stringResource(R.string.gateway_menu_title)) },
            icon = { Icon(Icons.Rounded.CloudQueue, contentDescription = null) }
        )
        Tab(
            selected = selected == HomeDestination.FAVORITES,
            onClick = onFavorites,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            text = { Text(stringResource(R.string.favorites)) },
            icon = { Icon(if (selected == HomeDestination.FAVORITES) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null) }
        )
    }
}
