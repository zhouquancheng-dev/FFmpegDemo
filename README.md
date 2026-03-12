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

## 视频合成配置接口文档

### 接口用途
用于获取一次视频合成任务的配置参数。
客户端根据返回内容执行视频帧率设置、图片动效、字幕样式和背景音乐混音。

### 响应格式
- `Content-Type: application/json`
- 响应体为 JSON 对象

### 响应字段说明

| 字段 | 类型 | 必填 | 说明 | 取值 / 约束 |
|---|---|---:|---|---|
| `task_id` | `string` | 是 | 合成任务唯一标识 | 建议使用 UUID |
| `audio_speed` | `number` | 否 | 音频播放速度倍率 | `0.5 ~ 2.0`，默认 `1.0` |
| `fps` | `number` | 是 | 输出视频帧率 | 建议 `24`、`25`、`30` |
| `enable_motion` | `boolean` | 是 | 是否启用图片动效 | `true` / `false` |
| `motion_intensity` | `string` | 是 | 动效强度 | `none` / `subtle` / `medium` / `dramatic` |
| `subtitle` | `object` | 是 | 字幕配置对象 | 见下文 |
| `music` | `object` | 是 | 背景音乐配置对象 | 见下文 |

### subtitle 对象说明

| 字段 | 类型 | 必填 | 说明 | 取值 / 约束 |
|---|---|---:|---|---|
| `enabled` | `boolean` | 是 | 是否启用字幕 | `true` / `false` |
| `mode` | `string` | 是 | 字幕模式 | `hard` / `soft` / `none` |
| `font_size` | `number` | 是 | 字号，按视频像素使用 | 建议 `28 ~ 48` |
| `margin_v` | `number` | 是 | 字幕距离底部的垂直边距，单位像素 | 建议 `60 ~ 140` |
| `outline` | `number` | 是 | 描边宽度，单位像素 | 建议 `0 ~ 4` |
| `font_color` | `string` | 是 | 字体颜色 | 固定格式 `#AARRGGBB` |
| `outline_color` | `string` | 是 | 描边颜色 | 固定格式 `#AARRGGBB` |
| `max_chars_per_line` | `number` | 否 | 每行最大字符数 | 建议 `12 ~ 20` |

### music 对象说明

| 字段 | 类型 | 必填 | 说明 | 取值 / 约束 |
|---|---|---:|---|---|
| `volume` | `number` | 是 | 背景音乐音量 | `0.0 ~ 1.0` |
| `fade_out_sec` | `number` | 是 | BGM 淡出时长，单位秒 | 建议 `0 ~ 3` |

### 返回约束
- 颜色统一使用 `#AARRGGBB`
- 枚举值统一使用小写
- 数值字段返回 `number`
- 布尔字段返回 `boolean`
- 建议后端补齐默认值，避免客户端兜底猜测

### 响应示例

```json
{
  "task_id": "1fb09ceb-3737-4cdb-82e2-95c31bcbd63e",
  "audio_speed": 1.0,
  "fps": 30,
  "enable_motion": true,
  "motion_intensity": "medium",
  "subtitle": {
    "enabled": true,
    "mode": "hard",
    "font_size": 36,
    "margin_v": 100,
    "outline": 2,
    "font_color": "#FFFFFFFF",
    "outline_color": "#FF000000",
    "max_chars_per_line": 15
  },
  "music": {
    "volume": 0.2,
    "fade_out_sec": 1.0
  }
}
```

### 字段示例说明
- `font_color: "#FFFFFFFF"` 表示不透明白色文字
- `outline_color: "#FF000000"` 表示不透明黑色描边
- `mode: "hard"` 表示烧录硬字幕，视频内直接可见
- `mode: "soft"` 表示封装软字幕，播放器不一定默认展示
- `mode: "none"` 表示不加字幕
