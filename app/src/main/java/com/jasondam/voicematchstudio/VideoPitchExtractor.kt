package com.jasondam.voicematchstudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

data class FemaleVoiceProfile(
    val medianF0Hz: Float,
    val formantRatio: Float,
    val sampleRate: Int
)

object VideoPitchExtractor {

    /**
     * Giải mã trực tiếp track âm thanh từ Video Clip (.mp4, .mkv, .mov) bằng MediaExtractor + MediaCodec
     * và đo tần số cơ bản (F0 trung vị) của giọng nữ trong video bằng thuật toán Autocorrelation.
     */
    fun extractProfileFromVideo(context: Context, videoUri: Uri): FemaleVoiceProfile {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, videoUri, null)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            return FemaleVoiceProfile(235.0f, 1.32f, 44100)
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        val pcmSamples = ArrayList<Float>(sampleRate * 12) // Phân tích tối đa 12 giây đầu của video
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        val maxSamples = sampleRate * 12

        while (!isEOS && pcmSamples.size < maxSamples) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                outBuffer.order(ByteOrder.nativeOrder())
                val shortBuffer = outBuffer.asShortBuffer()
                while (shortBuffer.hasRemaining() && pcmSamples.size < maxSamples) {
                    val sample = shortBuffer.get() / 32768.0f
                    pcmSamples.add(sample)
                    // Nếu stereo, bỏ qua kênh phải để lấy Mono
                    for (c in 1 until channelCount) {
                        if (shortBuffer.hasRemaining()) shortBuffer.get()
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val detectedF0 = estimateMedianF0(pcmSamples.toFloatArray(), sampleRate)
        val clampedFemaleF0 = detectedF0.coerceIn(165.0f, 310.0f)
        val formantRatio = (clampedFemaleF0 / 175.0f).coerceIn(1.15f, 1.48f)

        return FemaleVoiceProfile(
            medianF0Hz = clampedFemaleF0,
            formantRatio = formantRatio,
            sampleRate = sampleRate
        )
    }

    fun estimateMedianF0(samples: FloatArray, sampleRate: Int): Float {
        val frameSize = 2048
        val hop = 1024
        val detectedPitches = ArrayList<Float>()

        var start = 0
        while (start + frameSize < samples.size) {
            var rms = 0f
            for (i in 0 until frameSize) {
                val v = samples[start + i]
                rms += v * v
            }
            rms = sqrt(rms / frameSize)
            if (rms >= 0.015f) {
                val minLag = sampleRate / 400
                val maxLag = sampleRate / 75
                var bestLag = -1
                var bestCorr = 0f

                for (lag in minLag..maxLag) {
                    var sum = 0f
                    for (i in 0 until (frameSize - lag)) {
                        sum += samples[start + i] * samples[start + i + lag]
                    }
                    if (sum > bestCorr) {
                        bestCorr = sum
                        bestLag = lag
                    }
                }
                if (bestLag > 0) {
                    val f0 = sampleRate.toFloat() / bestLag
                    if (f0 in 75.0f..380.0f) {
                        detectedPitches.add(f0)
                    }
                }
            }
            start += hop
        }

        if (detectedPitches.isEmpty()) return 235.0f
        detectedPitches.sort()
        return detectedPitches[detectedPitches.size / 2]
    }
}
