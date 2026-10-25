package org.stypox.dicio.io.wake.oww

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.io.IOException
import kotlin.math.abs

class OpenWakeWordDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : WakeDevice {
    private val _state: MutableStateFlow<WakeState>
    override val state: StateFlow<WakeState>

    private val cacheDir: File = appContext.cacheDir
    private val owwFolder = File(appContext.filesDir, "openWakeWord")
    private val melFile = FileToDownload(MEL_URL, File(owwFolder, "melspectrogram.tflite"))
    private val embFile = FileToDownload(EMB_URL, File(owwFolder, "embedding.tflite"))
    private val wakeFile = FileToDownload(WAKE_URL, File(owwFolder, "wake.tflite"))
    private val userWakeFile = userWakeFile(appContext)
    private val userWakeFileExists = userWakeFile.exists()
    private val allModelFiles =
        // wakeFile is not needed if we want to use the userWakeFile instead
        if (userWakeFileExists) listOf(melFile, embFile)
        else listOf(melFile, embFile, wakeFile)

    private val audio = FloatArray(OwwModel.MEL_INPUT_COUNT)
    private var model: OwwModel? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        _state = if (allModelFiles.any(FileToDownload::needsToBeDownloaded)) {
            MutableStateFlow(WakeState.NotDownloaded)
        } else {
            MutableStateFlow(WakeState.NotLoaded)
        }
        state = _state
    }

    override fun download() {
        _state.value = WakeState.Downloading(Progress.UNKNOWN)

        scope.launch {
            try {
                owwFolder.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = allModelFiles,
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress ->
                    _state.value = WakeState.Downloading(progress)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Can't download OpenWakeWord model", e)
                _state.value = WakeState.ErrorDownloading(e)
                return@launch
            }

            _state.value = WakeState.NotLoaded
        }
    }

    /** the recent peak audio level, used to amplify quiet audio */
    private var smoothedPeak = 0.0f

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (audio16bitPcm.size != OwwModel.MEL_INPUT_COUNT) {
            throw IllegalArgumentException(
                "OwwModel can only process audio frames of ${OwwModel.MEL_INPUT_COUNT} samples"
            )
        }

        if (model == null) {
            if (_state.value.let { it != WakeState.NotLoaded && it !is WakeState.ErrorLoading }) {
                throw IOException("Model has not been downloaded yet")
            }

            try {
                _state.value = WakeState.Loading
                model = OwwModel(
                    melFile.file,
                    embFile.file,
                    if (userWakeFileExists) userWakeFile else wakeFile.file,
                )
                _state.value = WakeState.Loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load model", t)
                _state.value = WakeState.ErrorLoading(t)
                throw t
            }
        }

        var framePeak = 0.0f
        for (i in 0..<OwwModel.MEL_INPUT_COUNT) {
            audio[i] = audio16bitPcm[i].toFloat() / 32768.0f
            framePeak = maxOf(framePeak, abs(audio[i]))
        }

        // The microphone is recorded without automatic gain control, so a normal voice from a
        // distance can be too quiet for the model. Slowly track the recent peak level and amplify
        // quiet audio (up to MAX_GAIN times), so the user does not need to shout.
        smoothedPeak = maxOf(framePeak, smoothedPeak * PEAK_DECAY_PER_FRAME)
        val gain = (TARGET_PEAK / maxOf(smoothedPeak, 1e-4f)).coerceIn(1.0f, MAX_GAIN)
        if (gain > 1.0f) {
            for (i in 0..<OwwModel.MEL_INPUT_COUNT) {
                audio[i] = (audio[i] * gain).coerceIn(-1.0f, 1.0f)
            }
        }

        return model!!.processFrame(audio) > DETECTION_THRESHOLD
    }

    override fun frameSize(): Int {
        return OwwModel.MEL_INPUT_COUNT
    }

    override fun isOccupyingResources(): Boolean = model != null

    override fun destroy() {
        model?.close()
        model = null
        scope.cancel()
    }

    override fun isHeyDicio(): Boolean = !userWakeFileExists

    companion object {
        val TAG = OpenWakeWordDevice::class.simpleName

        /** openWakeWord models are trained to work well with a threshold of 0.5 (was 0.8) */
        private const val DETECTION_THRESHOLD = 0.5f
        /** quiet audio is amplified so that its peak reaches about this level */
        private const val TARGET_PEAK = 0.3f
        /** never amplify more than this, to avoid amplifying background noise too much */
        private const val MAX_GAIN = 3.0f
        /** each 80ms frame the tracked peak decays a little, so it halves in about 10 seconds */
        private const val PEAK_DECAY_PER_FRAME = 0.995f
        const val MEL_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite"
        const val EMB_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite"
        const val WAKE_URL = "https://github.com/Stypox/dicio-android/releases/download/v2.0/hey_dicio_v6.0.tflite"

        private fun userWakeFile(context: Context) =
            File(context.filesDir, "openWakeWord/userwake.tflite")

        suspend fun addUserWakeFile(context: Context, source: Uri) {
            // Use a partial file to ensure atomicity
            val userWakeFile = userWakeFile(context)
            withContext(Dispatchers.IO) {
                val partialFile = File.createTempFile(userWakeFile.name, ".part", context.cacheDir)
                val inputStream = context.contentResolver.openInputStream(source)
                if (inputStream != null) {
                    inputStream.use { source ->
                        partialFile.outputStream().use {
                            source.copyTo(it)
                        }
                    }

                    // Remove the previous file if it already exists
                    userWakeFile.delete()
                    userWakeFile.parentFile?.mkdirs()
                    val renameOk = partialFile.renameTo(userWakeFile)
                    if (!renameOk) {
                        throw IOException("Cannot rename partial file $partialFile to actual file $userWakeFile")
                    }
                }
            }
        }

        suspend fun removeUserWakeFile(context: Context) {
            withContext(Dispatchers.IO) {
                userWakeFile(context).delete()
            }
        }
    }
}
