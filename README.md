# Whisper Unchained for Android

A fast, private, floating-button dictation app that records audio, sends it to an OpenAI-compatible Whisper transcription server (such as your self-hosted desktop Whisper Unchained Server or cloud providers like Groq/OpenAI), and pastes the transcription directly into the active text field system-wide.

## How It Works

1. A persistent floating microphone button overlays all apps.
2. Tap to record; tap again to finish.
3. The audio is sent over HTTP to an OpenAI-compatible endpoint (`POST /v1/audio/transcriptions`).
4. Trailing silence hint-loops and leading hint recitations are automatically sanitized (with strict rules preserving natural dictation).
5. Voice edit commands (e.g. *"prompt AI, rewrite this as an email"*) are executed seamlessly.
6. The transcription is injected into the currently focused text field via Android's Accessibility Service (or copied to the system clipboard in 1-tap chip mode).

No cloud lock-in, zero telemetry — run it completely on your own hardware or with your personal API key.

## Requirements

- Android 14+ (API 34)
- A self-hosted Whisper server (such as [Whisper Unchained Server](https://github.com/Dominic-Shirazi/Whisper-Unchained-Server) or Speaches) or cloud provider (Groq, OpenAI, OpenRouter)
- Network access to the server — [Tailscale](https://tailscale.com/) works seamlessly for remote access

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Capture microphone input |
| `SYSTEM_ALERT_WINDOW` | Display the floating microphone button over other apps |
| `POST_NOTIFICATIONS` | Foreground service notifications (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Optionally auto-start the floating button service on device reboot |
| Accessibility Service | Automatically paste transcribed text into the focused input field |

## Key Features

- **Standard OpenAI API (`/v1/audio/transcriptions`)**: Interoperable across Self-Hosted, Speaches, Groq, OpenRouter, and custom endpoints.
- **Provider Presets**: Instant configuration for Self-Hosted (Faster-Whisper / Speaches), Groq Cloud (`whisper-large-v3-turbo`), OpenAI / OpenRouter (`whisper-1`, `gpt-4o-mini-transcribe`), and Custom Endpoints.
- **Soft Pause Support**: Seamlessly detects when the desktop PC soft-pauses dictation (for gaming or manual user pause). The app treats the server as reachable (preventing false Tailscale drops), retains the recorded audio, and automatically transcribes and pastes once the desktop PC resumes.
- **Camera QR Code Scanner**: Integrated Google Code Scanner for point-and-scan camera onboarding directly from the desktop installer or settings QR code, with zero camera permissions needed in the app manifest (plus manual paste fallback).
- **Desktop-Mirroring 4-Tab Interface (Samsung One UI Dark & Teal `#2FB79A`)**:
  - *Transcription*: Camera QR pairing, provider presets, server address, API key, model selection card (read-only for Self-Hosted, card dropdown for Groq/OpenAI, free text for Custom), and Test Connection button with contextual status messages.
  - *Words & Triggers*: Shared vocabulary hints with real-time character budget counter, read-only "from name corrections" server list, "Heard as → Write as" name corrections (synced from desktop on Self-Hosted, editable locally on Cloud), and informational display of voice edit triggers.
  - *AI Editing*: Dictation cleanup toggle, punctuation rules, rewrite style, tone control (neutral, professional, casual, friendly), conciseness/shorten, emoji insertion, and cloud chat completion configuration.
  - *Phone*: Injection mode (Accessibility auto-paste vs Clipboard chip), contextual permission shortcuts (Accessibility, Appear on Top, Internal Audio), and device behavior switches (Auto-restart Tailscale, Boot launch).
- **Voice Edit Commands ("prompt AI")**: Real-time voice commands ([`contracts/03-edit-commands.md`](file:///contracts/03-edit-commands.md)) triggered by saying *"prompt AI"* (with optional *"end prompt"*). Injects the transcription directly into the LLM edit pipeline and pastes the resulting answer.
- **Strict Hint Echo Sanitization**: Conforms to [`contracts/01 §10`](file:///contracts/01-transcription-api.md): preserves natural spoken phrases containing hint words while reliably stripping degenerate 3+ repetition hallucination loops caused by silence.
- **Hardware-Backed AES-256 Encryption (`EncryptedSharedPreferences`)**: API keys and server endpoints are encrypted at rest using authenticated AES-256-GCM through the Android KeyStore hardware security module (Snapdragon / StrongBox / Knox), with `android:allowBackup="false"` to prevent secret leakage via USB ADB or cloud backups. Includes automatic legacy migration that sanitizes unencrypted files.
- **Instant Auto-Save**: Settings, switches, and inputs persist immediately upon change or when navigating away — no manual "Save" button required.
- **Two-Way Shared Vocabulary Sync (`GET / POST /v1/vocabulary`)**: Seamlessly connects to the desktop Head PC shared vocabulary list; local additions are deduplicated case-insensitively and pushed to the desktop server via `POST /v1/vocabulary`, merging phone and desktop lists so newly typed words are never overwritten or clobbered.
- **Optional Dictation Cleanup**: Applies post-transcription cleaning (removes filler sounds, applies self-corrections, converts spoken punctuation to formatting) via `processing=clean` for Self-Hosted or client-side LLM for cloud presets, using canonical shared rules ([`contracts/02-cleanup.md`](file:///contracts/02-cleanup.md)).
- **Dual Injection Modes**:
  - *Accessibility Service*: Instant auto-paste into active input fields.
  - *Clipboard Fallback*: Privacy-friendly alternative for users who prefer not to grant Accessibility permissions.
- **Quick Permission Shortcuts**: Direct one-tap buttons in settings to jump straight to Accessibility Settings, "Appear on Top" (Overlay) permission, and Internal Audio capture.
- **Tailscale Health & Auto-Recovery**: Monitors connection health and automatically dispatches recovery intents to Tailscale when VPN drops occur.
- **Reboot Auto-Start**: Configurable option to launch the floating button automatically on device boot.

## Setup & Configuration

1. Install and open **Whisper Unchained**.
2. In the **Transcription** tab, tap the prominent **Scan Now** button inside the hero card at the top to scan the QR code displayed by your desktop server installer or desktop Settings window:
   ```json
   { "v": 1, "base_url": "http://100.x.y.z:5000/v1", "api_key": "wr_sec_...", "model": "large-v3-turbo", "name": "Desktop" }
   ```
3. Or configure manually: choose **Self-Hosted**, **Groq Cloud**, **OpenAI**, or **Custom Endpoint**.
4. Tap **Test Connection** to verify that your server or provider responds.
5. In the **Phone** tab, grant required permissions (Microphone, Notifications, and Display over other apps), and enable **Whisper Unchained Dictation** in Accessibility Settings.
6. All changes auto-save immediately.

## Building from Source

```bash
git clone https://github.com/Dominic-Shirazi/Whisper-Unchained-Android.git
cd Whisper-Unchained-Android
./gradlew test assembleDebug
```

Open in Android Studio (Ladybug or newer) for full IDE support.

## License

MIT — see [LICENSE](LICENSE).

## Attributions

See [ATTRIBUTIONS.md](ATTRIBUTIONS.md) for third-party library licenses.
