package org.stypox.dicio.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SpeakerphoneRequestTest : StringSpec({
    "Russian" {
        SpeakerphoneRequest.extract("никите по громкой связи") shouldBe Pair("никите", true)
        SpeakerphoneRequest.extract("по громкой связи никите") shouldBe Pair("никите", true)
        SpeakerphoneRequest.extract("маме на громкую") shouldBe Pair("маме", true)
        SpeakerphoneRequest.extract("саше через динамик") shouldBe Pair("саше", true)
        SpeakerphoneRequest.extract("никите") shouldBe Pair("никите", false)
    }

    "English" {
        SpeakerphoneRequest.extract("mom on speaker") shouldBe Pair("mom", true)
        SpeakerphoneRequest.extract("john with the speakerphone") shouldBe Pair("john", true)
        SpeakerphoneRequest.extract("john") shouldBe Pair("john", false)
    }
})
