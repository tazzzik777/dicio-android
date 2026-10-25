package org.stypox.dicio.skills.timer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Timer
import org.stypox.dicio.util.RussianNumbers
import org.stypox.dicio.util.StringUtils
import org.stypox.dicio.util.englishParserFormatter
import org.stypox.dicio.util.useRussianNumbers
import org.stypox.dicio.util.getString
import java.time.Duration

// TODO cleanup this skill and use a service to manage timers
class TimerSkill(
    correspondingSkillInfo: SkillInfo,
    private val timerData: StandardRecognizerData<Timer>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : StandardRecognizerSkill<Timer>(correspondingSkillInfo, timerData) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Timer> {
        if (!ctx.useRussianNumbers()) {
            return super.score(ctx, input)
        }
        // dicio-numbers does not support Russian, so rewrite Russian durations in English (e.g.
        // "таймер на две минуты" -> "таймер на 2 minutes") and parse them with the English parser
        val normalized = RussianNumbers.normalizeDurations(input)
        return timerData.score(MatchHelper(englishParserFormatter, normalized), normalized)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Timer): SkillOutput {
        return when (inputData) {
            is Timer.Set -> {
                if (inputData.duration == null) {
                    TimerOutput.SetAskDuration { setTimer(ctx, it, inputData.name) }
                } else {
                    setTimer(ctx, inputData.duration.toJavaDuration(), inputData.name)
                }
            }
            is Timer.Query -> {
                queryTimer(ctx, inputData.name)
            }
            is Timer.Cancel -> {
                if (inputData.name == null && SET_TIMERS.size > 1) {
                    TimerOutput.ConfirmCancel(yesNoData) { cancelTimer(ctx, null) }
                } else {
                    cancelTimer(ctx, inputData.name)
                }
            }
        }
    }

    private suspend fun setTimer(
        ctx: SkillContext,
        duration: Duration,
        name: String?,
    ): SkillOutput {
        // prefer the phone's clock app, so that the timer is visible and handled by the system
        if (setTimerInClockApp(ctx, duration, name)) {
            return TimerOutput.SetInClockApp(
                formatStringWithName(
                    ctx, name, duration.toMillis(),
                    R.string.skill_timer_set, R.string.skill_timer_set_name
                )
            )
        }

        var ringtone: Ringtone? = null

        val setTimer = withContext(Dispatchers.Main) { SetTimer(
            duration = duration,
            name = name,
            onMillisTickCallback = { milliseconds ->
                if (milliseconds < 0 && ringtone?.isPlaying == false) {
                    ringtone?.play()
                }
            },
            onSecondsTickCallback = { seconds ->
                if (seconds <= 5) {
                    ctx.speechOutputDevice.speak(
                        if (ctx.useRussianNumbers()) {
                            seconds.toString()
                        } else {
                            ctx.parserFormatter!!
                                .pronounceNumber(seconds.toDouble())
                                .get()
                        }
                    )
                }
            },
            onExpiredCallback = { theName ->
                // initialize ringtone when the timer has expired (play will be called right after)
                ringtone = RingtoneManager.getActualDefaultRingtoneUri(
                    ctx.android, RingtoneManager.TYPE_ALARM
                )
                    ?.let {
                        RingtoneManager.getRingtone(ctx.android, it)
                    }
                    ?.also {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // on older API versions it is looped manually in onMillisTickCallback
                            it.isLooping = true
                        }
                        it.play()
                    }

                if (ringtone == null) {
                    // we could not load a ringtone, so we can announce via speech instead
                    ctx.speechOutputDevice.speak(
                        formatStringWithName(
                            ctx.android,
                            theName,
                            R.string.skill_timer_expired,
                            R.string.skill_timer_expired_name
                        )
                    )
                }
            },
            onCancelCallback = { timerToCancel ->
                ringtone?.stop()
                ringtone = null
                // removed from setTimers list
                SET_TIMERS.removeIf { setTimer -> setTimer === timerToCancel }
            }
        ) }

        SET_TIMERS.add(setTimer)

        return TimerOutput.Set(
            duration.toMillis(),
            setTimer.lastTickMillisState,
            name,
        )
    }

    private fun cancelTimer(ctx: SkillContext, name: String?): SkillOutput {
        if (SET_TIMERS.isEmpty() && showTimersInClockApp(ctx)) {
            // timers are probably in the clock app, let the user handle them there
            return TimerOutput.Cancel(ctx.getString(R.string.skill_timer_opening_clock))
        }

        val message: String
        if (SET_TIMERS.isEmpty()) {
            message = ctx.android
                .getString(R.string.skill_timer_no_active)
        } else if (name == null) {
            message = if (SET_TIMERS.size == 1) {
                formatStringWithName(
                    ctx.android,
                    SET_TIMERS[0].name,
                    R.string.skill_timer_canceled,
                    R.string.skill_timer_canceled_name
                )
            } else {
                ctx.getString(R.string.skill_timer_all_canceled)
            }

            // cancel all timers (copying the SET_TIMERS list, since cancel() is going to remove
            // the timer from the SET_TIMERS list, and the for loop would break)
            for (setTimer in SET_TIMERS.toList()) {
                setTimer.cancel()
            }
            if (SET_TIMERS.isNotEmpty()) {
                Log.w(TAG, "Calling cancel() on all timers did not remove them all from the list")
                SET_TIMERS.clear()
            }

        } else {
            val setTimer = getSetTimerWithSimilarName(name)
            if (setTimer == null) {
                message = ctx.android
                    .getString(R.string.skill_timer_no_active_name, name)
            } else {
                message = ctx.android
                    .getString(R.string.skill_timer_canceled_name, setTimer.name)
                setTimer.cancel()
                SET_TIMERS.remove(setTimer)
            }
        }

        return TimerOutput.Cancel(message)
    }

    private fun queryTimer(ctx: SkillContext, name: String?): SkillOutput {
        if (SET_TIMERS.isEmpty() && showTimersInClockApp(ctx)) {
            // timers are probably in the clock app, which can show the remaining time
            return TimerOutput.Query(ctx.getString(R.string.skill_timer_opening_clock))
        }

        val message = if (SET_TIMERS.isEmpty()) {
            ctx.getString(R.string.skill_timer_no_active)
        } else if (name == null) {
            // no name provided by the user: query the last timer, but adapt the message if only one
            val lastTimer = SET_TIMERS[SET_TIMERS.size - 1]
            @StringRes val noNameQueryString: Int = if (SET_TIMERS.size == 1)
                R.string.skill_timer_query
            else
                R.string.skill_timer_query_last

            formatStringWithName(
                ctx,
                lastTimer.name,
                lastTimer.lastTickMillis,
                noNameQueryString,
                R.string.skill_timer_query_name
            )

        } else {
            val setTimer = getSetTimerWithSimilarName(name)
            if (setTimer == null) {
                ctx.getString(R.string.skill_timer_no_active_name, name)
            } else {
                ctx.getString(
                    R.string.skill_timer_query_name, setTimer.name,
                    getFormattedDuration(ctx, setTimer.lastTickMillis, true)
                )
            }
        }

        return TimerOutput.Query(message)
    }

    private fun getSetTimerWithSimilarName(name: String): SetTimer? {
        class Pair(val setTimer: SetTimer, val distance: Int)
        return SET_TIMERS
            .mapNotNull { setTimer: SetTimer ->
                setTimer.name?.let { timerName ->
                    Pair(
                        setTimer,
                        StringUtils.customStringDistance(name, timerName)
                    )
                }
            }
            .filter { pair -> pair.distance < 6 }
            .minByOrNull { pair -> pair.distance }
            ?.setTimer
    }

    /**
     * Asks the phone's clock app to start a timer.
     * @return false if no clock app could handle the request, in which case Dicio's own internal
     * timer should be used instead
     */
    private fun setTimerInClockApp(ctx: SkillContext, duration: Duration, name: String?): Boolean {
        val seconds = duration.seconds
        if (seconds < 1 || seconds > MAX_CLOCK_APP_TIMER_SECONDS) {
            return false // the clock app only accepts timers between 1 second and 24 hours
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            if (!name.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, name)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false) // show the timer in the clock app
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return startActivitySafely(ctx, intent)
    }

    /**
     * Opens the list of timers in the phone's clock app.
     * @return false if this is not possible
     */
    private fun showTimersInClockApp(ctx: SkillContext): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return startActivitySafely(ctx, intent)
    }

    private fun startActivitySafely(ctx: SkillContext, intent: Intent): Boolean {
        return try {
            ctx.android.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No clock app can handle ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start ${intent.action}", e)
            false
        }
    }

    companion object {
        private const val MAX_CLOCK_APP_TIMER_SECONDS = 24 * 60 * 60
        val SET_TIMERS: MutableList<SetTimer> = ArrayList()
        val TAG: String = TimerSkill::class.simpleName!!
    }
}
