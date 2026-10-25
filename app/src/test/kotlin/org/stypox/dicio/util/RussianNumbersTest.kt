package org.stypox.dicio.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RussianNumbersTest : StringSpec({
    "parseMixed simple numbers" {
        RussianNumbers.parseMixed("пять плюс пять") shouldBe listOf(5.0, "плюс", 5.0)
        RussianNumbers.parseMixed("сто двадцать три") shouldBe listOf(123.0)
        RussianNumbers.parseMixed("две тысячи двадцать шесть") shouldBe listOf(2026.0)
        RussianNumbers.parseMixed("тысяча") shouldBe listOf(1000.0)
        RussianNumbers.parseMixed("ноль") shouldBe listOf(0.0)
        RussianNumbers.parseMixed("трёх") shouldBe listOf(3.0)
    }

    "parseMixed separates numbers that cannot be joined" {
        RussianNumbers.parseMixed("пять пять") shouldBe listOf(5.0, 5.0)
        RussianNumbers.parseMixed("пятнадцать пять") shouldBe listOf(15.0, 5.0)
    }

    "parseMixed fractions and digits" {
        RussianNumbers.parseMixed("полторы тысячи") shouldBe listOf(1500.0)
        RussianNumbers.parseMixed("две с половиной") shouldBe listOf(2.5)
        RussianNumbers.parseMixed("две целых пять десятых") shouldBe listOf(2.5)
        RussianNumbers.parseMixed("два запятая ноль пять") shouldBe listOf(2.05)
        RussianNumbers.parseMixed("12 плюс 3,5") shouldBe listOf(12.0, "плюс", 3.5)
    }

    "parseMixed calculator sentence" {
        RussianNumbers.parseMixed("пять умножить на четыре минус миллион") shouldBe
                listOf(5.0, "умножить на", 4.0, "минус", 1_000_000.0)
    }

    "normalizeDurations" {
        RussianNumbers.normalizeDurations("запусти таймер на две минуты") shouldBe
                "запусти таймер на 2 minutes"
        RussianNumbers.normalizeDurations("поставь таймер на минуту") shouldBe
                "поставь таймер на 1 minute"
        RussianNumbers.normalizeDurations("таймер на две минуты и тридцать секунд") shouldBe
                "таймер на 2 minutes 30 seconds"
        RussianNumbers.normalizeDurations("таймер на полторы минуты") shouldBe
                "таймер на 1 minute 30 seconds"
        RussianNumbers.normalizeDurations("таймер на полчаса") shouldBe "таймер на 30 minutes"
        RussianNumbers.normalizeDurations("таймер на два с половиной часа") shouldBe
                "таймер на 2 hours 30 minutes"
    }

    "parseDurationSeconds" {
        RussianNumbers.parseDurationSeconds("пять минут") shouldBe 300L
        RussianNumbers.parseDurationSeconds("на минутку") shouldBe 60L
        RussianNumbers.parseDurationSeconds("не знаю") shouldBe null
    }

    "formatDuration" {
        RussianNumbers.formatDuration(60_000, true) shouldBe "1 минуту"
        RussianNumbers.formatDuration(150_000, true) shouldBe "2 минуты 30 секунд"
        RussianNumbers.formatDuration(21 * 60_000L, true) shouldBe "21 минуту"
        RussianNumbers.formatDuration(11 * 60_000L, true) shouldBe "11 минут"
        RussianNumbers.formatDuration(3_900_000, true) shouldBe "1 час 5 минут"
        RussianNumbers.formatDuration(150_000, false) shouldBe "2:30"
        RussianNumbers.formatDuration(3_723_000, false) shouldBe "1:02:03"
    }

    "formatNumber" {
        RussianNumbers.formatNumber(10.0) shouldBe "10"
        RussianNumbers.formatNumber(2.5) shouldBe "2,5"
        RussianNumbers.formatNumber(1.0 / 3) shouldBe "0,33"
    }
})
