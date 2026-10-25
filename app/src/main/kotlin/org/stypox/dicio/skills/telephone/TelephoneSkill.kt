package org.stypox.dicio.skills.telephone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Telephone
import org.stypox.dicio.util.SpeakerphoneRequest
import org.stypox.dicio.util.supportsNumbers

class TelephoneSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Telephone>,
    val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : StandardRecognizerSkill<Telephone>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Telephone): SkillOutput {
        val contentResolver = ctx.android.contentResolver
        val (userContactName, speakerphone) = SpeakerphoneRequest.extract(
            when (inputData) {
                is Telephone.Dial -> inputData.who?.trim { it <= ' ' } ?: ""
            }
        )
        val contacts = Contact.getFilteredSortedContacts(contentResolver, userContactName)
        val validContacts = ArrayList<Pair<String, List<String>>>()

        var i = 0
        while (validContacts.size < 5 && i < contacts.size) {
            val contact = contacts[i]
            val numbers = contact.getNumbers(contentResolver)
            if (numbers.isEmpty()) {
                ++i
                continue
            }
            if (validContacts.isEmpty()
                && contact.distance < 3
                && numbers.size == 1 // it has just one number
                && (contacts.size <= i + 1 // the next contact has a distance higher by 3+
                        || contacts[i + 1].distance - 2 > contact.distance
                        // or the words in the name match clearly better than the next contact,
                        // e.g. "саше" with contacts "Саша" and "Саша Самолет"
                        || (contact.wordScore > 0
                                && contacts[i + 1].wordScore < contact.wordScore))
            ) {
                // very close match with just one number and without distance ties: call it directly
                return ConfirmCallOutput(contact.name, numbers[0], yesNoData, speakerphone)
            }
            validContacts.add(Pair(contact.name, numbers))
            ++i
        }

        if (validContacts.size == 1 // there is exactly one valid contact and ...
            // ... either it has exactly one number, or we would be forced (because no number parser
            // is available) to use ContactChooserName, which only uses the first phone number
            // anyway
            && (validContacts[0].second.size == 1 || !ctx.supportsNumbers())
        ) {
            // not a good enough match, but since we have only this, call it directly
            val contact = validContacts[0]
            return ConfirmCallOutput(contact.first, contact.second[0], yesNoData, speakerphone)
        }

        // this point will not be reached if a very close match was found
        return TelephoneOutput(validContacts, yesNoData, speakerphone)
    }

    companion object {
        private val TAG = TelephoneSkill::class.simpleName

        fun call(context: Context, number: String?, speakerphone: Boolean = false) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (speakerphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // ask the phone app to start the call with the speakerphone turned on
                callIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            context.startActivity(callIntent)
            if (speakerphone) {
                turnOnSpeakerphoneLater(context)
            }
        }

        /**
         * Some phone apps ignore [TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE], so also try
         * to turn on the speakerphone a few times while the call is starting.
         */
        private fun turnOnSpeakerphoneLater(context: Context) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val handler = Handler(Looper.getMainLooper())
            for (delayMillis in listOf(1500L, 3000L, 5000L)) {
                handler.postDelayed({
                    try {
                        if (audioManager.mode != AudioManager.MODE_NORMAL) {
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = true
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Could not turn on speakerphone", e)
                    }
                }, delayMillis)
            }
        }
    }
}
