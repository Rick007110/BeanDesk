package nl.beandesk.app.ui.places

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.model.Category

@OptIn(ExperimentalComposeUiApi::class) // pluralStringResource, in older Compose versions
@Composable
fun PlaceList(
    items: List<PlaceItem>,
    // The city or search shown; another one starts at the top of the list again.
    areaKey: String,
    totalCount: Int,
    isRefreshing: Boolean,
    attribution: String,
    onPlaceClick: (Long) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(areaKey) { listState.scrollToItem(0) }
    LazyColumn(
        modifier = modifier,
        state = listState,
        // Bottom padding keeps the last card clear of the floating map button.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) {
            item(key = "header") { Box(Modifier.animateItem()) { header() } }
        }
        if (items.isEmpty()) {
            if (!isRefreshing) {
                item(key = "empty") {
                    EmptyState(filtered = totalCount > 0, onClearFilters = onClearFilters, modifier = Modifier.animateItem())
                }
            }
        } else {
            item(key = "count") {
                val count = if (items.size == totalCount) {
                    pluralStringResource(R.plurals.places_count, totalCount, totalCount)
                } else {
                    pluralStringResource(R.plurals.places_count_filtered, totalCount, items.size, totalCount)
                }
                // The new count fades in over the old one.
                Crossfade(count, modifier = Modifier.animateItem(), label = "count") { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Cards fade in and out and slide to their new spot when filters or the search change the list.
        items(items, key = { it.place.id }) { item ->
            PlaceCard(item, onClick = { onPlaceClick(item.place.id) }, modifier = Modifier.animateItem())
        }
        if (attribution.isNotEmpty()) {
            item(key = "attribution") {
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceCard(item: PlaceItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val place = item.place
    // The card gives a little under a finger, and springs back.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        // Grows and shrinks smoothly when a report adds or changes facts.
        Row(
            Modifier
                .animateContentSize()
                .padding(16.dp),
        ) {
            CategoryBadge(place.category, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = placeSubtitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RatingStars(place.rating)
                StatusLine(item.status)
                val chips = factChips(place)
                if (chips.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_reports_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { FactPill(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryBadge(category: Category, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(category.iconRes),
            contentDescription = stringResource(category.labelRes),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size / 2),
        )
    }
}

/** A coloured dot with "Nu open · sluit over 2 u" and the like. */
@Composable
fun StatusLine(status: OpenStatus) {
    // Fades to the new colour when a place opens or closes while the screen is open.
    val color by animateColorAsState(statusColor(status), label = "status")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText(status),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun FactPill(chip: FactChip) {
    val container = if (chip.warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (chip.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(chip.iconRes), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(chip.labelRes), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean, onClearFilters: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.no_places_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(if (filtered) R.string.no_places_filtered else R.string.no_places_loaded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (filtered) {
            TextButton(onClick = onClearFilters) { Text(stringResource(R.string.clear_filters)) }
        }
    }
}
