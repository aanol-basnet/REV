package app.rev.core.ui.components

import androidx.compose.foundation.lazy.LazyListState

/**
 * Where a row dragged [offset] px from its place lands: the position (per [positionOf], which
 * ignores headers and footers by returning null) of the row under the dragged row's centre.
 * Measured from the laid-out rows rather than a fixed row height, so rows that grow with large
 * fonts land where they're dropped. Past the visible rows, the first or last one. Null if the
 * dragged row isn't laid out.
 */
fun LazyListState.dropTarget(draggedKey: Any, offset: Float, positionOf: (key: Any) -> Int?): Int? {
    val items = layoutInfo.visibleItemsInfo
    val dragged = items.firstOrNull { it.key == draggedKey } ?: return null
    val centre = dragged.offset + dragged.size / 2f + offset
    val rows = items.mapNotNull { info -> positionOf(info.key)?.let { info to it } }
    if (rows.isEmpty()) return null
    val hit = rows.firstOrNull { (info, _) -> centre >= info.offset && centre < info.offset + info.size }
        ?: if (centre < rows.first().first.offset) rows.first() else rows.last()
    return hit.second
}
