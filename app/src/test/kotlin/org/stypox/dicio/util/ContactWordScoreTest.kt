package org.stypox.dicio.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.stypox.dicio.util.StringUtils.contactWordScore

class ContactWordScoreTest : StringSpec({
    "prefers the contact without extra words" {
        contactWordScore("саше", "Саша") shouldBeGreaterThan contactWordScore("саше", "Саша Самолет")
        contactWordScore("сашу", "Саша") shouldBeGreaterThan contactWordScore("сашу", "Саша Самолет")
    }

    "prefers the contact matching all words" {
        contactWordScore("саше самолету", "Саша Самолет") shouldBeGreaterThan
                contactWordScore("саше самолету", "Саша")
    }

    "does not confuse similar short names" {
        contactWordScore("маме", "Мама") shouldBeGreaterThan contactWordScore("маме", "Маша")
        contactWordScore("маше", "Маша") shouldBeGreaterThan contactWordScore("маше", "Мама")
    }
})
