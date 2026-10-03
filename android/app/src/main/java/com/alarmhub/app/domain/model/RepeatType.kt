package com.alarmhub.app.domain.model

/** Mirrors PRD §5.2 / the `RepeatType` union in the frozen contract (`web/src/bridge/types.ts`). */
enum class RepeatType {
    /** A one-off alarm: matches exactly one calendar date. */
    ONCE,
    DAILY,
    WEEKLY,
    /** Statutory working day (PRD §5.2 `WORKDAY`). */
    WORKDAY,
    /** Statutory day off (PRD §5.2 `HOLIDAY`). */
    HOLIDAY,
}

/**
 * The 7-bit weekday mask used by [RepeatType.WEEKLY], bit 0 = Monday … bit 6 = Sunday.
 *
 * Kept as a value class over the raw `Int` so the database column and the JSON bridge can keep
 * passing a plain number while the domain still gets a name for it.
 */
@JvmInline
value class WeekdayMask(val bits: Int) {

    init {
        require(bits in 0..ALL_DAYS) { "weekday mask must be a 7-bit value, was $bits" }
    }

    operator fun contains(mondayBasedIndex: Int): Boolean =
        mondayBasedIndex in 0 until DAYS_PER_WEEK && (bits shr mondayBasedIndex) and 1 == 1

    /** True when no weekday is selected — a WEEKLY alarm in this state never rings (PRD §5.1). */
    val isEmpty: Boolean get() = bits == 0

    val selectedWeekdays: List<Int> get() = (0 until DAYS_PER_WEEK).filter { it in this }

    companion object {
        const val DAYS_PER_WEEK = 7

        /** All seven bits set. */
        const val ALL_DAYS = 0b111_1111

        fun of(mondayBasedIndices: Iterable<Int>): WeekdayMask =
            WeekdayMask(mondayBasedIndices.fold(0) { acc, day -> acc or (1 shl day) })

        /** 周一…周五. */
        val MONDAY_TO_FRIDAY = of(0..4)
    }
}
