# Subtitle Burner

An Android Studio project for burning `.srt` subtitles and the watermark
`ترجمة فريق S.K` into a video.

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- A device or emulator running Android 8.0 / API 26 or newer

Open the `subtitle-burner` directory in Android Studio and run the `app`
configuration. The app uses the system document picker, so it does not need
broad storage permissions.

## What is included

- Video picker using `ACTION_OPEN_DOCUMENT`
- Video inspection through `MediaExtractor`:
  - source width and height
  - source frame rate when declared by the container
  - timestamp-derived frame-rate estimate when it is not declared
- UTF-8/UTF-16 aware SRT parsing, BOM removal, comma or period millisecond
  separators, and multiline cues
- Font, size, text-color, subtitle placement, and watermark controls
- Surface-based `MediaCodec` decoder and encoder connected by an EGL/OpenGL
  compositor
- `MediaMuxer` output with the original audio track copied through unchanged
- Output written to the app's Movies directory and shareable through Android's
  system share sheet

## Rendering notes

The renderer never assumes 25 or 30 FPS. It carries the decoded presentation
timestamps into the encoder and configures the encoder with the inspected
source rate. The encoder and muxer use the inspected width and height, so the
video is not stretched to a fixed canvas.

The project intentionally uses platform Android views and media APIs instead
of a large UI framework. This keeps the sample easy to import and makes the
media pipeline visible in the source.