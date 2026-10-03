package com.alarmhub.app.domain.calendar

import com.alarmhub.app.domain.json.MiniJson
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads the bundled `assets/holidays.json` into [Holidays] (PRD FR-6.1 / FR-6.2).
 *
 * The file format is intentionally about *citable* dates: `holiday` and `workday` list exactly the
 * days a 国务院办公厅 notice names, so the data can be checked line by line against the notice.
 * `rest` (grouped holiday blocks such as 春节) is carried along for display only — it never takes
 * part in any decision, so a mistake there cannot make an alarm ring on the wrong day.
 */
object HolidayDataParser {

    class HolidayDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** One holiday block as published, kept for UI display. */
    data class HolidayPeriod(val name: String, val dates: List<LocalDate>)

    data class ParsedHolidays(
        val holidays: Holidays,
        val periods: List<HolidayPeriod>,
        val source: String,
    )

    fun parse(text: String): ParsedHolidays {
        val root = MiniJson.parse(text).asObject("root")
        val source = root["source"]?.asStringOrNull() ?: DEFAULT_SOURCE

        val holidayDates = LinkedHashSet<LocalDate>()
        val workdayDates = LinkedHashSet<LocalDate>()
        val years = LinkedHashSet<Int>()
        val periods = ArrayList<HolidayPeriod>()

        for (yearValue in root.requiredArray("years")) {
            val yearObject = yearValue.asObject("years[]")
            val year = yearObject.requiredNumber("year").toInt()
            years += year

            holidayDates += yearObject.optionalStringArray("holiday").map { parseDate(it) }
            workdayDates += yearObject.optionalStringArray("workday").map { parseDate(it) }

            for (periodValue in yearObject["rest"]?.asArrayOrNull() ?: emptyList()) {
                val periodObject = periodValue.asObject("rest[]")
                val name = periodObject.requiredString("name")
                val dates = periodObject.optionalStringArray("dates").map { parseDate(it) }
                periods += HolidayPeriod(name, dates)
            }
        }

        if (years.isEmpty()) throw HolidayDataException("holiday data contains no years")

        return ParsedHolidays(
            holidays = Holidays(holidayDates, workdayDates, years),
            periods = periods,
            source = source,
        )
    }

    /** PRD FR-6.4: the notice the bundled data was transcribed from. */
    const val DEFAULT_SOURCE: String =
        "国务院办公厅关于2025年、2026年部分节假日安排的通知（国办发明电〔2024〕12号、〔2025〕7号）"

    private fun parseDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw HolidayDataException("'$raw' is not a yyyy-MM-dd date", e)
        }

    // ---- small typed accessors, so a malformed file fails with a useful message ----

    private fun MiniJson.Value.asObject(where: String): MiniJson.Value.Obj =
        this as? MiniJson.Value.Obj
            ?: throw HolidayDataException("expected a JSON object at $where")

    private operator fun MiniJson.Value.Obj.get(key: String): MiniJson.Value? = entries[key]

    private fun MiniJson.Value.asArrayOrNull(): List<MiniJson.Value>? = when (this) {
        is MiniJson.Value.Arr -> items
        MiniJson.Value.Null -> null
        else -> throw HolidayDataException("expected a JSON array")
    }

    private fun MiniJson.Value.asStringOrNull(): String? = when (this) {
        is MiniJson.Value.Str -> value
        MiniJson.Value.Null -> null
        else -> throw HolidayDataException("expected a JSON string")
    }

    private fun MiniJson.Value.Obj.requiredArray(key: String): List<MiniJson.Value> =
        this[key]?.asArrayOrNull() ?: throw HolidayDataException("missing array '$key'")

    private fun MiniJson.Value.Obj.requiredNumber(key: String): Double = when (val v = this[key]) {
        is MiniJson.Value.Num -> v.value
        else -> throw HolidayDataException("missing number '$key'")
    }

    private fun MiniJson.Value.Obj.requiredString(key: String): String =
        this[key]?.asStringOrNull() ?: throw HolidayDataException("missing string '$key'")

    private fun MiniJson.Value.Obj.optionalStringArray(key: String): List<String> {
        val array = this[key] ?: return emptyList()
        return array.asArrayOrNull().orEmpty().map {
            it.asStringOrNull() ?: throw HolidayDataException("'$key' must contain only strings")
        }
    }
}
