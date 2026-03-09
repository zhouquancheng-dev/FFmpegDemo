package com.example.ffmpegdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    private lateinit var progressBar: ProgressBar
    private lateinit var cardResult: MaterialCardView
    private lateinit var spinnerPreset: Spinner
    private lateinit var seekBarCrf: SeekBar
    private lateinit var rgSubtitleMode: RadioGroup

    private var currentSessionId: Long = -1
    private var isRunning = false
    private var outputFile: File? = null

    private val segmentCount = 18
    private val totalSteps get() = segmentCount + 2

    private val presets = arrayOf(
        "ultrafast", "superfast", "veryfast", "faster",
        "fast", "medium", "slow", "slower", "veryslow"
    )

    data class WorkFiles(val workDir: File, val subtitleFile: File, val finalOutput: File)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        showDeviceInfo()
        setupPresetSpinner()
        setupCrfSeekBar()
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
        spinnerPreset = findViewById(R.id.spinnerPreset)
        seekBarCrf = findViewById(R.id.seekBarCrf)
        rgSubtitleMode = findViewById(R.id.rgSubtitleMode)
    }

    private fun showDeviceInfo() {
        val cpuAbi = Build.SUPPORTED_ABIS.joinToString(", ")
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024

        val info = buildString {
            appendLine("型号: ${Build.MODEL}")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("芯片: ${Build.HARDWARE}")
            appendLine("SoC板: ${Build.BOARD}")
            appendLine("ABI: $cpuAbi")
            appendLine("CPU核心: ${cores}核")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("可用内存: ${maxMem}MB")
        }
        tvDeviceInfo.text = info
    }

    private fun setupPresetSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPreset.adapter = adapter
        spinnerPreset.setSelection(5)
    }

    private fun setupCrfSeekBar() {
        seekBarCrf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCrfValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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

        val preset = presets[spinnerPreset.selectedItemPosition]
        val crf = seekBarCrf.progress
        val subtitleMode = when (rgSubtitleMode.checkedRadioButtonId) {
            R.id.rbHardSub -> SubtitleMode.HARD
            R.id.rbSoftSub -> SubtitleMode.SOFT
            else -> SubtitleMode.NONE
        }

        lifecycleScope.launch {
            try {
                val prepareStart = System.currentTimeMillis()
                val files = prepareAssets()
                val prepareCost = System.currentTimeMillis() - prepareStart
                appendLog("=== 资源准备完成 (耗时 ${formatDuration(prepareCost)}) ===")
                appendLog("片段数: $segmentCount")
                appendLog("字幕: ${files.subtitleFile.name} (${files.subtitleFile.length()}B)")
                appendLog("")

                runFFmpeg(files, preset, crf, subtitleMode)
            } catch (e: Exception) {
                appendLog("错误: ${e.message}")
                Log.e(TAG, "合成异常", e)
                runOnUiThread { onSynthesisFinished(false, "") }
            }
        }
    }

    private fun cancelSynthesis() {
        if (currentSessionId != -1L) {
            FFmpegKit.cancel(currentSessionId)
            appendLog("\n=== 用户取消 ===")
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
                Log.d(TAG, "清理旧文件: ${f.name}")
            }
        }

        for (i in 1..segmentCount) {
            val segDir = File(workDir, "seg_$i")
            segDir.mkdirs()
            copyAsset("data/$i/image.png", segDir)
            copyAsset("data/$i/audio.wav", segDir)
        }

        val subtitleFile = copyAsset("data/subtitles.srt", workDir)
        Log.d(TAG, "字幕文件: ${subtitleFile.absolutePath}, size=${subtitleFile.length()}")

        val finalOutput = File(workDir, "output_${System.currentTimeMillis()}.mp4")
        WorkFiles(workDir, subtitleFile, finalOutput)
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

    /**
     * 用命令字符串执行 FFmpeg（FFmpegKit 自行解析参数）
     */
    private suspend fun executeFFmpegCommand(cmd: String): Boolean =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "FFmpeg cmd: $cmd")
            val session = FFmpegKit.executeAsync(
                cmd,
                { session ->
                    val rc = session.returnCode
                    Log.d(TAG, "FFmpeg done: returnCode=$rc")
                    if (cont.isActive) cont.resume(ReturnCode.isSuccess(rc))
                },
                { log ->
                    Log.v(TAG, log.message ?: "")
                    runOnUiThread { appendLog(log.message) }
                },
                { /* statistics */ }
            )
            currentSessionId = session.sessionId
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    /**
     * 用参数数组执行 FFmpeg（绕过命令字符串解析，避免引号/转义问题）
     */
    private suspend fun executeFFmpegArgs(args: Array<String>): Boolean =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "FFmpeg args: ${args.joinToString(" ")}")
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { session ->
                    val rc = session.returnCode
                    Log.d(TAG, "FFmpeg done: returnCode=$rc")
                    if (cont.isActive) cont.resume(ReturnCode.isSuccess(rc))
                },
                { log ->
                    Log.v(TAG, log.message ?: "")
                    runOnUiThread { appendLog(log.message) }
                },
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

        val segmentTimes = mutableListOf<Long>()
        var concatTime: Long
        var subtitleTime: Long

        appendLog("=== 参数: preset=$preset, crf=$crf, 字幕=${subtitleMode.label} ===")
        appendLog("")

        // ── Step 1: 逐片段生成视频 ──
        appendLog("=== 第一步: 生成各片段视频 ===")
        for (i in 1..segmentCount) {
            updateProgress(i, "生成片段 $i/$segmentCount")

            val segDir = File(workDir, "seg_$i")
            val imagePath = File(segDir, "image.png").absolutePath
            val audioPath = File(segDir, "audio.wav").absolutePath
            val segOutputFile = File(workDir, "segment_$i.mp4")

            val cmd = buildString {
                append("-loop 1 ")
                append("-i \"$imagePath\" ")
                append("-i \"$audioPath\" ")
                append("-c:v libx264 -tune stillimage ")
                append("-preset $preset -crf $crf ")
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
                appendLog("片段 $i 编码失败!")
                Log.e(TAG, "片段 $i 失败: exists=${segOutputFile.exists()}, size=${segOutputFile.length()}")
                runOnUiThread { onSynthesisFinished(false, "片段 $i 编码失败") }
                return
            }
            appendLog("片段 $i 完成 (${formatDuration(segCost)}, ${formatSize(segOutputFile.length())})")
        }

        // ── Step 2: 拼接 ──
        updateProgress(segmentCount + 1, "拼接所有片段")
        appendLog("")
        appendLog("=== 第二步: 拼接所有片段 ===")

        val concatListFile = File(workDir, "concat_list.txt")
        concatListFile.writeText(buildString {
            for (i in 1..segmentCount) {
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
            appendLog("拼接失败!")
            Log.e(TAG, "拼接失败: exists=${concatenatedFile.exists()}, size=${concatenatedFile.length()}")
            runOnUiThread { onSynthesisFinished(false, "拼接失败") }
            return
        }
        appendLog("拼接完成 (${formatDuration(concatTime)}, ${formatSize(concatenatedFile.length())})")

        // ── Step 3: 字幕处理 ──
        updateProgress(segmentCount + 2, "字幕处理")
        appendLog("")
        appendLog("=== 第三步: 字幕处理 (${subtitleMode.label}) ===")

        val finalOutput = files.finalOutput
        val subStart = System.currentTimeMillis()

        when (subtitleMode) {
            SubtitleMode.HARD -> {
                val subPath = files.subtitleFile.absolutePath
                Log.d(TAG, "硬字幕路径: $subPath, exists=${files.subtitleFile.exists()}, size=${files.subtitleFile.length()}")

                // 用参数数组执行，彻底避免引号/逗号/冒号转义问题
                // force_style 中单引号包裹，FFmpeg filter parser 不会拆分逗号
                val vfValue = "subtitles=${subPath}:" +
                    "force_style='FontSize=24,PrimaryColour=&H00FFFFFF," +
                    "OutlineColour=&H00000000,Outline=2,Shadow=1,MarginV=30'"

                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-vf", vfValue,
                    "-c:v", "libx264", "-preset", preset, "-crf", crf.toString(),
                    "-c:a", "copy",
                    "-y", finalOutput.absolutePath
                )
                appendLog("硬字幕 -vf: $vfValue")
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("硬字幕烧录失败!")
                    Log.e(TAG, "硬字幕失败: exists=${finalOutput.exists()}, size=${finalOutput.length()}")
                    runOnUiThread { onSynthesisFinished(false, "硬字幕烧录失败") }
                    return
                }
            }
            SubtitleMode.SOFT -> {
                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-i", files.subtitleFile.absolutePath,
                    "-map", "0:v", "-map", "0:a", "-map", "1",
                    "-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text",
                    "-y", finalOutput.absolutePath
                )
                appendLog("软字幕命令: ffmpeg ${args.joinToString(" ")}")
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("软字幕封装失败!")
                    Log.e(TAG, "软字幕失败: exists=${finalOutput.exists()}, size=${finalOutput.length()}")
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
        appendLog("字幕处理完成 (${formatDuration(subtitleTime)}, ${formatSize(finalOutput.length())})")

        // ── 清理中间文件 ──
        appendLog("")
        appendLog("=== 清理中间文件 ===")
        withContext(Dispatchers.IO) {
            try {
                for (i in 1..segmentCount) {
                    File(workDir, "segment_$i.mp4").delete()
                }
                concatListFile.delete()
                concatenatedFile.delete()
            } catch (e: Exception) {
                Log.w(TAG, "清理中间文件异常", e)
            }
        }
        appendLog("清理完成")

        // ── 耗时分析 ──
        val totalDuration = System.currentTimeMillis() - startTime
        val outputSize = finalOutput.length()
        val segTotal = segmentTimes.sum()
        val segMin = segmentTimes.minOrNull() ?: 0
        val segMax = segmentTimes.maxOrNull() ?: 0
        val segAvg = if (segmentTimes.isNotEmpty()) segTotal / segmentTimes.size else 0

        appendLog("")
        appendLog("=========================================")
        appendLog("           耗时分析总结")
        appendLog("=========================================")
        appendLog("片段编码总计: ${formatDuration(segTotal)} (占比 ${pct(segTotal, totalDuration)})")
        appendLog("  最快片段: ${formatDuration(segMin)}")
        appendLog("  最慢片段: ${formatDuration(segMax)}")
        appendLog("  平均每片: ${formatDuration(segAvg)}")
        appendLog("拼接耗时:   ${formatDuration(concatTime)} (占比 ${pct(concatTime, totalDuration)})")
        appendLog("字幕耗时:   ${formatDuration(subtitleTime)} (占比 ${pct(subtitleTime, totalDuration)})")
        appendLog("-----------------------------------------")
        appendLog("总耗时:     ${formatDuration(totalDuration)}")
        appendLog("输出文件:   ${formatSize(outputSize)}")
        appendLog("=========================================")

        Log.i(TAG, "===== 合成完成 =====")
        Log.i(TAG, "总耗时: ${totalDuration}ms")
        Log.i(TAG, "片段编码: ${segTotal}ms (min=${segMin}, max=${segMax}, avg=${segAvg})")
        Log.i(TAG, "拼接: ${concatTime}ms, 字幕: ${subtitleTime}ms")
        Log.i(TAG, "输出: ${finalOutput.absolutePath}, $outputSize bytes")

        val resultInfo = buildResultInfo(
            totalDuration, preset, crf, subtitleMode, outputSize, files,
            segmentTimes, concatTime, subtitleTime
        )
        runOnUiThread {
            onSynthesisFinished(true, resultInfo, finalOutput)
        }
    }

    private fun pct(part: Long, total: Long): String {
        if (total == 0L) return "0%"
        return "%.1f%%".format(part * 100.0 / total)
    }

    private fun updateProgress(step: Int, desc: String) {
        val pct = (step * 100) / totalSteps
        runOnUiThread {
            progressBar.progress = pct
            tvProgress.text = getString(R.string.fmt_progress, step, totalSteps, pct, desc)
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
            appendLine("编码器: libx264 (软编码)")
            appendLine()
            appendLine("--- 文件信息 ---")
            appendLine("片段数: $segmentCount")
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
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
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
        Log.d(TAG, text)
        tvLog.append(text)
        if (!text.endsWith("\n")) tvLog.append("\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        val millis = ms % 1000
        return if (minutes > 0) {
            "${minutes}分${secs}秒${millis}毫秒"
        } else {
            "${secs}秒${millis}毫秒"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    enum class SubtitleMode(val label: String) {
        HARD("硬字幕(烧录)"),
        SOFT("软字幕(封装)"),
        NONE("无字幕")
    }
}
