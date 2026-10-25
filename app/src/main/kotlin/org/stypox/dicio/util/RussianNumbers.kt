package org.stypox.dicio.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Minimal support for Russian numbers and durations, used because dicio-numbers does not support
 * Russian (yet). Provides:
 * - [parseMixed]: splits text into strings and numbers ("сколько будет двадцать пять плюс пять" ->
 *   ["сколько будет", 25.0, "плюс", 5.0]), used by the calculator
 * - [normalizeDurations]: rewrites Russian durations into a form that the English dicio-numbers
 *   parser understands ("таймер на две с половиной минуты" -> "таймер на 2 minutes 30 seconds"),
 *   used by the timer
 * - [parseDurationSeconds], [formatDuration], [formatNumber]: helpers for the timer and calculator
 *
 * This file has no Android dependencies so that it can be unit tested easily.
 */
object RussianNumbers {

    private enum class Kind { ZERO, UNIT, TEEN, TEN, HUNDRED, SCALE }

    private data class NumWord(val value: Long, val kind: Kind)

    private val NUM_WORDS: Map<String, NumWord> = HashMap<String, NumWord>().apply {
        fun add(kind: Kind, value: Long, vararg forms: String) {
            for (form in forms) {
                put(form, NumWord(value, kind))
            }
        }

        add(Kind.ZERO, 0, "ноль", "нуль", "ноля", "нуля", "нолю", "нулю", "нолем", "нулем")

        add(Kind.UNIT, 1, "один", "одна", "одно", "одну", "одного", "одной", "одному", "одним",
            "одною", "одни", "одних")
        add(Kind.UNIT, 2, "два", "две", "двух", "двум", "двумя")
        add(Kind.UNIT, 3, "три", "трех", "трем", "тремя")
        add(Kind.UNIT, 4, "четыре", "четырех", "четырем", "четырьмя")
        add(Kind.UNIT, 5, "пять", "пяти", "пятью")
        add(Kind.UNIT, 6, "шесть", "шести", "шестью")
        add(Kind.UNIT, 7, "семь", "семи", "семью")
        add(Kind.UNIT, 8, "восемь", "восьми", "восемью", "восьмью")
        add(Kind.UNIT, 9, "девять", "девяти", "девятью")

        listOf(
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
        ).forEachIndexed { i, word ->
            // e.g. "двенадцать", "двенадцати", "двенадцатью"
            add(Kind.TEEN, 10L + i, word, word.dropLast(1) + "и", word + "ю")
        }

        add(Kind.TEN, 20, "двадцать", "двадцати", "двадцатью")
        add(Kind.TEN, 30, "тридцать", "тридцати", "тридцатью")
        add(Kind.TEN, 40, "сорок", "сорока")
        add(Kind.TEN, 50, "пятьдесят", "пятидесяти", "пятьюдесятью")
        add(Kind.TEN, 60, "шестьдесят", "шестидесяти", "шестьюдесятью")
        add(Kind.TEN, 70, "семьдесят", "семидесяти", "семьюдесятью")
        add(Kind.TEN, 80, "восемьдесят", "восьмидесяти", "восемьюдесятью")
        add(Kind.TEN, 90, "девяносто", "девяноста")

        add(Kind.HUNDRED, 100, "сто", "ста", "сотня", "сотни", "сотню")
        add(Kind.HUNDRED, 200, "двести", "двухсот", "двумстам", "двумястами")
        add(Kind.HUNDRED, 300, "триста", "трехсот", "тремстам", "тремястами")
        add(Kind.HUNDRED, 400, "четыреста", "четырехсот", "четыремстам", "четырьмястами")
        add(Kind.HUNDRED, 500, "пятьсот", "пятисот", "пятистам", "пятьюстами")
        add(Kind.HUNDRED, 600, "шестьсот", "шестисот", "шестистам", "шестьюстами")
        add(Kind.HUNDRED, 700, "семьсот", "семисот", "семистам", "семьюстами")
        add(Kind.HUNDRED, 800, "восемьсот", "восьмисот", "восьмистам", "восемьюстами")
        add(Kind.HUNDRED, 900, "девятьсот", "девятисот", "девятистам", "девятьюстами")

        add(Kind.SCALE, 1_000, "тысяча", "тысячи", "тысяч", "тысячу", "тысячей", "тысячам",
            "тысячами", "тыща", "тыщи", "тыщ", "тыщу")
        add(Kind.SCALE, 1_000_000, "миллион", "миллиона", "миллионов", "миллиону", "миллионом",
            "миллионам", "миллионами")
        add(Kind.SCALE, 1_000_000_000, "миллиард", "миллиарда", "миллиардов", "миллиарду",
            "миллиардом", "миллиардам", "миллиардами")
    }

    private val ONE_AND_A_HALF = setOf("полтора", "полторы", "полутора")
    // "половиной" is not included since it is handled in "с половиной" by combineFractions()
    private val HALF = setOf("половина", "половину", "половины")
    private val DECIMAL_SEPARATOR = setOf("запятая", "точка")
    private val WHOLE = setOf("целых", "целая", "целую", "целой", "целые")
    private val FRACTION_DENOMINATOR = mapOf(
        "десятых" to 10.0, "десятая" to 10.0, "десятую" to 10.0, "десятые" to 10.0,
        "сотых" to 100.0, "сотая" to 100.0, "сотую" to 100.0, "сотые" to 100.0,
        "тысячных" to 1000.0, "тысячная" to 1000.0, "тысячную" to 1000.0, "тысячные" to 1000.0,
    )

    private val DIGITS_REGEX = Regex("^[+-]?\\d+([.,]\\d+)?$")
    private val PUNCTUATION_REGEX = Regex("^[\\p{Punct}«»…]+|[\\p{Punct}«»…]+$")

    /** A token of user input: either a word (keeping the original spelling) or a number. */
    private sealed interface Tok {
        data class Word(val original: String, val norm: String) : Tok
        data class Num(val value: Double, val digits: String? = null) : Tok
        data class Dur(val seconds: Double) : Tok
    }

    private fun normalizeWord(word: String): String {
        return word.lowercase().replace('ё', 'е').replace(PUNCTUATION_REGEX, "")
    }

    private fun tokenizeWords(text: String): List<Tok.Word> {
        return text.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { Tok.Word(it, normalizeWord(it)) }
    }

    /**
     * Turns words into numbers wherever possible, also handling digits already present in the text,
     * "полтора", "с половиной", "две целых пять десятых" and "два запятая пять".
     */
    private fun tokenize(text: String): List<Tok> {
        val words = tokenizeWords(text)
        val res = ArrayList<Tok>()

        var i = 0
        while (i < words.size) {
            val norm = words[i].norm

            if (DIGITS_REGEX.matches(norm)) {
                res.add(Tok.Num(norm.replace(',', '.').toDouble(), norm.trimStart('+', '-')))
                i += 1
                continue
            }

            if (norm in ONE_AND_A_HALF) {
                val next = words.getOrNull(i + 1)?.norm?.let { NUM_WORDS[it] }
                if (next != null && next.kind == Kind.SCALE) {
                    res.add(Tok.Num(1.5 * next.value)) // "полторы тысячи"
                    i += 2
                } else {
                    res.add(Tok.Num(1.5))
                    i += 1
                }
                continue
            }

            if (norm in HALF) {
                res.add(Tok.Num(0.5))
                i += 1
                continue
            }

            val consumed = parseNumberWords(words, i)
            if (consumed != null) {
                res.add(Tok.Num(consumed.first.toDouble(), consumed.first.toString()))
                i += consumed.second
                continue
            }

            res.add(words[i])
            i += 1
        }

        return combineFractions(res)
    }

    /**
     * Parses a sequence of number words starting at [start], stopping as soon as the next word
     * could not be part of the same number (e.g. "пять пять" is two numbers).
     * @return the parsed value and the number of words consumed, or null if there is no number
     */
    private fun parseNumberWords(words: List<Tok.Word>, start: Int): Pair<Long, Int>? {
        var total = 0L
        var group = 0L
        var lastScale = Long.MAX_VALUE
        var lastKind: Kind? = null
        var i = start

        while (i < words.size) {
            val w = NUM_WORDS[words[i].norm] ?: break
            val canAppend = when {
                lastKind == Kind.ZERO -> false
                w.kind == Kind.ZERO -> lastKind == null
                w.kind == Kind.UNIT -> group % 10 == 0L && (group % 100 == 0L || group % 100 >= 20)
                w.kind == Kind.TEEN || w.kind == Kind.TEN -> group % 100 == 0L
                w.kind == Kind.HUNDRED -> group == 0L
                else /* SCALE */ -> w.value < lastScale && (group != 0L || lastKind == null)
            }
            if (!canAppend) {
                break
            }

            if (w.kind == Kind.SCALE) {
                total += (if (group == 0L) 1 else group) * w.value
                group = 0
                lastScale = w.value
            } else {
                group += w.value
            }
            lastKind = w.kind
            i += 1
        }

        return if (i == start) null else Pair(total + group, i - start)
    }

    private fun digitsOf(tok: Tok?): String? {
        return (tok as? Tok.Num)?.let { num ->
            num.digits?.takeIf { it.all(Char::isDigit) }
        }
    }

    /** Handles "с половиной", "две целых пять десятых" and "два запятая ноль пять" */
    private fun combineFractions(tokens: List<Tok>): List<Tok> {
        val res = ArrayList<Tok>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok !is Tok.Num) {
                res.add(tok)
                i += 1
                continue
            }

            val w1 = (tokens.getOrNull(i + 1) as? Tok.Word)?.norm
            val w2 = (tokens.getOrNull(i + 2) as? Tok.Word)?.norm

            // "две с половиной"
            if (w1 == "с" && (w2 == "половиной" || w2 == "половинкой")) {
                res.add(Tok.Num(tok.value + 0.5))
                i += 3
                continue
            }

            // "две целых пять десятых" or "две и пять десятых"
            if ((w1 in WHOLE || w1 == "и") && tokens.getOrNull(i + 2) is Tok.Num) {
                val denominator = (tokens.getOrNull(i + 3) as? Tok.Word)?.norm
                    ?.let { FRACTION_DENOMINATOR[it] }
                if (denominator != null) {
                    val numerator = (tokens[i + 2] as Tok.Num).value
                    res.add(Tok.Num(tok.value + numerator / denominator))
                    i += 4
                    continue
                }
            }

            // "два запятая ноль пять"
            if (w1 in DECIMAL_SEPARATOR && tok.value == floor(tok.value)) {
                val decimals = StringBuilder()
                var j = i + 2
                while (j < tokens.size) {
                    val digits = digitsOf(tokens[j]) ?: break
                    decimals.append(digits)
                    j += 1
                }
                if (decimals.isNotEmpty()) {
                    res.add(Tok.Num("${tok.value.toLong()}.$decimals".toDouble()))
                    i = j
                    continue
                }
            }

            res.add(tok)
            i += 1
        }
        return res
    }

    /**
     * Splits [text] into a list of [String]s and [Double]s, where numbers (written either with
     * words or with digits) are converted to [Double]. Consecutive words are joined with spaces.
     */
    fun parseMixed(text: String): List<Any> {
        val res = ArrayList<Any>()
        val currentText = StringBuilder()
        for (tok in tokenize(text)) {
            when (tok) {
                is Tok.Word -> {
                    if (currentText.isNotEmpty()) {
                        currentText.append(' ')
                    }
                    currentText.append(tok.original)
                }
                is Tok.Num -> {
                    if (currentText.isNotEmpty()) {
                        res.add(currentText.toString())
                        currentText.clear()
                    }
                    res.add(tok.value)
                }
                is Tok.Dur -> {} // not produced by tokenize()
            }
        }
        if (currentText.isNotEmpty()) {
            res.add(currentText.toString())
        }
        return res
    }

    private enum class TimeUnit(val seconds: Double) { SECOND(1.0), MINUTE(60.0), HOUR(3600.0) }

    private val UNIT_WORDS: Map<String, TimeUnit> = mapOf(
        "секунда" to TimeUnit.SECOND, "секунды" to TimeUnit.SECOND, "секунд" to TimeUnit.SECOND,
        "секунду" to TimeUnit.SECOND, "секундой" to TimeUnit.SECOND, "секундам" to TimeUnit.SECOND,
        "секундочку" to TimeUnit.SECOND, "секундочки" to TimeUnit.SECOND,
        "сек" to TimeUnit.SECOND, "секи" to TimeUnit.SECOND,
        "минута" to TimeUnit.MINUTE, "минуты" to TimeUnit.MINUTE, "минут" to TimeUnit.MINUTE,
        "минуту" to TimeUnit.MINUTE, "минутой" to TimeUnit.MINUTE, "минутам" to TimeUnit.MINUTE,
        "минутку" to TimeUnit.MINUTE, "минутки" to TimeUnit.MINUTE, "минуток" to TimeUnit.MINUTE,
        "минуточку" to TimeUnit.MINUTE, "мин" to TimeUnit.MINUTE,
        "час" to TimeUnit.HOUR, "часа" to TimeUnit.HOUR, "часов" to TimeUnit.HOUR,
        "часу" to TimeUnit.HOUR, "часом" to TimeUnit.HOUR, "часам" to TimeUnit.HOUR,
        "часик" to TimeUnit.HOUR, "часика" to TimeUnit.HOUR, "часиков" to TimeUnit.HOUR,
        "часок" to TimeUnit.HOUR,
    )

    /** Unit words that mean "one unit" even without a number, e.g. "таймер на минуту" */
    private val SINGULAR_UNIT_WORDS = setOf(
        "секунда", "секунду", "секундочку", "минута", "минуту", "минутку", "минуточку",
        "час", "часик", "часок",
    )

    /** Words that are a whole duration on their own */
    private val SPECIAL_DURATIONS: Map<String, Double> = mapOf(
        "полчаса" to 1800.0, "получаса" to 1800.0, "полчасика" to 1800.0,
        "полминуты" to 30.0, "полминутки" to 30.0,
        "полторачаса" to 5400.0,
    )

    private fun tokenizeDurations(text: String): List<Tok> {
        val tokens = tokenize(text)
        val res = ArrayList<Tok>()

        for (tok in tokens) {
            if (tok !is Tok.Word) {
                res.add(tok)
                continue
            }

            val special = SPECIAL_DURATIONS[tok.norm]
            if (special != null) {
                res.add(Tok.Dur(special))
                continue
            }

            val unit = UNIT_WORDS[tok.norm]
            if (unit == null) {
                res.add(tok)
                continue
            }

            val prev = res.lastOrNull()
            if (prev is Tok.Num) {
                res.removeAt(res.size - 1)
                res.add(Tok.Dur(prev.value * unit.seconds))
            } else if (prev is Tok.Word && prev.norm == "пол") {
                res.removeAt(res.size - 1)
                res.add(Tok.Dur(0.5 * unit.seconds))
            } else if (tok.norm in SINGULAR_UNIT_WORDS) {
                res.add(Tok.Dur(unit.seconds))
            } else {
                res.add(tok)
            }
        }

        // merge consecutive durations, e.g. "две минуты и тридцать секунд" -> one duration
        val merged = ArrayList<Tok>()
        var i = 0
        while (i < res.size) {
            val tok = res[i]
            val prev = merged.lastOrNull()
            if (tok is Tok.Dur && prev is Tok.Dur) {
                merged[merged.size - 1] = Tok.Dur(prev.seconds + tok.seconds)
            } else if (tok is Tok.Word && tok.norm == "и" && prev is Tok.Dur
                && res.getOrNull(i + 1) is Tok.Dur) {
                // skip "и" between two durations
            } else {
                merged.add(tok)
            }
            i += 1
        }
        return merged
    }

    private fun englishDuration(seconds: Double): String {
        var remaining = seconds.roundToLong().coerceAtLeast(1)
        val hours = remaining / 3600
        remaining %= 3600
        val minutes = remaining / 60
        val secs = remaining % 60

        val parts = ArrayList<String>()
        if (hours > 0) parts.add(if (hours == 1L) "1 hour" else "$hours hours")
        if (minutes > 0) parts.add(if (minutes == 1L) "1 minute" else "$minutes minutes")
        if (secs > 0) parts.add(if (secs == 1L) "1 second" else "$secs seconds")
        return parts.joinToString(" ")
    }

    /**
     * Replaces Russian durations in [text] with English ones that the English dicio-numbers parser
     * can understand, and numbers with digits. All other words are kept unchanged.
     * E.g. "запусти таймер на две минуты тридцать секунд" -> "запусти таймер на 2 minutes 30
     * seconds".
     */
    fun normalizeDurations(text: String): String {
        return tokenizeDurations(text).joinToString(" ") { tok ->
            when (tok) {
                is Tok.Word -> tok.original
                is Tok.Num -> formatNumber(tok.value, decimalSeparator = '.')
                is Tok.Dur -> englishDuration(tok.seconds)
            }
        }
    }

    /**
     * @return the number of seconds of the first duration found in [text], or null if there is no
     * duration in [text]
     */
    fun parseDurationSeconds(text: String): Long? {
        return tokenizeDurations(text)
            .firstNotNullOfOrNull { (it as? Tok.Dur)?.seconds }
            ?.roundToLong()
            ?.takeIf { it > 0 }
    }

    /**
     * Formats a number as digits, e.g. 5.0 -> "5", 2.5 -> "2,5", 1/3 -> "0,33".
     */
    fun formatNumber(value: Double, decimalSeparator: Char = ','): String {
        if (value.isNaN() || value.isInfinite()) {
            return value.toString()
        }
        val rounded = Math.round(value * 100) / 100.0
        if (rounded == floor(rounded) && abs(rounded) < 1e15) {
            return rounded.toLong().toString()
        }
        return String.format(java.util.Locale.ROOT, "%.2f", rounded)
            .trimEnd('0')
            .trimEnd('.')
            .replace('.', decimalSeparator)
    }

    /**
     * Chooses the right plural form for [n], e.g. 1 минута, 2 минуты, 5 минут, 21 минута.
     */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val n100 = abs(n) % 100
        val n10 = abs(n) % 10
        return when {
            n10 == 1L && n100 != 11L -> one
            n10 in 2L..4L && n100 !in 12L..14L -> few
            else -> many
        }
    }

    /**
     * Formats a duration in Russian.
     * @param speech if true, returns e.g. "2 минуты 30 секунд" (accusative case, to be used
     * after "через" or "на"); if false, returns e.g. "2:30" or "1:02:03"
     */
    fun formatDuration(milliseconds: Long, speech: Boolean): String {
        val totalSeconds = if (speech) {
            (abs(milliseconds) + 500) / 1000
        } else {
            abs(milliseconds) / 1000
        }
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        if (!speech) {
            val sign = if (milliseconds < 0) "-" else ""
            return if (hours > 0) {
                String.format(java.util.Locale.ROOT, "%s%d:%02d:%02d", sign, hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.ROOT, "%s%d:%02d", sign, minutes, seconds)
            }
        }

        val parts = ArrayList<String>()
        if (hours > 0) {
            parts.add("$hours ${plural(hours, "час", "часа", "часов")}")
        }
        if (minutes > 0) {
            parts.add("$minutes ${plural(minutes, "минуту", "минуты", "минут")}")
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add("$seconds ${plural(seconds, "секунду", "секунды", "секунд")}")
        }
        return parts.joinToString(" ")
    }
}
