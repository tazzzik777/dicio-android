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
) :
    // use a low specificity to prefer the index-based contact chooser
    Skill<Pair<String, String>?>(TelephoneInfo, Specificity.LOW) {

    override fun score(
        ctx: SkillContext,
        input: String
    ): Pair<Score, Pair<String, String>?> {
        val trimmedInput = input.trim { it <= ' ' }

        val candidates = contacts
            .map { nameNumberPair ->
                Pair(
                    nameNumberPair,
                    StringUtils.contactStringDistance(trimmedInput, nameNumberPair.first)
                )
            }
            .filter { pair -> pair.second < -7 }
            .sortedBy { pair -> pair.second }

        // if two different contacts match equally well (e.g. the user said only "Саша" and there
        // are "Саша Иванов" and "Саша Петров"), do not just pick the first one
        val isTie = candidates.size >= 2
                && candidates[0].second == candidates[1].second
                && candidates[0].first.first != candidates[1].first.first
        val bestContact = if (isTie) null else candidates.firstOrNull()?.first

        return Pair(
            if (bestContact == null) AlwaysWorstScore else AlwaysBestScore,
            bestContact
        )
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Pair<String, String>?): SkillOutput {
        return inputData?.let {
            ConfirmCallOutput(it.first, it.second, yesNoData)
        }
            // impossible situation
            ?: ConfirmedCallOutput(null)
    }
}
