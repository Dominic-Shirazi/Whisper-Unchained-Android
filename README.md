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
- A self-hosted Whisper server (such as [Whisper Unchained Server](https://github.com/Dominic-Shirazi/Whisper-Unchained-Server) or Speaches), cloud provider (Groq, OpenAI, OpenRouter), or local on-device transcription via sherpa-onnx (Parakeet TDT 110M int8)
- Network access to the server (if using PC/cloud) — [Tailscale](https://tailscale.com/) works seamlessly for remote access

## What's New in v1.3 (On-Device Transcription & Word List Management)

- **Interactive Helper Words & Name Corrections Editor**: Edit vocabulary helper words and "Heard as → Write as" name corrections directly from the phone. Features row tap-to-edit dialogs, inline add fields, "+ Add a correction" dialogs, and instant removal with ~4s Undo snackbars.
- **Offline Mutation Queueing & A11y Badging**: Edits made while disconnected from the PC are queued locally in exact order and automatically replayed on the next successful `/health` check. Queued items are badged with clear textual `"waiting for your PC"` labels (text-based for color-blind accessibility).
- **Immediate Local Reflection**: Edits and queued mutations immediately update cached server vocabulary and name corrections maps so on-device transcription (`sherpa-onnx`) and `TextRules` apply the updated spellings in real time before reaching the PC.
- **Center-Aligned Name Correction Columns**: "Heard as → Write as" rows align the replacement arrows in a straight vertical column for consistent scanning on mobile screens.
- **Local Offline Transcription Engine**: Embedded `sherpa-onnx` (2.0.1) engine running quantized NVIDIA Parakeet TDT 110M int8 (NeMo transducer) entirely on-device with zero network requests or API keys required.
- **Silero VAD Chunking**: Bounded audio chunking using Silero VAD (cutting speech into $\le 25\text{ s}$ pause-separated segments) for reliable decoding of arbitrarily long dictations.
- **Dynamic Hotwords & Beam Search**: Automatic compilation of shared vocabulary and name correction target words into hotword definitions with `modified_beam_search` (score 2.0) and graceful fallback to greedy search.
- **Canonical Text Rules Port**: Full Kotlin port of server-side name corrections ("heard as → write as", whole-word, case-insensitive) and standalone filler sound removal (`um`, `uh`, `hmm`), verified 100% against canonical server test suites.
- **Target Selection & Automatic Fallback**: Flexible dictation target choices (*On this phone* / *My PC* / *Cloud service*) with seamless on-device fallback when network or desktop endpoints are unreachable.
- **Resumable Model Downloads & Management**: Dedicated in-app model manager with HTTP Range resumable downloads (`.part` files), SHA-256 checksum verification, Wi-Fi only toggle with mobile data override, and storage cleanup.
- **Out-of-the-Box Intro Sandbox**: Interactive first-run onboarding downloading default models in-place with instant local speech transcription—no PC or cloud accounts required out of the box.
- **Robust Fallback Retention**: Preserves audio files as pending retries when on-device fallback fails (automatically polling and sending when the PC returns), with instant phone fallback when the PC is paused or loading models.
- **Optimized 64-bit ABI Packaging**: Streamlined native packaging (`arm64-v8a`) reducing release APK sizes by ~97 MB (from 156.8 MB down to 59.2 MB).
- **End-to-End Encrypted PC Connection (Pinned TLS)**: Zero-CA self-signed TLS verification using leaf certificate SHA-256 fingerprint matching (`cert_sha256`) from QR pairing payload v2, hostname verification anchoring to the cert pin, explicit certificate change detection ("Your PC's security certificate changed. If you reinstalled Whisper Unchained Server, scan its pairing code again."), cleartext traffic disabled in the Play build (`usesCleartextTraffic="false"`), and backward-compatibility upgrade banner for older HTTP pairings (Contract 06).
- **Home Wi-Fi Diagnostics & Setup Guidance**: Automatic detection of non-Tailscale LAN server pairings with one-tap Tailscale setup guidance, and post-scan connection diagnostics (Tailscale not installed / not connected / PC unreachable) (Contract 06 & F-37/F-38).
- **Play Release & S25 Support**: Official `play` flavor distribution (`com.cyrus_studio.whisperunchained`, versionCode 20, versionName 1.3.0) featuring 3-choice engine picker (PC QR, Cloud API keys, or offline on-device), read-only demo practice box with floating mic button, scrollable accessibility disclosure, and automatic phone model backup cards.

## What's New in v1.2 (UI Makeover + Intro Sandbox)

- **Complete Jetpack Compose & Material 3 Rebuild**: Replaced the 1,478-line XML activity with a clean, modular Compose architecture adhering to Samsung One UI principles (grouped borderless cards, 26dp rounded corners, pure-black `#000000` dark mode, `#3E6FD8` dynamic primary accent fallback, zero borders, zero shadows).
- **7-Page First-Run Intro Sandbox**: Full-screen interactive onboarding with segmented progress indicators, in-place microphone permission flow, app logo practice button with direction indicator (`← Tap here to practice`), live microphone recording (`AudioRecord`), and real transcription through the configured server.
- **Seamless Setup Navigation & Direct Battery Exemption**: Automatic advance from QR scanning to practice; direct system battery optimization exemption dialog avoiding buried app lists; and incomplete setup card tap navigation straight to missing permissions or server setup.
- **Overlay & IME Keyboard Layering**: Recording style sheet rendered on top of active soft keyboards with `FLAG_ALT_FOCUSABLE_IM` without stealing text input focus.
- **Color-Blind Safe Indicators**: All connection and permission statuses feature paired icons and explicit textual labels (e.g. "Allowed ✓" / "Not allowed") ensuring WCAG compliance without relying on color alone.
- **Google Play Policy Compliance**: Strict exclusion of sensitive password, PIN, and payment fields from `DictationAccessibilityService`; immediate deletion of temporary audio files from app-private `cacheDir`; re-showable prominent Accessibility disclosure; and direct links to Privacy Policy (`https://whisperunchained.cyrus-studio.com/privacy/`).
- **Roborazzi Screenshot Tests**: Automated JVM-based screenshot regression suite running on Robolectric across S25 phone, Galaxy Fold inner, and Galaxy Fold cover screen dimensions in both light and dark modes.

## Distribution & Build Flavors (v1.2)

Whisper Unchained is built with two distinct product flavors to ensure full Google Play policy compliance while preserving advanced internal-audio transcription for sideloaded installations:

| Flavor | Package ID | App Name | Internal Audio (`FOREGROUND_SERVICE_MEDIA_PROJECTION`) | Target Audience |
|---|---|---|---|---|
| **`play`** | `com.cyrus_studio.whisperunchained` | Whisper Unchained | **No** (manifest stripped, UI hidden, no projection code reachable) | Google Play Store distribution |
| **`full`** | `com.cyrus_studio.whisperunchained.full` | Whisper Unchained Full | **Yes** (audio capture from media playback, e.g. videos/podcasts; phone calls excluded) | GitHub Releases / F-Droid / Sideloading |

Both flavors include the Accessibility Service with prominent disclosure for seamless text injection. Both flavors share identical signing keys and can be installed side-by-side on the same device.

## Permissions

| Permission | Flavor | Purpose |
|---|---|---|
| `RECORD_AUDIO` | Both | Capture microphone input |
| `SYSTEM_ALERT_WINDOW` | Both | Display the floating microphone button over other apps |
| `POST_NOTIFICATIONS` | Both | Foreground service notifications (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **Full only** | Direct battery optimization exemption prompt to prevent service sleep (Play build uses standard settings list per policy) |
| `RECEIVE_BOOT_COMPLETED` | Both | Optionally auto-start the floating button service on device reboot |
| `VIBRATE` | Both | Haptic ticks on recording style pill navigation |
| `FOREGROUND_SERVICE_MICROPHONE` | Both | Microphone foreground service execution |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | **Full only** | Transcribe audio playing on this phone |
| Accessibility Service | Both | Automatically paste transcribed text into the focused input field |

## Key Features

- **Standard OpenAI API (`/v1/audio/transcriptions`)**: Interoperable across Self-Hosted, Speaches, Groq, OpenRouter, and custom endpoints.
- **First-Run Setup Checklist (Contract F-16)**:
  - 4-step live checklist card at the top of the app guiding users through Microphone permission, PC/Cloud connection, "Appear on top" overlay permission, and Accessibility/Clipboard text injection.
  - Live checkmarks with explicit "Done" text (color-blind accessible), auto-hiding when all steps are completed, and a subtle "Setup" reopen link.
  - Proactive tap protection: tapping the floating button when a prerequisite is missing presents an in-place prompt with a one-tap direct fix (grant permission, open settings, or switch to clipboard mode).
- **Smart AI Cleanup Default (Contract K-10)**:
  - Intelligently sets AI Cleanup default based on server or model capability (matching desktop `/health -> cleanup_always` on Self-Hosted, or default ON when cloud editor key is configured).
  - Clear contextual explanation note below the switch ("Following your PC's setting" / "On because an AI model is set up" / "Off: no AI model set up yet").
  - Explicit per-dictation `cleanup=on` or `cleanup=off` sent once the user manually chooses a preference.
- **Provider Presets**: Instant configuration ranked by Privacy > Price > Speed: Self-Hosted (100% private, $0), Groq Cloud (Zero training, ~$0.04/hr), Together AI (Zero training, ~$0.006/min), OpenAI (Commercial non-training, 30-day abuse logs), OpenRouter (Multi-model proxy aggregator), and Custom Endpoints, complete with in-app info modals `(i)` detailing privacy, pricing, and speed.
- **Soft Pause Support**: Seamlessly detects when the desktop PC soft-pauses dictation (for gaming or manual user pause). The app treats the server as reachable (preventing false Tailscale drops), retains the recorded audio, and automatically transcribes and pastes once the desktop PC resumes.
- **Camera QR Code Scanner**: Integrated Google Code Scanner for point-and-scan camera onboarding directly from the desktop installer or settings QR code, with zero camera permissions needed in the app manifest (plus manual paste fallback).
- **Desktop-Mirroring 4-Tab Interface (Samsung One UI Dark & Teal `#2FB79A`)**:
  - *Transcription*: Camera QR pairing, provider presets, server address, API key, model selection card (read-only for Self-Hosted, card dropdown for Groq/OpenAI, free text for Custom), and Test Connection button with contextual status messages.
  - *Words & Triggers*: Shared vocabulary hints with real-time character budget counter, read-only "from name corrections" server list, "Heard as → Write as" name corrections (synced from desktop on Self-Hosted, editable locally on Cloud), and informational display of voice edit triggers.
  - *AI Editing*: Dictation cleanup toggle, punctuation rules, rewrite style, tone control (neutral, professional, casual, friendly), conciseness/shorten, emoji insertion, and cloud chat completion configuration.
  - *Phone*: Injection mode (Accessibility auto-paste vs Clipboard chip), contextual permission shortcuts (Accessibility, Appear on Top, Internal Audio), and device behavior switches (Auto-restart Tailscale, Boot launch).
- **Voice Edit Commands ("prompt AI")**: Real-time voice commands ([`contracts/03-edit-commands.md`](file:///contracts/03-edit-commands.md)) triggered by saying *"prompt AI"* (with optional *"end prompt"*). Injects the transcription directly into the LLM edit pipeline and pastes the resulting answer.
- **Strict Hint Echo Sanitization**: Conforms to [`contracts/01 §10`](file:///contracts/01-transcription-api.md): preserves natural spoken phrases containing hint words while reliably stripping degenerate 3+ repetition hallucination loops caused by silence.
- **Recording Style Pill & Sheet (Contracts F-6 & F-7)**:
  - While recording, the mic label moves above the button, and an interactive style pill appears beneath it.
  - Swipe horizontally on the pill (LEFT = "do less", RIGHT = "do more") to adjust Grammar & flow (`keep` < `fix` < `polish`), Conciseness (`off` < `light` < `strong`), or Punctuation (`minimal` < `light` < `standard`).
  - Swipe vertically (UP / DOWN) on the pill to smoothly cycle active style dimensions with ~180ms slide/fade animation and haptic feedback.
  - Tap the pill to expand a full-screen blurred/dimmed style sheet displaying all options (Grammar & flow, Conciseness, Punctuation, and Tone with independent Mean tone and auto-advance to Fix grammar for other tones), live elapsed recording timer, and a large Done button (with outside taps fully consumed so background text fields maintain cursor position and focus).
  - Fully color-blind accessible (state indicated by physical position dots and checkmarks, never color alone) and integrated with Android TalkBack custom accessibility actions.
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

# Run unit tests
./gradlew test

# Build release APKs for both flavors
./gradlew :app:assemblePlayRelease :app:assembleFullRelease
```

Release APKs are output to:
- Play flavor: `app/build/outputs/apk/play/release/app-play-release.apk`
- Full flavor: `app/build/outputs/apk/full/release/app-full-release.apk`

Open in Android Studio (Ladybug or newer) for full IDE support.

## License

MIT — see [LICENSE](LICENSE).

## Attributions

See [ATTRIBUTIONS.md](ATTRIBUTIONS.md) for third-party library licenses.
