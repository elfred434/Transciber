package com.elfred434.transciber.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class LocalTranscriber @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelMutex = Mutex()
    @Volatile private var model: dev.ffmpegkit.whisper.WhisperModel? = null

    suspend fun transcribe(uri: Uri): String = withContext(Dispatchers.IO) {
        val wav = File.createTempFile("transciber-", ".wav", context.cacheDir)
        try {
            val pcm = decodeToPcm(uri)
            writeWhisperWav(pcm, wav)
            val loadedModel = loadModel()
            Whisper.transcribe(
                loadedModel,
                wav.absolutePath,
                WhisperConfig(
                    language = "auto",
                    translate = false,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    printTimestamps = false
                )
            ).text.trim().takeIf { it.isNotBlank() }
                ?: throw IOException("Aucune parole détectée dans ce vocal.")
        } finally {
            wav.delete()
        }
    }

    private suspend fun loadModel(): dev.ffmpegkit.whisper.WhisperModel = modelMutex.withLock {
        model ?: Whisper.loadModelFromAsset(context, MODEL_ASSET).also { model = it }
    }

    private fun writeWhisperWav(pcm: PcmAudio, destination: File) {
        if (pcm.encoding != AudioFormat.ENCODING_PCM_16BIT) {
            throw IOException("Le décodeur audio n'a pas produit du PCM 16 bits.")
        }
        if (pcm.sampleRate <= 0 || pcm.channels <= 0) {
            throw IOException("Paramètres audio invalides.")
        }

        val sourceFrames = pcm.bytes.size / (2 * pcm.channels)
        if (sourceFrames == 0) throw IOException("Le fichier audio est vide.")
        val targetFrames = (sourceFrames.toDouble() * TARGET_SAMPLE_RATE / pcm.sampleRate)
            .roundToInt()
            .coerceAtLeast(1)
        val dataSize = targetFrames * 2

        FileOutputStream(destination).use { output ->
            output.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            writeLeInt(output, 36 + dataSize)
            output.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
            writeLeInt(output, 16)
            writeLeShort(output, 1)
            writeLeShort(output, 1)
            writeLeInt(output, TARGET_SAMPLE_RATE)
            writeLeInt(output, TARGET_SAMPLE_RATE * 2)
            writeLeShort(output, 2)
            writeLeShort(output, 16)
            output.write("data".toByteArray(StandardCharsets.US_ASCII))
            writeLeInt(output, dataSize)

            for (targetFrame in 0 until targetFrames) {
                val sourcePosition = targetFrame.toDouble() * pcm.sampleRate / TARGET_SAMPLE_RATE
                val firstFrame = sourcePosition.toInt().coerceIn(0, sourceFrames - 1)
                val secondFrame = (firstFrame + 1).coerceAtMost(sourceFrames - 1)
                val fraction = sourcePosition - firstFrame
                val sample = (sampleAt(pcm, firstFrame) * (1.0 - fraction) +
                    sampleAt(pcm, secondFrame) * fraction).roundToInt().coerceIn(-32768, 32767)
                writeLeShort(output, sample)
            }
        }
    }

    private fun sampleAt(pcm: PcmAudio, frame: Int): Int {
        var total = 0
        val safeFrame = frame.coerceIn(0, pcm.bytes.size / (2 * pcm.channels) - 1)
        for (channel in 0 until pcm.channels) {
            val offset = (safeFrame * pcm.channels + channel) * 2
            val low = pcm.bytes[offset].toInt() and 0xff
            val high = pcm.bytes[offset + 1].toInt()
            total += (high shl 8) or low
        }
        return total / pcm.channels
    }

    private fun writeLeInt(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
        output.write(value shr 16 and 0xff)
        output.write(value shr 24 and 0xff)
    }

    private fun writeLeShort(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
    }

    private fun decodeToPcm(uri: Uri): PcmAudio {
        val source = context.contentResolver.openFileDescriptor(uri, "r")
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
            var sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
            var channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
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
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decoded = codec.outputFormat
                        sampleRate = decoded.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = decoded.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = decoded.intOrDefault(MediaFormat.KEY_PCM_ENCODING, encoding)
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

            return PcmAudio(output.toByteArray(), sampleRate, channels, encoding)
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
        val channels: Int,
        val encoding: Int
    )

    private companion object {
        const val MODEL_ASSET = "models/ggml-tiny-q5_1.bin"
        const val TARGET_SAMPLE_RATE = 16_000
    }
}

private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default
