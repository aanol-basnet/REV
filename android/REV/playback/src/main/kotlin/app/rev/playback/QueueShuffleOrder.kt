package app.rev.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The shuffled play order, kept predictable when the queue changes. ExoPlayer's default scatters
 * inserted songs at random and ignores moves; here, songs inserted or moved into the queue play
 * right after the song that now precedes them in the queue (first, if there is none). So "Play
 * next" (insert after the current song) and dragging in the shuffled queue do what they show.
 * A queue filled from empty is shuffled at random.
 */
@OptIn(UnstableApi::class)
class QueueShuffleOrder private constructor(
    /** Queue indices in play order. */
    private val shuffled: IntArray,
    private val random: Random,
) : ShuffleOrder {

    constructor(random: Random = Random.Default) : this(IntArray(0), random)

    /** Position in play order of each queue index. */
    private val positions = IntArray(shuffled.size).also { p -> shuffled.forEachIndexed { pos, index -> p[index] = pos } }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int = shuffled.getOrNull(positions[index] + 1) ?: C.INDEX_UNSET

    override fun getPreviousIndex(index: Int): Int = shuffled.getOrNull(positions[index] - 1) ?: C.INDEX_UNSET

    override fun getLastIndex(): Int = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = shuffled.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val inserted = List(insertionCount) { insertionIndex + it }
        if (shuffled.isEmpty()) return QueueShuffleOrder(inserted.shuffled(random).toIntArray(), random)
        val shifted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }
        return QueueShuffleOrder(placeAfter(shifted, insertionIndex - 1, inserted), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = shuffled.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        // Where each queue index ends up once the range has moved.
        val moved = (0 until shuffled.size).toMutableList().apply {
            val range = subList(indexFrom, indexToExclusive).toList()
            subList(indexFrom, indexToExclusive).clear()
            addAll(newIndexFrom, range)
        }
        val newIndexOf = IntArray(shuffled.size).also { n -> moved.forEachIndexed { newIndex, old -> n[old] = newIndex } }
        val rest = shuffled.filter { it < indexFrom || it >= indexToExclusive }.map { newIndexOf[it] }
        return QueueShuffleOrder(placeAfter(rest, newIndexFrom - 1, List(count) { newIndexFrom + it }), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(random)

    /** Play order with [block] right after queue index [predecessor], or first if it is -1. */
    private fun placeAfter(order: List<Int>, predecessor: Int, block: List<Int>): IntArray {
        val at = if (predecessor < 0) 0 else order.indexOf(predecessor) + 1
        return (order.subList(0, at) + block + order.subList(at, order.size)).toIntArray()
    }

    companion object {
        /** A fresh shuffle of [length] songs that starts with [first], as when shuffle is turned on mid-queue. */
        fun startingWith(first: Int, length: Int, random: Random = Random.Default): QueueShuffleOrder {
            if (first !in 0 until length) return QueueShuffleOrder((0 until length).shuffled(random).toIntArray(), random)
            val rest = (0 until length).filter { it != first }.shuffled(random)
            return QueueShuffleOrder((listOf(first) + rest).toIntArray(), random)
        }
    }
}

/** Queue index to insert at so songs play after everything else, in play order. */
fun Player.endOfPlayOrder(): Int =
    if (shuffleModeEnabled && mediaItemCount > 0) currentTimeline.getLastWindowIndex(true) + 1 else mediaItemCount

/**
 * The queue move that puts the song at play position [from] at play position [to], given the
 * queue indices in play order. With [QueueShuffleOrder] a moved song plays right after its new
 * queue predecessor, so the move targets the queue slot after the song it should follow.
 * Returns (queue index, new queue index), or null if nothing moves.
 */
fun queueMove(order: List<Int>, from: Int, to: Int): Pair<Int, Int>? {
    if (from !in order.indices || to !in order.indices || from == to) return null
    val item = order[from]
    val predecessor = if (to > from) order[to] else order.getOrNull(to - 1)
    val newIndex = when {
        predecessor == null -> 0
        item > predecessor -> predecessor + 1
        else -> predecessor
    }
    return item to newIndex
}
