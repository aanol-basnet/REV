package app.rev.domain.modes

import kotlin.math.ln
import kotlinx.coroutines.flow.Flow

/** What a mode leans towards when picking songs. */
enum class ModeLean(val wire: String) {
    None("none"),

    /** Songs you know and love: crowd-pleasers, background music. */
    Familiar("familiar"),

    /** Songs you haven't heard much: new ideas, inspiration. */
    Discover("discover"),
}

/**
 * What you're doing while you listen: driving alone, driving with family, working, at home,
 * looking for ideas for your next jam, or one of your own. Every listen records the mode, so the
 * taste profile and the personal model learn your taste in each.
 */
data class ListeningMode(val id: String, val name: String, val lean: ModeLean = ModeLean.None, val builtIn: Boolean = false) {
    companion object {
        const val CUSTOM_PREFIX = "custom:"
    }
}

object BuiltInModes {
    val DrivingSolo = ListeningMode("driving_solo", "Driving solo", ModeLean.None, builtIn = true)
    val DrivingFamily = ListeningMode("driving_family", "Driving with family", ModeLean.Familiar, builtIn = true)
    val Work = ListeningMode("work", "At work", ModeLean.Familiar, builtIn = true)
    val Home = ListeningMode("home", "At home", ModeLean.None, builtIn = true)
    val Jam = ListeningMode("jam", "Jam inspiration", ModeLean.Discover, builtIn = true)

    val all = listOf(DrivingSolo, DrivingFamily, Work, Home, Jam)
}

/** How the current mode was set. */
enum class ModeSource(val wire: String) {
    /** You chose it. */
    Picked("picked"),

    /** REV recognised the situation from the times you chose it before. */
    Auto("auto"),
}

data class ActiveMode(val mode: ListeningMode, val source: ModeSource, val since: Long)

interface ModeRepository {
    /** The built-in modes, then yours. */
    val modes: Flow<List<ListeningMode>>
    val active: Flow<ActiveMode?>

    /** Picks [modeId]; null hands the choice back to REV (automatic). */
    suspend fun pick(modeId: String?)

    /** REV's own choice, used while you haven't picked one. */
    suspend fun setAuto(mode: ListeningMode?)

    suspend fun addCustom(name: String, lean: ModeLean): ListeningMode
    suspend fun removeCustom(id: String)
}

/** The mode right now, kept up to date by playback; read when ranking songs. */
class CurrentMode {
    @Volatile var value: ActiveMode? = null
}

/** The situation a mode is guessed from. */
data class ModeSituation(
    val output: String?,
    /** The Bluetooth or headphone device's name: the car's kit, your earbuds. */
    val outputName: String?,
    val hour: Int,
    /** 0 = Monday. */
    val weekday: Int,
    val network: String?,
    val charging: Boolean?,
) {
    fun features(): List<String> = listOfNotNull(
        output?.let { "o:$it" },
        outputName?.let { "on:${it.lowercase()}" },
        "p:${hour / 4}",
        "we:${weekday >= 5}",
        network?.let { "n:$it" },
        charging?.let { "c:$it" },
    )
}

/**
 * A time you picked a mode, and the situation you were in. [NO_MODE] records listening without
 * one (a session REV had no guess for, or turning its guess off), so a mode isn't guessed
 * everywhere just because it's the only one you've picked.
 */
data class ModeChoice(val modeId: String, val situation: ModeSituation, val at: Long) {
    companion object {
        const val NO_MODE = ""
    }
}

interface ModeChoiceStore {
    suspend fun add(choice: ModeChoice)
    suspend fun recent(limit: Int): List<ModeChoice>
}

/**
 * Guesses the mode from the situation, learned from the times you picked one (naive Bayes over
 * the output device and its name, part of the day, weekday or weekend, network and charging).
 * Only guesses when it's fairly sure, it's more likely than listening without a mode, and you've
 * picked that mode a few times.
 */
class ModeGuesser(choices: List<ModeChoice>) {
    private val perMode = choices.groupBy { it.modeId }
    private val counts = perMode.mapValues { (_, cs) -> cs.flatMap { it.situation.features() }.groupingBy { it }.eachCount() }
    private val total = choices.size

    /** The likeliest mode and how sure (0–1), or null when unsure. */
    fun guess(situation: ModeSituation): Pair<String, Double>? {
        if (total == 0) return null
        val features = situation.features()
        val logs = perMode.mapValues { (mode, cs) ->
            val n = cs.size.toDouble()
            val seen = counts.getValue(mode)
            ln(n / total) + features.sumOf { ln(((seen[it] ?: 0) + SMOOTHING) / (n + 2 * SMOOTHING)) }
        }
        val top = logs.maxOf { it.value }
        val odds = logs.mapValues { kotlin.math.exp(it.value - top) }
        val (best, weight) = odds.maxBy { it.value }
        val sure = weight / odds.values.sum()
        if (best == ModeChoice.NO_MODE) return null
        return (best to sure).takeIf { sure >= MIN_SURE && perMode.getValue(best).size >= MIN_PICKS }
    }

    companion object {
        const val MIN_SURE = 0.6
        const val MIN_PICKS = 3
        private const val SMOOTHING = 0.5
    }
}

/**
 * Keeps the mode right as you listen. A mode you picked lasts until you start listening somewhere
 * else (another output, such as leaving the car) or you take a break (half an hour without
 * listening) after picking it; then REV guesses from the times you picked one before. Each pick is remembered with
 * its situation to learn from, and so are sessions with no guess, as "no mode".
 */
class ModeKeeper(choices: List<ModeChoice> = emptyList()) {
    private val choices = choices.toMutableList()
    private var guesser = ModeGuesser(this.choices)

    /** The mode in use, as last decided here or picked by you. */
    var active: ActiveMode? = null
        private set
    var modes: List<ListeningMode> = BuiltInModes.all

    /** Where you were listening when you picked the mode. */
    private var pickedOutput: String? = null

    /** Your past choices, loaded from storage after start; [restore] the mode that was in use. */
    fun load(saved: List<ModeChoice>) {
        choices.addAll(0, saved)
        guesser = ModeGuesser(choices)
    }

    fun restore(saved: ActiveMode?) {
        active = saved
    }

    /** Whether [other] is the mode decided here (an echo from storage, not a change by you). */
    fun isCurrent(other: ActiveMode?): Boolean = other?.mode?.id == active?.mode?.id && other?.source == active?.source

    /** What to do as a song starts: the mode to use, and a choice to remember (if any). */
    data class Decision(val active: ActiveMode?, val remember: ModeChoice?)

    fun onSongStart(situation: ModeSituation, newSession: Boolean, now: Long): Decision {
        val current = active
        if (current?.source == ModeSource.Picked) {
            // Where you listen is taken from the first song after picking: pick, then get in the car.
            val where = outputKey(situation)
            if (pickedOutput == null) pickedOutput = where
            // A break ends a mode picked before it, not one picked just now.
            val endedByBreak = newSession && now - current.since > BREAK_MS
            if (where == pickedOutput && !endedByBreak) return Decision(current, null)
        }
        val guess = guesser.guess(situation)?.first?.let { id -> modes.firstOrNull { it.id == id } }
        val next = guess?.let { if (current?.mode?.id == it.id && current.source == ModeSource.Auto) current else ActiveMode(it, ModeSource.Auto, now) }
        active = next
        val remember = if (newSession && guess == null) ModeChoice(ModeChoice.NO_MODE, situation, now).also(::learn) else null
        return Decision(next, remember)
    }

    /**
     * You picked a mode ([picked]) or switched to automatic (null). Returns the choice to remember:
     * the mode in this situation, or "no mode" when you turned off REV's guess.
     */
    fun onUserChange(picked: ActiveMode?, situation: ModeSituation, now: Long): ModeChoice? {
        val before = active
        active = picked
        pickedOutput = null
        val choice = when {
            picked != null -> ModeChoice(picked.mode.id, situation, now)
            before?.source == ModeSource.Auto -> ModeChoice(ModeChoice.NO_MODE, situation, now)
            else -> null
        }
        choice?.let(::learn)
        return choice
    }

    private fun learn(choice: ModeChoice) {
        choices += choice
        if (choices.size > MAX_CHOICES) choices.removeAt(0)
        guesser = ModeGuesser(choices)
    }

    private fun outputKey(s: ModeSituation) = "${s.output}/${s.outputName}"

    companion object {
        const val MAX_CHOICES = 500

        /** The same break that starts a new listening session. */
        const val BREAK_MS = 30 * 60_000L
    }
}
