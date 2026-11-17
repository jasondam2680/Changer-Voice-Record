package com.jasondam.voicematchstudio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln

class MainActivity : AppCompatActivity() {

    private val sampleRate = 44100
    private var targetFemaleF0 = 235.0f
    private var userMeasuredF0 = 122.0f
    private var semitoneShift = 9.5f
    private var formantBoost = 0.32f

    private var recordedSamples = FloatArray(0)
    private var convertedSamples = FloatArray(0)
    private var isRecording = false
    private var recordingJob: Job? = null

    private lateinit var tvVideoStatus: TextView
    private lateinit var tvUserPitchStatus: TextView
    private lateinit var tvShiftInfo: TextView
    private lateinit var seekPitch: SeekBar
    private lateinit var seekFormant: SeekBar
    private lateinit var btnRecord: Button
    private lateinit var btnPlayOrig: Button
    private lateinit var btnPlayFemale: Button
    private lateinit var btnSaveWav: Button

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            analyzeTrainingVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupProgrammaticUi()
        checkMicPermission()
    }

    private fun setupProgrammaticUi() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF090D16.toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "VoiceMatch DSP Studio"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Trích xuất tông giọng nữ từ Video Clip & chuyển đổi giọng thu âm"
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 8, 0, 36)
        }

        // BƯỚC 1: Chọn Video huấn luyện
        val btnPickVideo = Button(this).apply {
            text = "1. Chọn Video Clip Giọng Nữ Làm Mẫu"
            setBackgroundColor(0xFFDB2777.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }
        tvVideoStatus = TextView(this).apply {
            text = "Hồ sơ hiện tại: Giọng Nữ Mặc Định (F0 = 235.0 Hz)"
            textSize = 13f
            setTextColor(0xFFF472B6.toInt())
            setPadding(0, 16, 0, 36)
        }

        // BƯỚC 2: Ghi âm giọng nói
        btnRecord = Button(this).apply {
            text = "2. Bắt Đầu Ghi Âm Giọng Của Bạn"
            setBackgroundColor(0xFF0284C7.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isRecording) stopMicRecording() else startMicRecording()
            }
        }
        tvUserPitchStatus = TextView(this).apply {
            text = "Trạng thái: Chưa có bản thu âm"
            textSize = 13f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, 16, 0, 32)
        }

        // Thanh trượt chỉnh Pitch & Formant
        tvShiftInfo = TextView(this).apply {
            text = "Độ dịch tông (Pitch Shift): +9.5 Semitones | Formant Nữ: +32%"
            textSize = 13f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, 8, 0, 12)
        }

        seekPitch = SeekBar(this).apply {
            max = 160 // 0.0 -> 16.0 semitones
            progress = 95
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    semitoneShift = p / 10.0f
                    updateParameterLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { reprocessConvertedVoice() }
            })
        }

        seekFormant = SeekBar(this).apply {
            max = 60
            progress = 32
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    formantBoost = p / 100.0f
                    updateParameterLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { reprocessConvertedVoice() }
            })
        }

        // BƯỚC 3: Phát giọng gốc & Phát giọng đã chuyển sang tông nữ
        btnPlayOrig = Button(this).apply {
            text = "▶ Nghe Lại Giọng Gốc"
            isEnabled = false
            setOnClickListener { playPcmBuffer(recordedSamples) }
        }

        btnPlayFemale = Button(this).apply {
            text = "▶ Phát Giọng Đã Chuyển Sang Tông Nữ"
            setBackgroundColor(0xFFEC4899.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            isEnabled = false
            setOnClickListener {
                reprocessConvertedVoice()
                playPcmBuffer(convertedSamples)
            }
        }

        btnSaveWav = Button(this).apply {
            text = "⬇ Lưu File WAV Giọng Nữ"
            isEnabled = false
            setOnClickListener { exportWavToStorage() }
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(btnPickVideo)
        container.addView(tvVideoStatus)
        container.addView(btnRecord)
        container.addView(tvUserPitchStatus)
        container.addView(tvShiftInfo)
        container.addView(seekPitch)
        container.addView(seekFormant)
        container.addView(btnPlayOrig)
        container.addView(btnPlayFemale)
        container.addView(btnSaveWav)

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun updateParameterLabel() {
        val fmPct = (formantBoost * 100).toInt()
        tvShiftInfo.text = "Độ dịch tông: +${"%.1f".format(semitoneShift)} ST (${userMeasuredF0.toInt()}Hz → ${targetFemaleF0.toInt()}Hz) | Formant: +$fmPct%"
    }

    private fun analyzeTrainingVideo(uri: Uri) {
        tvVideoStatus.text = "Đang tách âm thanh & đo tần số F0 giọng nữ từ Video..."
        lifecycleScope.launch(Dispatchers.IO) {
            val profile = VideoPitchExtractor.extractProfileFromVideo(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                targetFemaleF0 = profile.medianF0Hz
                tvVideoStatus.text = "Đã học từ Video! Tông giọng nữ mục tiêu: ${"%.1f".format(targetFemaleF0)} Hz"
                autoMatchSemitones()
                reprocessConvertedVoice()
            }
        }
    }

    private fun autoMatchSemitones() {
        val ratio = (targetFemaleF0 / userMeasuredF0.coerceAtLeast(75f)).toDouble()
        val calcSt = (12.0 * ln(ratio) / ln(2.0)).toFloat().coerceIn(0f, 16f)
        semitoneShift = calcSt
        seekPitch.progress = (calcSt * 10).toInt()
        updateParameterLabel()
    }

    private fun startMicRecording() {
        if (!checkMicPermission()) return
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2
        )

        val tempSamples = ArrayList<Float>(sampleRate * 10)
        isRecording = true
        btnRecord.text = "⏹ Dừng Ghi Âm"
        tvUserPitchStatus.text = "Đang thu âm giọng nói của bạn..."

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(1024)
            recorder.startRecording()
            while (isActive && isRecording) {
                val read = recorder.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        tempSamples.add(shortBuf[i] / 32768.0f)
                    }
                }
            }
            recorder.stop()
            recorder.release()

            val rawArray = tempSamples.toFloatArray()
            val detectedF0 = VideoPitchExtractor.estimateMedianF0(rawArray, sampleRate)

            withContext(Dispatchers.Main) {
                recordedSamples = rawArray
                userMeasuredF0 = detectedF0.coerceIn(80f, 240f)
                val durSec = rawArray.size.toFloat() / sampleRate
                tvUserPitchStatus.text = "Đã thu âm ${"%.1f".format(durSec)}s • F0 giọng bạn: ${"%.1f".format(userMeasuredF0)} Hz"
                autoMatchSemitones()
                reprocessConvertedVoice()
                btnPlayOrig.isEnabled = true
                btnPlayFemale.isEnabled = true
                btnSaveWav.isEnabled = true
            }
        }
    }

    private fun stopMicRecording() {
        isRecording = false
        btnRecord.text = "2. Bắt Đầu Ghi Âm Giọng Của Bạn"
    }

    private fun reprocessConvertedVoice() {
        if (recordedSamples.isEmpty()) return
        convertedSamples = VoiceConverterDsp.convertToFemaleTone(
            inputSamples = recordedSamples,
            sampleRate = sampleRate,
            semitoneShift = semitoneShift,
            formantBoost = formantBoost
        )
    }

    private fun playPcmBuffer(samples: FloatArray) {
        if (samples.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val shortData = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(shortData.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(shortData, 0, shortData.size)
            audioTrack.play()
        }
    }

    private fun exportWavToStorage() {
        if (convertedSamples.isEmpty()) return
        val outFile = File(getExternalFilesDir(null), "VoiceMatch_Female_${System.currentTimeMillis()}.wav")
        VoiceConverterDsp.saveAsWavFile(convertedSamples, sampleRate, outFile)
        Toast.makeText(this, "Đã lưu file WAV tại: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun checkMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return false
        }
        return true
    }
}
