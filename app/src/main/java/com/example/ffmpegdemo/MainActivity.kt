package com.example.ffmpegdemo

import android.annotation.SuppressLint
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
import com.google.android.material.materialswitch.MaterialSwitch
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

        // ── 字幕样式 ──
        private const val SUBTITLE_FONT_SIZE = 10       // libass 字体大小（基于 PlayResY=288）
        private const val SUBTITLE_MARGIN_V = 24         // 字幕距底部距离（↑ 调大远离底边）
        private const val SUBTITLE_OUTLINE = 1           // 描边宽度
        private const val SUBTITLE_SHADOW = 0            // 阴影（0=无）
        private const val SUBTITLE_USABLE_WIDTH = 0.85   // 字幕可用宽度占比（用于计算每行最大字数）

        // ── 音频 ──
        private const val BGM_FILENAME = "a_litte_story.mp3" // BGM 文件名（assets/data/ 下）
        private const val BGM_VOLUME = 0.3               // 背景音乐音量（0.0~1.0）
        private const val BGM_FADEOUT_SEC = 2             // amix dropout_transition 过渡秒数
        private const val AUDIO_BITRATE = "128k"         // 音频编码码率

        // ── 视频编码 ──
        private const val HW_ENCODER_BITRATE = "2M"      // 硬件编码器码率
        private const val FALLBACK_DURATION_MS = 3000L    // 片段时长探测失败时的 fallback

        // ── 动效 ──
        private const val TARGET_FPS = 25                // zoompan 帧率
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
    private lateinit var rgMotionMode: RadioGroup
    private lateinit var switchHwEncoder: MaterialSwitch

    private var currentSessionId = -1L
    private var isRunning = false
    private var hwEncoderSupported = false
    private var useHwEncoder = false
    private var pendingScroll = false
    private var outputFile: File? = null

    private val presets = arrayOf(
        "ultrafast", "superfast", "veryfast", "faster",
        "fast", "medium", "slow", "slower", "veryslow"
    )

    private data class MotionTemplate(val name: String, val filter: String)

    data class WorkFiles(
        val workDir: File,
        val segmentTexts: List<String>,
        val fontFile: File,
        val bgmFile: File,
        val finalOutput: File
    )

    @SuppressLint("ClickableViewAccessibility")
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

        // 日志区触摸时阻止外层 NestedScrollView 拦截，使内层可独立滚动
        scrollLog.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 异步检测硬件编码器，初始化开关状态
        lifecycleScope.launch {
            hwEncoderSupported = withContext(Dispatchers.IO) { detectHwEncoder() }
            val hwStatus = if (hwEncoderSupported) "支持" else "不支持"
            tvDeviceInfo.append("\n硬编码: $hwStatus (h264_mediacodec)")

            switchHwEncoder.isEnabled = hwEncoderSupported
            switchHwEncoder.isChecked = hwEncoderSupported
            updateSoftEncodeParamsVisibility(!hwEncoderSupported)
        }

        switchHwEncoder.setOnCheckedChangeListener { _, isChecked ->
            updateSoftEncodeParamsVisibility(!isChecked)
        }
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
        rgMotionMode = findViewById(R.id.rgMotionMode)
        switchHwEncoder = findViewById(R.id.switchHwEncoder)
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

    private fun updateSoftEncodeParamsVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.labelPreset).visibility = visibility
        findViewById<View>(R.id.tilPreset).visibility = visibility
        findViewById<View>(R.id.labelCrf).visibility = visibility
        findViewById<View>(R.id.layoutCrf).visibility = visibility
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

        useHwEncoder = switchHwEncoder.isChecked
        val preset = actvPreset.text.toString()
        val crf = sliderCrf.value.toInt()
        val subtitleMode = when (rgSubtitleMode.checkedRadioButtonId) {
            R.id.rbHardSub -> SubtitleMode.HARD
            R.id.rbSoftSub -> SubtitleMode.SOFT
            else -> SubtitleMode.NONE
        }
        val motionMode = when (rgMotionMode.checkedRadioButtonId) {
            R.id.rbMotionSubtle -> MotionMode.SUBTLE
            R.id.rbMotionDramatic -> MotionMode.DRAMATIC
            else -> MotionMode.NONE
        }

        lifecycleScope.launch {
            try {
                val prepareStart = System.currentTimeMillis()
                val files = prepareAssets()
                val segCount = files.segmentTexts.size
                appendLog("资源准备完成 (${formatDuration(System.currentTimeMillis() - prepareStart)}), 片段: $segCount")

                runFFmpeg(files, preset, crf, subtitleMode, motionMode)
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
        runOnUiThread { onSynthesisFinished(false, getString(R.string.status_cancelled)) }
    }

    private suspend fun prepareAssets(): WorkFiles = withContext(Dispatchers.IO) {
        val workDir = File(filesDir, "ffmpeg_work")
        workDir.mkdirs()

        // 清理中间文件（输出文件用固定名，FFmpeg -y 自动覆盖）
        workDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("segment_")
                        || f.name == "concatenated.mp4" || f.name == "concat_list.txt"
                        || f.name == "subtitles.srt")) {
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

        // 复制背景音乐到工作目录
        val bgmFile = File(workDir, BGM_FILENAME)
        if (!bgmFile.exists()) copyAsset("data/$BGM_FILENAME", workDir)

        val finalOutput = File(workDir, "output.mp4")
        WorkFiles(workDir, segmentTexts, fontFile, bgmFile, finalOutput)
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
        var srtIndex = 1

        // 从第一个片段探测视频尺寸，计算每行最大字符数
        val maxChars = calcMaxCharsPerLine(File(workDir, "segment_1.mp4"))

        for (i in 1..segmentTexts.size) {
            val durationMs = (getMediaDurationSec(File(workDir, "segment_$i.mp4"))?.times(1000))?.toLong() ?: -1
            if (durationMs <= 0) {
                offsetMs += FALLBACK_DURATION_MS
                continue
            }

            // 按标点拆分成多条短句，每条独立分配时间
            val sentences = splitBySentence(segmentTexts[i - 1], maxChars)
            val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)

            for (sentence in sentences) {
                val sentenceMs = (durationMs * sentence.length / totalChars.toDouble()).toLong()
                sb.appendLine(srtIndex++)
                sb.appendLine("${formatSrtTime(offsetMs)} --> ${formatSrtTime(offsetMs + sentenceMs)}")
                sb.appendLine(sentence)
                sb.appendLine()
                offsetMs += sentenceMs
            }
        }

        srtFile.writeText(sb.toString())
        return srtFile
    }

    /**
     * 按标点拆句：先拆散再贪心合并，每条不超过 maxChars 且无碎片
     */
    private fun splitBySentence(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        // 第一步：按标点拆成最小粒度的短句
        val punctuation = setOf('，', '。', '、', '；', '！', '？', ',', '.', '!', '?')
        val fragments = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in punctuation) {
                fragments.add(text.substring(start, i + 1).trim())
                start = i + 1
            }
        }
        if (start < text.length) fragments.add(text.substring(start).trim())
        fragments.removeAll { it.isEmpty() }

        // 只有一个片段或没拆开，直接返回
        if (fragments.size <= 1) return listOf(text)

        // 第二步：贪心合并——逐个累加，超过 maxChars 就换新行
        val result = mutableListOf<String>()
        val current = StringBuilder(fragments[0])
        for (i in 1 until fragments.size) {
            if (current.length + fragments[i].length <= maxChars) {
                current.append(fragments[i])
            } else {
                result.add(current.toString())
                current.clear().append(fragments[i])
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())

        // 第三步：尾巴太短就并入上一条
        val minChars = maxChars / 3
        if (result.size >= 2 && result.last().length < minChars) {
            val tail = result.removeAt(result.lastIndex)
            result[result.lastIndex] = result.last() + tail
        }

        return result
    }

    /**
     * 根据视频尺寸和字幕 FontSize 计算每行最大字符数
     * libass 默认 PlayResY=288，中文字符近似等宽（宽≈高）
     */
    private fun calcMaxCharsPerLine(videoFile: File, fontSize: Int = SUBTITLE_FONT_SIZE): Int {
        val info = FFprobeKit.getMediaInformation(videoFile.absolutePath).mediaInformation
        if (info != null) {
            val stream = info.streams?.firstOrNull { it.width != null && it.height != null }
            if (stream != null) {
                val videoWidth = stream.width.toDouble()
                val videoHeight = stream.height.toDouble()
                val charPixelWidth = fontSize * videoHeight / 288.0
                val usableWidth = videoWidth * SUBTITLE_USABLE_WIDTH
                val calculated = (usableWidth / charPixelWidth).toInt()
                if (calculated > 0) return calculated
            }
        }
        return 20
    }

    /** 返回媒体时长（秒），探测失败返回 null */
    private fun getMediaDurationSec(file: File): Double? {
        val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation ?: return null
        return info.duration.toDoubleOrNull()
    }

    private fun getImageSize(file: File): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = if (opts.outWidth > 0) opts.outWidth else 1280
        val h = if (opts.outHeight > 0) opts.outHeight else 720
        return Pair(w, h)
    }

    /**
     * 生成随机图片动效滤镜（zoompan），相邻片段不重复
     * @return Triple(滤镜字符串, 模板名称, 本次模板索引)
     */
    private fun getRandomMotionEffect(
        durationSec: Double,
        mode: MotionMode,
        lastIndex: Int,
        width: Int = 1280,
        height: Int = 720
    ): Triple<String, String, Int> {
        val zoomRange = when (mode) {
            MotionMode.SUBTLE -> 0.10
            MotionMode.DRAMATIC -> 0.20
            else -> 0.0
        }

        // 保持浮点精度，d=1 配合 -loop 1 逐帧处理
        val totalFrames = (durationSec * TARGET_FPS).coerceAtLeast(1.0)
        val s = "${width}x${height}"
        // 缓动: ease-in-out  t=on/total → (sin(t*PI - PI/2)+1)/2
        val ease = "((sin(on/$totalFrames*PI-PI/2)+1)/2)"

        // x/y 用 max(0,...) 防止 zoom>1 时坐标为负导致绿边
        val cx = "max(0\\,($width-iw*zoom)/2)"
        val cy = "max(0\\,($height-ih*zoom)/2)"
        val templates = listOf(
            MotionTemplate("中心放大",
                "zoompan=z=1+${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=$cx:y=$cy:s=$s"),
            MotionTemplate("中心缩小",
                "zoompan=z=1+${zoomRange}-${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=$cx:y=$cy:s=$s"),
            MotionTemplate("左移右",
                "zoompan=z=1+${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=max(0\\,($width-iw*zoom)*($ease)):y=$cy:s=$s"),
            MotionTemplate("右移左",
                "zoompan=z=1+${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=max(0\\,($width-iw*zoom)*(1-$ease)):y=$cy:s=$s"),
            MotionTemplate("上移下",
                "zoompan=z=1+${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=$cx:y=max(0\\,($height-ih*zoom)*($ease)):s=$s"),
            MotionTemplate("下移上",
                "zoompan=z=1+${zoomRange}*($ease):d=1:fps=$TARGET_FPS:x=$cx:y=max(0\\,($height-ih*zoom)*(1-$ease)):s=$s")
        )

        // 相邻不重复的随机选取
        val candidates = templates.indices.filter { it != lastIndex }
        val idx = candidates.random()
        val t = templates[idx]
        return Triple(t.filter, t.name, idx)
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
        subtitleMode: SubtitleMode,
        motionMode: MotionMode = MotionMode.NONE
    ) {
        val startTime = System.currentTimeMillis()
        val workDir = files.workDir
        val segCount = files.segmentTexts.size
        val totalSteps = segCount + 2

        val segmentTimes = mutableListOf<Long>()
        var concatTime: Long
        var subtitleTime: Long

        val encoderName = if (useHwEncoder) "h264_mediacodec (硬编码)" else "libx264 (软编码)"
        if (useHwEncoder) {
            appendLog("编码器: $encoderName | 码率=$HW_ENCODER_BITRATE, 字幕=${subtitleMode.label}, 动效=${motionMode.label}")
        } else {
            appendLog("编码器: $encoderName | preset=$preset, crf=$crf, 字幕=${subtitleMode.label}, 动效=${motionMode.label}")
        }

        // ── Step 1: 逐片段生成视频 ──
        appendLog("\n▶ 第一步: 生成各片段视频")
        var lastMotionIndex = -1  // 用于相邻不重复
        for (i in 1..segCount) {
            updateProgress(i, totalSteps, "生成片段 $i/$segCount")

            val segDir = File(workDir, "seg_$i")
            val imagePath = File(segDir, "image.png").absolutePath
            val audioPath = File(segDir, "audio.wav").absolutePath
            val segOutputFile = File(workDir, "segment_$i.mp4")

            // 有动效时，先探测音频时长和图片尺寸，生成滤镜
            var motionFilter: String? = null
            if (motionMode != MotionMode.NONE) {
                val imageFile = File(segDir, "image.png")
                val audioDurationSec = withContext(Dispatchers.IO) {
                    getMediaDurationSec(File(segDir, "audio.wav")) ?: 3.0
                }
                val imgSize = withContext(Dispatchers.IO) { getImageSize(imageFile) }
                val result = getRandomMotionEffect(
                    audioDurationSec, motionMode, lastMotionIndex,
                    imgSize.first, imgSize.second
                )
                lastMotionIndex = result.third
                motionFilter = result.first
                appendLog("    动效: ${result.second}")
            }

            val cmd = buildString {
                append("-loop 1 ")
                append("-i \"$imagePath\" ")
                append("-i \"$audioPath\" ")
                if (motionFilter != null) append("-vf \"$motionFilter\" ")
                if (useHwEncoder) {
                    append("-c:v h264_mediacodec -b:v $HW_ENCODER_BITRATE ")
                } else {
                    append("-c:v libx264 ")
                    if (motionFilter == null) append("-tune stillimage ")
                    append("-preset $preset -crf $crf ")
                }
                append("-c:a aac -b:a $AUDIO_BITRATE ")
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

        // ── Step 3: 字幕 + BGM 混音 ──
        updateProgress(segCount + 2, totalSteps, "字幕 + BGM 混音")
        appendLog("\n▶ 第三步: 字幕处理 (${subtitleMode.label}) + BGM 混音")

        val finalOutput = files.finalOutput
        val bgmFile = files.bgmFile
        val subStart = System.currentTimeMillis()

        // BGM 混音滤镜：BGM 循环播放、音量 30%，以视频时长为准
        val audioFilter = "[1:a]volume=$BGM_VOLUME[bgm];[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=$BGM_FADEOUT_SEC[aout]"

        when (subtitleMode) {
            SubtitleMode.HARD -> {
                val escapePath = { p: String -> p.replace("\\", "\\\\").replace(":", "\\:") }
                val vfValue = "subtitles=${escapePath(subtitleFile.absolutePath)}:" +
                    "fontsdir=${escapePath(files.fontFile.parentFile!!.absolutePath)}:" +
                    "force_style='FontName=Source Han Sans CN Medium,FontSize=$SUBTITLE_FONT_SIZE,PrimaryColour=&H00FFFFFF," +
                    "OutlineColour=&H00000000,Outline=$SUBTITLE_OUTLINE,Shadow=$SUBTITLE_SHADOW,MarginV=$SUBTITLE_MARGIN_V'"

                val encoderArgs = if (useHwEncoder) {
                    arrayOf("-c:v", "h264_mediacodec", "-b:v", HW_ENCODER_BITRATE)
                } else {
                    arrayOf("-c:v", "libx264", "-preset", preset, "-crf", crf.toString())
                }
                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-stream_loop", "-1", "-i", bgmFile.absolutePath,
                    "-filter_complex", "[0:v]${vfValue}[vout];$audioFilter",
                    "-map", "[vout]", "-map", "[aout]",
                    *encoderArgs,
                    "-c:a", "aac", "-b:a", AUDIO_BITRATE,
                    "-y", finalOutput.absolutePath
                )
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("  ✗ 硬字幕烧录 + BGM 混音失败")
                    runOnUiThread { onSynthesisFinished(false, "硬字幕烧录 + BGM 混音失败") }
                    return
                }
            }
            SubtitleMode.SOFT -> {
                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-stream_loop", "-1", "-i", bgmFile.absolutePath,
                    "-i", subtitleFile.absolutePath,
                    "-filter_complex", audioFilter,
                    "-map", "0:v", "-map", "[aout]", "-map", "2",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-c:s", "mov_text",
                    "-y", finalOutput.absolutePath
                )
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("  ✗ 软字幕封装 + BGM 混音失败")
                    runOnUiThread { onSynthesisFinished(false, "软字幕封装 + BGM 混音失败") }
                    return
                }
            }
            SubtitleMode.NONE -> {
                val args = arrayOf(
                    "-i", concatenatedFile.absolutePath,
                    "-stream_loop", "-1", "-i", bgmFile.absolutePath,
                    "-filter_complex", audioFilter,
                    "-map", "0:v", "-map", "[aout]",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", AUDIO_BITRATE,
                    "-y", finalOutput.absolutePath
                )
                val ok = withContext(Dispatchers.IO) { executeFFmpegArgs(args) }
                if (!ok || !finalOutput.exists() || finalOutput.length() == 0L) {
                    appendLog("  ✗ BGM 混音失败")
                    runOnUiThread { onSynthesisFinished(false, "BGM 混音失败") }
                    return
                }
            }
        }
        subtitleTime = System.currentTimeMillis() - subStart
        appendLog("  完成 ${formatDuration(subtitleTime)}  ${formatSize(finalOutput.length())}")

        // ── 输出 SRT 内容供调试查看 ──
        appendLog("\n═══════════ SRT 字幕内容 ═══════════")
        appendLog(subtitleFile.readText().trimEnd())

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
        appendLog("字幕+BGM: ${formatDuration(subtitleTime)} (${pct(subtitleTime, totalDuration)})")
        appendLog("总耗时:   ${formatDuration(totalDuration)}")
        appendLog("输出:     ${formatSize(outputSize)}")

        Log.i(TAG, "合成完成: ${totalDuration}ms, 编码${segTotal}ms, 拼接${concatTime}ms, 字幕+BGM${subtitleTime}ms, ${outputSize}bytes")

        val resultInfo = buildResultInfo(
            totalDuration, preset, crf, subtitleMode, motionMode, outputSize, files,
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
            progressBar.setProgress(pct, true)
            tvProgress.text = getString(R.string.fmt_progress, step, total, pct, desc)
        }
    }

    private fun buildResultInfo(
        durationMs: Long,
        preset: String,
        crf: Int,
        subtitleMode: SubtitleMode,
        motionMode: MotionMode,
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
            appendLine("字幕+BGM: ${formatDuration(subtitleTime)} (${pct(subtitleTime, durationMs)})")
            appendLine()
            appendLine("--- 编码参数 ---")
            appendLine("编码器: ${if (useHwEncoder) "h264_mediacodec (硬编码)" else "libx264 (软编码)"}")
            if (useHwEncoder) {
                appendLine("码率: $HW_ENCODER_BITRATE")
            } else {
                appendLine("Preset: $preset")
                appendLine("CRF: $crf")
            }
            appendLine("字幕模式: ${subtitleMode.label}")
            appendLine("图片动效: ${motionMode.label}")
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

    enum class MotionMode(val label: String) {
        NONE("无动效"),
        SUBTLE("轻微"),
        DRAMATIC("明显")
    }
}
