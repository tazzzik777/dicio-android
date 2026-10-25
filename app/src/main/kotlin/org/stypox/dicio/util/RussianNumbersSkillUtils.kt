package org.stypox.dicio.util

import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import java.util.Locale

/**
 * Whether skills should use [RussianNumbers] instead of [SkillContext.parserFormatter], which is
 * `null` for Russian since dicio-numbers does not support it.
 */
fun SkillContext.useRussianNumbers(): Boolean {
    return parserFormatter == null && sentencesLanguage == "ru"
}

/**
 * Whether skills that need to parse or format numbers can work in the current language, either
 * through dicio-numbers or through [RussianNumbers].
 */
fun SkillContext.supportsNumbers(): Boolean {
    return parserFormatter != null || useRussianNumbers()
}

/**
 * An English [ParserFormatter], used to parse durations after they have been rewritten in English
 * by [RussianNumbers.normalizeDurations].
 */
val englishParserFormatter: ParserFormatter by lazy { ParserFormatter(Locale.ENGLISH) }
