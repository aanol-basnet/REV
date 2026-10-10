package app.rev.feature.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Dates, times and numbers in reports, in the report's time zone and the device's language. */
internal class StatsFormat(timeZone: String, private val locale: Locale = Locale.getDefault()) {
    private val zone: ZoneId = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    private val shortMonth = DateTimeFormatter.ofPattern("MMM", locale)
    private val shortMonthYear = DateTimeFormatter.ofPattern("MMM yy", locale)
    private val weekdayDay = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val year = DateTimeFormatter.ofPattern("yyyy", locale)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** "5 – 11 Oct 2026", or "28 Sep – 4 Oct 2026" across months. [to] is exclusive. */
    fun range(from: Long, to: Long): String {
        val start = date(from)
        val end = date(to - 1)
        return when {
            start == end -> dayMonthYear.format(start)
            start.year != end.year -> "${dayMonthYear.format(start)} – ${dayMonthYear.format(end)}"
            start.month == end.month -> "${start.dayOfMonth} – ${dayMonthYear.format(end)}"
            else -> "${dayMonth.format(start)} – ${dayMonthYear.format(end)}"
        }
    }

    fun month(ms: Long): String = monthYear.format(date(ms))

    /** The month alone, "October" (standalone form, for languages that inflect it in dates). */
    fun monthName(ms: Long): String = date(ms).month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun year(ms: Long): String = year.format(date(ms))
    fun day(ms: Long): String = dayMonthYear.format(date(ms))
    fun weekdayDay(ms: Long): String = weekdayDay.format(date(ms))

    /** Short label under a timeline bar: the day of the month, or "Jan" / "Jan 25" for months. */
    fun axisLabel(ms: Long, byMonth: Boolean, spansYears: Boolean): String = when {
        !byMonth -> date(ms).dayOfMonth.toString()
        spansYears -> shortMonthYear.format(date(ms))
        else -> shortMonth.format(date(ms))
    }

    /** Monday is 0. */
    fun weekday(index: Int, style: TextStyle = TextStyle.FULL): String =
        DayOfWeek.of(index + 1).getDisplayName(style, locale)

    fun hour(hour: Int): String = time.format(LocalTime.of(hour, 0))

    fun percent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

    /** Change from [previous] to [current] as a whole percentage; null without a base to compare. */
    fun change(current: Long, previous: Long): Int? =
        if (previous <= 0) null else (((current - previous).toDouble() / previous) * 100).roundToInt()
}
