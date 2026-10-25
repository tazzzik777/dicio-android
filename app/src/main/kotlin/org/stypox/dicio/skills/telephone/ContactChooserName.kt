package org.stypox.dicio.skills.telephone

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.StringUtils

class ContactChooserName internal constructor(
    private val contacts: List<Pair<String, String>>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val speakerphone: Boolean = false,
) :
    // use a low specificity to prefer the index-based contact chooser
    Skill<Pair<String, String>?>(TelephoneInfo, Specificity.LOW) {

    override fun score(
        ctx: SkillContext,
        input: String
    ): Pair<Score, Pair<String, String>?> {
        val trimmedInput = input.trim { it <= ' ' }

        class Candidate(val contact: Pair<String, String>, val wordScore: Int, val distance: Int)

        val candidates = contacts
            .map { nameNumberPair ->
                Candidate(
                    nameNumberPair,
                    StringUtils.contactWordScore(trimmedInput, nameNumberPair.first),
                    StringUtils.contactStringDistance(trimmedInput, nameNumberPair.first),
                )
            }
            .filter { it.distance < -7 || it.wordScore > 0 }
            .sortedWith(compareByDescending<Candidate> { it.wordScore }.thenBy { it.distance })

        // if two different contacts match equally well (e.g. the user said only "Саша" and there
        // are "Саша Иванов" and "Саша Петров"), do not just pick the first one
        val isTie = candidates.size >= 2
                && candidates[0].wordScore == candidates[1].wordScore
                && candidates[0].distance == candidates[1].distance
                && candidates[0].contact.first != candidates[1].contact.first
        val bestContact = if (isTie) null else candidates.firstOrNull()?.contact

        return Pair(
            if (bestContact == null) AlwaysWorstScore else AlwaysBestScore,
            bestContact
        )
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Pair<String, String>?): SkillOutput {
        return inputData?.let {
            ConfirmCallOutput(it.first, it.second, yesNoData, speakerphone)
        }
            // impossible situation
            ?: ConfirmedCallOutput(null)
    }
}
