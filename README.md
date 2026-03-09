# FFmpegDemo

Android 视频合成 Demo，基于 FFmpegKit 实现多片段图片+音频合成视频。

## 功能

- 逐片段编码（静态图片 + 语音音频 → MP4）
- 图片动效（zoompan 缩放/平移，支持 无动效/轻微/明显 三档）
- 片段拼接（concat demuxer）
- 字幕处理（硬字幕烧录 / 软字幕封装 / 无字幕）
- BGM 背景音乐混音
- 硬件编码（h264_mediacodec）/ 软件编码（libx264）可选

## 技术栈

- Kotlin + Coroutines
- FFmpegKit Full GPL 6.0-2
- Material Design 3
- Min SDK 26 / Target SDK 36
