package com.badmanners.animurglar.ui.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmanners.animurglar.media.RawSeason


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonSelection(
    seasons: List<RawSeason>,
    selectedSeason: RawSeason?,
    onSeasonSelected: (RawSeason) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().selectableGroup(),
    ) {
        seasons.sorted().forEach { season ->
            FilterChip(
                selected = season == selectedSeason,
                onClick = { if (season != selectedSeason) onSeasonSelected(season) },
                enabled = enabled,
                label = { Text(season.label) },
            )
        }
    }
}
