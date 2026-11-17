package com.jasondam.voicematchstudio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

object VoiceConverterDsp {

    /**
     * Dịch chuyển cao độ giọng thu âm của người dùng (sourceF0) khớp với tông giọng nữ từ Video (targetF0)
     * bằng thuật toán Dual-Head Hann-Windowed Granular Pitch Shifter (giữ nguyên độ dài câu nói)
     * kết hợp làm sáng âm sắc Formant và khử âm trầm lồng ngực giọng nam.
     */
    fun convertToFemaleTone(
        inputSamples: FloatArray,
        sampleRate: Int,
        semitoneShift: Float,
        formantBoost: Float = 0.32f,
        vibratoDepth: Float = 0.25f
    ): FloatArray {
        val length = inputSamples.size
        val output = FloatArray(length)
        val basePitchRatio = 2.0.pow(semitoneShift / 12.0).toFloat()

        // Cửa sổ hạt 32ms tối ưu cho xung thanh quản giọng nói
        val grainSize = (0.032f * sampleRate).toInt().coerceAtLeast(256)
        var phaseA = 0.0f
        var phaseB = 0.5f

        // Bộ lọc High-pass bậc 1 khử âm ngực nam giới (<185 Hz)
        val rc = 1.0f / (2.0f * PI.toFloat() * 185.0f)
        val dt = 1.0f / sampleRate
        val alphaHp = rc / (rc + dt)
        var prevIn = 0f
        var prevHp = 0f

        for (i in 0 until length) {
            val tSec = i.toFloat() / sampleRate
            val vibMod = 1.0f + (vibratoDepth * 0.012f * sin(2.0f * PI.toFloat() * 5.5f * tSec))
            val instDelta = ((basePitchRatio * vibMod) - 1.0f) / grainSize

            phaseA = (phaseA - instDelta) % 1.0f
            if (phaseA < 0f) phaseA += 1.0f
            phaseB = (phaseA + 0.5f) % 1.0f

            val readIdxA = i - (phaseA * grainSize)
            val readIdxB = i - (phaseB * grainSize)

            val winA = 0.5f * (1.0f - cos(2.0f * PI.toFloat() * phaseA))
            val winB = 0.5f * (1.0f - cos(2.0f * PI.toFloat() * phaseB))

            val sampleA = interpolateSample(inputSamples, readIdxA)
            val sampleB = interpolateSample(inputSamples, readIdxB)

            val rawShifted = sampleA * winA + sampleB * winB

            // Lọc bỏ cộng hưởng ngực trầm
            val hpSample = alphaHp * (prevHp + rawShifted - prevIn)
            prevIn = rawShifted
            prevHp = hpSample

            // Kích thích hài bậc cao giả lập Formant giọng nữ sáng
            val excited = tanh((hpSample * (1.25f + formantBoost * 0.85f)).toDouble()).toFloat()
            output[i] = (hpSample * 0.68f + excited * 0.32f).coerceIn(-0.98f, 0.98f)
        }

        return output
    }

    private fun interpolateSample(buffer: FloatArray, index: Float): Float {
        if (index < 0f || index >= buffer.size - 1) return 0f
        val i0 = floor(index).toInt()
        val frac = index - i0
        return buffer[i0] * (1.0f - frac) + buffer[i0 + 1] * frac
    }

    fun saveAsWavFile(samples: FloatArray, sampleRate: Int, outFile: File) {
        val byteData = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        byteData.put("RIFF".toByteArray())
        byteData.putInt(36 + samples.size * 2)
        byteData.put("WAVE".toByteArray())
        byteData.put("fmt ".toByteArray())
        byteData.putInt(16)
        byteData.putShort(1) // PCM
        byteData.putShort(1) // Mono
        byteData.putInt(sampleRate)
        byteData.putInt(sampleRate * 2)
        byteData.putShort(2)
        byteData.putShort(16)
        byteData.put("data".toByteArray())
        byteData.putInt(samples.size * 2)

        for (s in samples) {
            val clamped = s.coerceIn(-1.0f, 1.0f)
            val pcm16 = (clamped * 32767.0f).toInt().toShort()
            byteData.putShort(pcm16)
        }

        FileOutputStream(outFile).use { fos ->
            fos.write(byteData.array())
        }
    }
}
