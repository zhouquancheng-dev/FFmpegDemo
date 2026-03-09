package com.example.ffmpegdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FFmpegDemo"
    }

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvCrfValue: TextView
    private lateinit var tvOutputPath: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnPlayVideo: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var cardResult: MaterialCardView
    private lateinit var actvPreset: AutoCompleteTextView
    private lateinit var sliderCrf: Slider
    private lateinit var rgSubtitleMode: RadioGroup

    private var currentSessionId = -1L
    private var isRunning = false
    private var useHwEncoder = false
    private var pendingScroll = false
    private var outputFile: File? = null

    private val presets = arrayOf(
        "ultrafast", "superfast", "veryfast", "faster",
        "fast", "medium", "slow", "slower", "veryslow"
    )

    data class WorkFiles(
        val workDir: File,
        val segmentTexts: List<String>,
        val fontFile: File,
        val finalOutput: File
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val surfaceColor = TypedValue().let { tv ->
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)
            tv.data
        }
        addSystemBarsColorUpdate(surfaceColor, true)

        initViews()
        showDeviceInfo()
        setupPresetSpinner()
        setupCrfSlider()
        setupButtons()
    }

    private fun initViews() {
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvLog = findViewById(R.id.tvLog)
        tvProgress = findViewById(R.id.tvProgress)
        tvResult = findViewById(R.id.tvResult)
        tvCrfValue = findViewById(R.id.tvCrfValue)
        tvOutputPath = findViewById(R.id.tvOutputPath)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnPlayVideo = findViewById(R.id.btnPlayVideo)
        progressBar = findViewById(R.id.progressBar)
        cardResult = findViewById(R.id.cardResult)
        actvPreset = findViewById(R.id.actvPreset)
        sliderCrf = findViewById(R.id.sliderCrf)
        rgSubtitleMode = findViewById(R.id.rgSubtitleMode)
    }

    private fun showDeviceInfo() {
        val cpuAbi = Build.SUPPORTED_ABIS.joinToString(", ")
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024

        tvDeviceInfo.text = buildString {
            appendLine("型号: ${Build.MODEL}")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("芯片: ${Build.HARDWARE}")
            appendLine("SoC板: ${Build.BOARD}")
            appendLine("ABI: $cpuAbi")
            appendLine("CPU核心: ${cores}核")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("可用内存: ${maxMem}MB")
        }
    }

    private fun setupPresetSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presets)
        actvPreset.setAdapter(adapter)
        actvPreset.setText(presets[5], false)
    }

    private fun setupCrfSlider() {
        sliderCrf.addOnChangeListener { _, value, _ ->
            tvCrfValue.text = value.toInt().toString()
        }
    }

    private fun setupButtons() {
        btnStart.setOnClickListener { startSynthesis() }
        btnCancel.setOnClickListener { cancelSynthesis() }
        btnPlayVideo.setOnClickListener { playVideo() }
    }

    private fun startSynthesis() {
        if (isRunning) return
        isRunning = true

        btnStart.isEnabled = false
        btnCancel.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = getString(R.string.status_preparing)
        cardResult.visibility = View.GONE
        tvOutputPath.visibility = View.GONE
        btnPlayVideo.visibility = View.GONE
        tvLog.text = ""
        outputFile = null

        val preset = actvPreset.text.toString()
        val crf = sliderCrf.value.toInt()
        val subtitleMode = when (rgSubtitleMode.checkedRadioButtonId) {
            R.id.rbHardSub -> SubtitleMode.HARD
            R.id.rbSoftSub -> SubtitleMode.SOFT
            else -> SubtitleMode.NONE
        }

        lifecycleScope.launch {
            try {
                val prepareStart = System.currentTimeMillis()
                val files = prepareAssets()
                val segCount = files.segmentTexts.size
                appendLog("资源准备完成 (${formatDuration(System.currentTimeMillis() - prepareStart)}), 片段: $segCount")

                runFFmpeg(files, preset, crf, subtitleMode)
            } catch (e: Exception) {
                Log.e(TAG, "合成异常", e)
                appendLog("错误: ${e.message}")
                runOnUiThread { onSynthesisFinished(false, "") }
            }
        }
    }

    private fun cancelSynthesis() {
        if (currentSessionId != -1L) {
            FFmpegKit.cancel(currentSessionId)
            appendLog("=== 用户取消 ===")
        }
        onSynthesisFinished(false, getString(R.string.status_cancelled))
    }

    private suspend fun prepareAssets(): WorkFiles = withContext(Dispatchers.IO) {
        val workDir = File(filesDir, "ffmpeg_work")
        workDir.mkdirs()

        // 清理上次的输出视频和中间文件
        workDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("output_") || f.name.startsWith("segment_")
                        || f.name == "concatenated.mp4" || f.name == "concat_list.txt")) {
                f.delete()
            }
        }

        // 从 segments.json 读取片段信息，动态确定片段数
        val jsonStr = assets.open("data/segments.json").bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(jsonStr)
        val segmentTexts = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val raw = jsonArray.getJSONObject(i).getString("text")
            segmentTexts.add(raw.replace(Regex("^=+\\s*\\n\\n"), ""))
        }

        // 复制各片段的素材到工作目录
        for (i in 1..segmentTexts.size) {
            val segDir = File(workDir, "seg_$i")
            segDir.mkdirs()
            val assetDir = "data/seg_%02d".format(i)
            copyAsset("$assetDir/image.png", segDir)
            copyAsset("$assetDir/audio.wav", segDir)
        }

        // 复制字体到工作目录（libass 需要文件系统路径，无法直接读取 APK 内资源）
        val fontFile = File(workDir, "font.otf")
        if (!fontFile.exists()) copyAsset("data/font.otf", workDir)

        val finalOutput = File(workDir, "output_${System.currentTimeMillis()}.mp4")
        WorkFiles(workDir, segmentTexts, fontFile, finalOutput)
    }

    private fun copyAsset(assetName: String, destDir: File): File {
        val fileName = assetName.substringAfterLast("/")
        val destFile = File(destDir, fileName)
        if (destFile.exists()) destFile.delete()
        assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    private fun generateSrtFromSegments(workDir: File, segmentTexts: List<String>): File {
        val srtFile = File(workDir, "subtitles.srt")
        val sb = StringBuilder()
        var offsetMs = 0L

        for (i in 1..segmentTexts.size) {
            val durationMs = getVideoDurationMs(File(workDir, "segment_$i.mp4"))
            if (durationMs <= 0) {
                offsetMs += 3000
                continue
            }

            sb.appendLine(i)
            sb.appendLine("${formatSrtTime(offsetMs)} --> ${formatSrtTime(offsetMs + durationMs)}")
            sb.appendLine(segmentTexts[i - 1])
            sb.appendLine()

            offsetMs += durationMs
        }

        srtFile.writeText(sb.toString())
        return srtFile
    }

    private fun getVideoDurationMs(file: File): Long {
        val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation ?: return -1
        return (info.duration.toDoubleOrNull()?.times(1000))?.toLong() ?: -1
    }

    private fun formatSrtTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }

    private fun detectHwEncoder(): Boolean {
        val output = FFmpegKit.execute("-hide_banner -encoders").output ?: return false
        return output.contains("h264_mediacodec")
    }

    private suspend fun executeFFmpegCommand(cmd: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(cmd,
                { s -> if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode)) },
                { /* log - 不转发到 UI */ },
                { /* statistics */ }
            )
            currentSessionId = session.sessionId
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private suspend fun executeFFmpegArgs(args: Array<String>): Boolean =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(args,
                { s -> if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode)) },
                { /* log - 不转发到 UI */ },
                { /* statistics */ }
            )
            currentSessionId = session.sessionId
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private suspend fun runFFmpeg(
        files: WorkFiles,
        preset: String,
        crf: Int,
        subtitleMode: SubtitleMode
    ) {
        val startTime = System.currentTimeMillis()
        val workDir = files.workDir
        val segCount = files.segmentTexts.size
        val totalSteps = segCount + 2

        val segmentTimes = mutableListOf<Long>()
        var concatTime: Long
        var subtitleTime: Long

        // 检测硬件编码器
        useHwEncoder = withContext(Dispatchers.IO) { detectHwEncoder() }
        val encoderName = if (useHwEncoder) "h264_mediacodec (硬编码)" else "libx264 (软编码)"
        appendLog("编码器: $encoderName | preset=$preset, crf=$crf, 字幕=${subtitleMode.label}")

        // ── Step 1: 逐片段生成视频 ──
        appendLog("\n▶ 第一步: 生成各片段视频")
        for (i in 1..segCount) {
            updateProgress(i, totalSteps, "生成片段 $i/$segCount")

            val segDir = File(workDir, "seg_$i")
            val imagePath = File(segDir, "image.png").absolutePath
            val audioPath = File(segDir, "audio.wav").absolutePath
            val segOutputFile = File(workDir, "segment_$i.mp4")

            val cmd = buildString {
                append("-loop 1 ")
                append("-i \"$imagePath\" ")
                append("-i \"$audioPath\" ")
                if (useHwEncoder) {
                    append("-c:v h264_mediacodec -b:v 2M ")
                } else {
                    append("-c:v libx264 -tune stillimage ")
                    append("-preset $preset -crf $crf ")
                }
                append("-c:a aac -b:a 128k ")
                append("-pix_fmt yuv420p ")
                append("-shortest ")
                append("-y \"${segOutputFile.absolutePath}\"")
            }

            val segStart = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) { executeFFmpegCommand(cmd) }
            val segCost = System.currentTimeMillis() - segStart
            segmentTimes.add(segCost)

            if (!ok || !segOutputFile.exists() || segOutputFile.length() == 0L) {
                appendLog("  ✗ 片段 $i 编码失败")
                runOnUiThread { onSynthesisFinished(false, "片段 $i 编码失败") }
                return
            }
            appendLog("  $i/$segCount  ${formatDuration(segCost)}  ${formatSize(segOutputFile.length())}")
        }

        // 探测每个片段视频时长，动态生成 SRT 字幕文件
        val subtitleFile = withContext(Dispatchers.IO) {
            generateSrtFromSegments(workDir, files.segmentTexts)
        }

        // ── Step 2: 拼接 ──
        updateProgress(segCount + 1, totalSteps, "拼接所有片段")
        appendLog("\n▶ 第二步: 拼接所有片段")

        val concatListFile = File(workDir, "concat_list.txt")
        concatListFile.writeText(buildString {
            for (i in 1..segCount) {
                appendLine("file '${File(workDir, "segment_$i.mp4").absolutePath}'")
            }
        })

        val concatenatedFile = File(workDir, "concatenated.mp4")
        val concatCmd = buildString {
            append("-f concat -safe 0 ")
            append("-i \"${concatListFile.absolutePath}\" ")
            append("-c copy ")
            append("-y \"${concatenatedFile.absolutePath}\"")
        }

        val concatStart = System.currentTimeMillis()
        val concatOk = withContext(Dispatchers.IO) { executeFFmpegCommand(concatCmd) }
        concatTime = System.currentTimeMillis() - concatStart

        if (!concatOk || !concatenatedFile.exists() || concatenatedFile.length() == 0L) {
            appendLog("  ✗ 拼接失败")
            runOnUiThread { onSynthesisFinished(false, "拼接失败") }
            return
        }
        appendLog("  完成 ${formatDuration(concatTime)}  ${formatSize(concatenatedFile.length())}")

        // ── Step 3: 字幕处理 ──
        updateProgress(segCount + 2, totalSteps, "字幕处理")
        appendLog("\n▶ 第三步: 字幕处理 (${subtitleMode.label})")

        val finalOutput = files.finalOutput
        val subStart = System.currentTimeMillis()

        when (subtitleMode) {
            SubtitleMode.HARD -> {
                val fontDir = files.fontFile.parentFile!!.absolutePath
                val vfValue = "subtitles=${subtitleFile.absolutePath}:" +
                    "fontsdir=${fontDir}:" +
                    "force_style='FontName=Source Han Sans CN Medium,FontSize=12,PrimaryColour=&H00FFFFFF," +
                    "OutlineColour=&H00000000,Outline=2,Shadow=1,MarginV=20'"

                val args = if (useHwEncoder) {
                    arrayOf(
                        "-i", concatenatedFile.absolutePath,
                        "-vf", vfValue,
                        "-c:v", "h264_mediacodec", "-b:v", "2M",
                        "-c:a", "copy",
                        "-y", finalOutput.absolutePath
                    )
                } else {
                    arrayOf(
                        "-i", concatenatedFile.absolutePath,
                        "-vf", vfValue,
                        "-c:v", "libx264", "-preset", preset, "-crf", crf.toString(),
                        "-c:a", "copy",
                        "-y", finalOutput.absolutePath
                    )
                }
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("  ✗ 硬字幕烧录失败")
                    runOnUiThread { onSynthesisFinished(false, "硬字幕烧录失败") }
                    return
                }
            }
            SubtitleMode.SOFT -> {
                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-i", subtitleFile.absolutePath,
                    "-map", "0:v", "-map", "0:a", "-map", "1",
                    "-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text",
                    "-y", finalOutput.absolutePath
                )
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("  ✗ 软字幕封装失败")
                    runOnUiThread { onSynthesisFinished(false, "软字幕封装失败") }
                    return
                }
            }
            SubtitleMode.NONE -> {
                withContext(Dispatchers.IO) {
                    concatenatedFile.copyTo(finalOutput, overwrite = true)
                }
            }
        }
        subtitleTime = System.currentTimeMillis() - subStart
        appendLog("  完成 ${formatDuration(subtitleTime)}  ${formatSize(finalOutput.length())}")

        // ── 清理中间文件 ──
        withContext(Dispatchers.IO) {
            try {
                for (i in 1..segCount) File(workDir, "segment_$i.mp4").delete()
                concatListFile.delete()
                concatenatedFile.delete()
                subtitleFile.delete()
            } catch (_: Exception) {}
        }

        // ── 耗时分析 ──
        val totalDuration = System.currentTimeMillis() - startTime
        val outputSize = finalOutput.length()
        val segTotal = segmentTimes.sum()
        val segAvg = if (segmentTimes.isNotEmpty()) segTotal / segmentTimes.size else 0

        appendLog("\n═══════════ 耗时分析 ═══════════")
        appendLog("片段编码: ${formatDuration(segTotal)} (${pct(segTotal, totalDuration)}) 均${formatDuration(segAvg)}")
        appendLog("拼接:     ${formatDuration(concatTime)} (${pct(concatTime, totalDuration)})")
        appendLog("字幕:     ${formatDuration(subtitleTime)} (${pct(subtitleTime, totalDuration)})")
        appendLog("总耗时:   ${formatDuration(totalDuration)}")
        appendLog("输出:     ${formatSize(outputSize)}")

        Log.i(TAG, "合成完成: ${totalDuration}ms, 编码${segTotal}ms, 拼接${concatTime}ms, 字幕${subtitleTime}ms, ${outputSize}bytes")

        val resultInfo = buildResultInfo(
            totalDuration, preset, crf, subtitleMode, outputSize, files,
            segmentTimes, concatTime, subtitleTime
        )
        runOnUiThread { onSynthesisFinished(true, resultInfo, finalOutput) }
    }

    private fun pct(part: Long, total: Long): String {
        if (total == 0L) return "0%"
        return "%.1f%%".format(part * 100.0 / total)
    }

    private fun updateProgress(step: Int, total: Int, desc: String) {
        val pct = (step * 100) / total
        runOnUiThread {
            progressBar.progress = pct
            tvProgress.text = getString(R.string.fmt_progress, step, total, pct, desc)
        }
    }

    private fun buildResultInfo(
        durationMs: Long,
        preset: String,
        crf: Int,
        subtitleMode: SubtitleMode,
        outputSize: Long,
        files: WorkFiles,
        segmentTimes: List<Long>,
        concatTime: Long,
        subtitleTime: Long
    ): String {
        val segTotal = segmentTimes.sum()
        val segAvg = if (segmentTimes.isNotEmpty()) segTotal / segmentTimes.size else 0
        return buildString {
            appendLine("========== 合成统计 ==========")
            appendLine()
            appendLine("总耗时: ${formatDuration(durationMs)}")
            appendLine("耗时(秒): %.2f".format(durationMs / 1000.0))
            appendLine()
            appendLine("--- 耗时分布 ---")
            appendLine("片段编码: ${formatDuration(segTotal)} (${pct(segTotal, durationMs)})")
            appendLine("  平均每片: ${formatDuration(segAvg)}")
            appendLine("拼接: ${formatDuration(concatTime)} (${pct(concatTime, durationMs)})")
            appendLine("字幕: ${formatDuration(subtitleTime)} (${pct(subtitleTime, durationMs)})")
            appendLine()
            appendLine("--- 编码参数 ---")
            appendLine("Preset: $preset")
            appendLine("CRF: $crf")
            appendLine("字幕模式: ${subtitleMode.label}")
            appendLine("编码器: ${if (useHwEncoder) "h264_mediacodec (硬编码)" else "libx264 (软编码)"}")
            appendLine()
            appendLine("--- 文件信息 ---")
            appendLine("片段数: ${files.segmentTexts.size}")
            appendLine("输出文件: ${formatSize(outputSize)}")
            appendLine("输出路径: ${files.finalOutput.absolutePath}")
            appendLine()
            appendLine("--- 设备 ---")
            appendLine("${Build.BRAND} ${Build.MODEL}")
            appendLine("芯片: ${Build.HARDWARE}")
            appendLine("CPU核心: ${Runtime.getRuntime().availableProcessors()}")
            appendLine()
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        }
    }

    private fun onSynthesisFinished(
        success: Boolean,
        resultInfo: String,
        output: File? = null
    ) {
        isRunning = false
        currentSessionId = -1
        btnStart.isEnabled = true
        btnCancel.visibility = View.GONE
        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE

        if (success || resultInfo.isNotEmpty()) {
            cardResult.visibility = View.VISIBLE
            tvResult.text = resultInfo
        }

        if (success && output != null && output.exists()) {
            outputFile = output
            tvOutputPath.visibility = View.VISIBLE
            tvOutputPath.text = getString(R.string.fmt_output_path, output.absolutePath)
            tvOutputPath.setOnClickListener { playVideo() }
            btnPlayVideo.visibility = View.VISIBLE
        }
    }

    private fun playVideo() {
        val file = outputFile ?: return
        if (!file.exists()) {
            Toast.makeText(this, R.string.toast_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.toast_no_video_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            tvLog.append(text)
            if (!text.endsWith("\n")) tvLog.append("\n")
            if (!pendingScroll) {
                pendingScroll = true
                scrollLog.post {
                    scrollLog.fullScroll(View.FOCUS_DOWN)
                    pendingScroll = false
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        val millis = ms % 1000
        return if (minutes > 0) "${minutes}分${secs}秒${millis}毫秒"
        else "${secs}秒${millis}毫秒"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    enum class SubtitleMode(val label: String) {
        HARD("硬字幕(烧录)"),
        SOFT("软字幕(封装)"),
        NONE("无字幕")
    }
}
