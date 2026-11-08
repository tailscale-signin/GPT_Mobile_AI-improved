package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectPlatformProfileCard(
    profile: PlatformV2,
    selected: Boolean,
    insight: ProfileInsight?,
    onToggle: (String) -> Unit,
    onFavorite: () -> Unit,
    onInsights: () -> Unit,
    gatewayAvailability: GatewayAvailability? = null,
    animateGateway: Boolean = false,
    onFocus: (Boolean) -> Unit = {}
) {
    val health by dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.collectAsState()
    val now by dev.chungjungsoo.gptmobile.presentation.common.freeProviderClock()
    val unavailable = androidx.compose.runtime.remember(profile, health, now) { dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile) }
    val favoriteLabel = stringResource(if (profile.isFavorite) R.string.picker_remove_favorite else R.string.picker_add_favorite)
    val lines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 1
    val surface = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val online = gatewayAvailability?.isOnline == true
    val nameColor = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val treatment = remember(online, nameColor, surface) {
        if (online) gatewayNameTreatment(nameColor.toArgb(), surface.toArgb()) else GatewayNameTreatment(surface.toArgb(), 1f)
    }
    val nameAlpha by rememberGatewayNameAlpha(online && animateGateway, Color(treatment.backgroundArgb), nameColor, treatment.minimumOpacity)
    DisposableEffect(profile.uid) { onDispose { onFocus(false) } }
    Surface(
        Modifier.fillMaxWidth().testTag("profile_card_${profile.uid}").onFocusChanged { onFocus(it.hasFocus) }.focusGroup(),
        shape = RoundedCornerShape(16.dp),
        color = surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints {
            val metricsWidth = (maxWidth * .45f).coerceAtLeast(48.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 64.dp).testTag("profile_select_${profile.uid}").alpha(if (unavailable != null && !selected) 0.55f else 1f)
                        .combinedClickable(enabled = selected || unavailable == null, role = Role.Checkbox, onClick = { onToggle(profile.uid) }, onLongClick = onFavorite)
                        .semantics(mergeDescendants = true) {
                            toggleableState = ToggleableState(selected)
                            customActions = listOf(
                                CustomAccessibilityAction(favoriteLabel) {
                                    onFavorite()
                                    true
                                }
                            )
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = selected, onCheckedChange = null, enabled = selected || unavailable == null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                profile.name,
                                Modifier.weight(1f).background(Color(treatment.backgroundArgb), MaterialTheme.shapes.extraSmall).alpha(nameAlpha)
                                    .testTag("gateway_name_${profile.uid}").semantics { gatewayNameOpacity = nameAlpha },
                                color = nameColor,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (profile.isFavorite) Icon(Icons.Rounded.Star, stringResource(R.string.picker_favorite), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            if (online) GatewayProfileStatus(requireNotNull(gatewayAvailability), Modifier.testTag("gateway_availability_${profile.uid}"))
                        }
                        gatewayAvailability?.takeIf { !it.isOnline && it.status != GatewayAvailabilityStatus.NOT_GATEWAY }?.let {
                            GatewayProfileStatus(it)
                        }
                        if (gatewayAvailability?.engineeringBinding == true && gatewayAvailability.gatewayProfileUid != profile.uid && gatewayAvailability.gatewayName.isNotBlank()) {
                            Text(stringResource(R.string.gateway_availability_via, gatewayAvailability.gatewayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        unavailable?.let { blocked ->
                            val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(profile.apiUrl)
                            val description = provider?.let { dev.chungjungsoo.gptmobile.presentation.common.freeProviderAvailability(it, profile.model, health[it], now, profile.token) } ?: blocked.message
                            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(profile.model, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.primary, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    }
                }
                insight?.compact()?.let { compact ->
                    if (compact.scoreState != InsightAvailability.HIDDEN || compact.priceState != InsightAvailability.HIDDEN) {
                        val locale = LocalLocale.current.platformLocale
                        val score = if (compact.scoreState == InsightAvailability.LOADING) "…" else compact.score?.let { String.format(locale, "%.0f", it) } ?: "—"
                        val rate = when (compact.priceState) {
                            InsightAvailability.ON_DEVICE -> stringResource(R.string.picker_on_device)
                            InsightAvailability.LOADING -> stringResource(R.string.picker_loading)
                            InsightAvailability.AVAILABLE -> compact.rate.orEmpty() + if (compact.qualified) " ⓘ" else ""
                            else -> stringResource(R.string.insights_price_unknown)
                        }
                        val origin = stringResource(if (compact.community) R.string.insights_community else R.string.insights_my_best)
                        val description = listOfNotNull(
                            stringResource(R.string.picker_insights_for, profile.name),
                            if (compact.scoreState != InsightAvailability.HIDDEN) "$origin: $score" else null,
                            if (compact.priceState != InsightAvailability.HIDDEN) "$rate · ${stringResource(R.string.picker_rate_legend)}" else null,
                            if (compact.qualified) stringResource(R.string.picker_qualified_rate) else null
                        ).joinToString(". ")
                        TextButton(onClick = onInsights, modifier = Modifier.widthIn(min = 48.dp, max = metricsWidth).heightIn(min = 48.dp).testTag("profile_insights_${profile.uid}").semantics { contentDescription = description }) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (compact.scoreState != InsightAvailability.HIDDEN) Text(score, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                if (compact.priceState != InsightAvailability.HIDDEN) Text(rate, style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}
