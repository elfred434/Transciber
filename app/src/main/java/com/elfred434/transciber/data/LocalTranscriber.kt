package com.elfred434.transciber.data

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class LocalTranscriber @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun transcribe(uri: Uri): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw IOException("La transcription hors ligne nécessite Android 12 ou une version ultérieure.")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IOException("Le module de reconnaissance hors ligne n'est pas installé sur cet appareil.")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pcm = withContext(Dispatchers.IO) { decodeToPcm(uri) }
            recognizePcm(pcm)
        } else {
            recognizeLegacyAudioUri(uri)
        }
    }

    private suspend fun recognizePcm(audio: PcmAudio): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val pipe = ParcelFileDescriptor.createPipe()
                val input = pipe[0]
                val output = pipe[1]
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                var writer: Job? = null
                var latestPartial = ""
                var finished = false

                fun cleanup() {
                    writer?.cancel()
                    runCatching { input.close() }
                    runCatching { output.close() }
                    runCatching { recognizer.destroy() }
                }

                fun finish(result: Result<String>) {
                    if (finished) return
                    finished = true
                    cleanup()
                    result.fold(
                        onSuccess = { text -> continuation.resume(text) },
                        onFailure = { error -> continuation.resumeWithException(error) }
                    )
                }

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        val message = if (latestPartial.isNotBlank()) {
                            null
                        } else {
                            "La reconnaissance hors ligne a échoué (code $error). Vérifiez que le modèle de langue est installé."
                        }
                        if (message == null) finish(Result.success(latestPartial))
                        else finish(Result.failure(IOException(message)))
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        finish(
                            if (text.isNotBlank()) Result.success(text)
                            else Result.failure(IOException("Aucune parole détectée dans ce vocal."))
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        latestPartial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                }

                recognizer.setRecognitionListener(listener)
                val intent = recognitionIntent().apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audio.channels)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
                }

                continuation.invokeOnCancellation {
                    cleanup()
                }

                try {
                    recognizer.startListening(intent)
                    writer = CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                                stream.write(audio.bytes)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    runCatching { output.close() }
                    finish(Result.failure(error))
                }
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun recognizeLegacyAudioUri(uri: Uri): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                var finished = false
                var latestPartial = ""

                fun cleanup() {
                    runCatching { recognizer.destroy() }
                }

                fun finish(result: Result<String>) {
                    if (finished) return
                    finished = true
                    cleanup()
                    result.fold(
                        onSuccess = { text -> continuation.resume(text) },
                        onFailure = { error -> continuation.resumeWithException(error) }
                    )
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        if (latestPartial.isNotBlank()) finish(Result.success(latestPartial))
                        else finish(Result.failure(IOException("La reconnaissance hors ligne a échoué (code $error).")))
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        finish(
                            if (text.isNotBlank()) Result.success(text)
                            else Result.failure(IOException("Aucune parole détectée dans ce vocal."))
                        )
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        latestPartial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = recognitionIntent().apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_INJECT_SOURCE, uri)
                }
                continuation.invokeOnCancellation { cleanup() }
                try {
                    recognizer.startListening(intent)
                } catch (error: Throwable) {
                    finish(Result.failure(error))
                }
            }
        }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun decodeToPcm(uri: Uri): PcmAudio {
        val resolver = context.contentResolver
        val source = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Impossible d'ouvrir le fichier audio.")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.fileDescriptor)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    trackFormat = candidate
                    break
                }
            }
            val format = trackFormat ?: throw IOException("Aucune piste audio lisible n'a été trouvée.")
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Format audio inconnu.")
            val fallbackRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                16_000
            }
            val fallbackChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleRate = fallbackRate
            var channels = fallbackChannels
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Tampon audio indisponible.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decodedFormat = codec.outputFormat
                        if (decodedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (decodedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outputIndex >= 0 -> {
                        codec.getOutputBuffer(outputIndex)?.let { buffer ->
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                buffer.get(bytes)
                                output.write(bytes)
                            }
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val bytes = output.toByteArray()
            if (bytes.isEmpty()) throw IOException("Le fichier audio ne contient aucun échantillon exploitable.")
            return PcmAudio(bytes, sampleRate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            source.close()
        }
    }

    private data class PcmAudio(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )
}
