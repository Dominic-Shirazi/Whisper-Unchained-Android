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

- Android 12 or newer (API 31+)
- A self-hosted Whisper server (such as [Whisper Unchained Server](https://github.com/Dominic-Shirazi/Whisper-Unchained-Server) or Speaches), cloud provider (Groq, OpenAI, OpenRouter), or local on-device transcription via sherpa-onnx (Parakeet TDT 110M int8)
- Network access to the server (if using PC/cloud) — [Tailscale](https://tailscale.com/) works seamlessly for remote access

## What's New in v1.3 (Build 37)

- **Pre-Collapsed Skill Library List with Search & Dynamic Category Filters (F-76)**:
  - Redesigned `LibraryBrowserDialog` (`WordsTriggersScreen.kt`) as a full-screen `640.dp`-constrained view where every skill starts collapsed to a single line (name + chevron + `✓` when already added, model note on the right or stacked underneath on narrow screens) with TalkBack labels (`"<name>, <model note>, collapsed, double tap to expand"`).
  - Tapping a skill opens its description, `Say: "…"`, `"Show the instruction"` toggle, and `Add` / `✓ Added` / `Update` button in place.
  - Added collapsible category headings with counts (open by default), a live search box, a `"Small local models only"` filter chip, dynamic category filter chips read directly from `library.json`, an empty-results state, and `rememberSaveable` state preservation across rotation and fold/unfold.

## What's New in v1.3 (Build 36)

- **Fold Crash Fix, Smooth Dragging, Real IME-Aware Edit Box & Sideload Restricted Settings Guidance (F-75)**:
  - **Skill Picker Crash Fix**: Replaced the Service-context `AlertDialog` in the recording Style Sheet (`FloatingButtonService.buildStyleSheetCard`) with inline expandable skill rows inside the sheet card, eliminating the `WindowManager$BadTokenException` crash when selecting a skill.
  - **Sideload Restricted Settings Guidance & Restart Button**: Added a plain-language `"Allow restricted settings"` explanation and `"Open App info"` button in the Full flavor (`IntroScreen`, `PermissionsScreen`, and `FloatingButtonScreen`) when Accessibility is off, and updated `"Restart floating button"` to detect when Accessibility is off and lead the user straight to Accessibility Settings.
  - **IME-Aware Edit Box Positioning**: Replaced the fixed top margin on the typed instruction overlay with a padded host driven by real `WindowInsets.Type.ime()` + `AccessibilityWindowInfo.TYPE_INPUT_METHOD` window bounds (and re-applied on `onConfigurationChanged`), keeping the Edit box above the keyboard on Fold inner/cover displays and phones even when the keyboard is already open.
  - **Smooth Floating Button Drag & Pill Performance**: Ignored own-overlay `TYPE_WINDOW_CONTENT_CHANGED` events, paused content-changed accessibility work while dragging (`FloatingButtonService.isDraggingOverlay`), coalesced content-changed focus checks to 250 ms, removed synchronous accessibility IPC from `ACTION_DOWN`, and fast-pathed `getSelectedText()` when the focused field has a collapsed cursor.

## What's New in v1.3 (Build 35)

- **Idle Highlight Pill Renamed to Pencil + Word Count (F-74)**:
  - The idle pill that appears under the floating button when text is highlighted (in self-hosted PC mode) now displays `✎ 1 word` / `✎ N words` instead of `Type`, updating live as the selection changes, matching the recording highlight chip geometry for a zero-jump transition, and announcing `"Edit N highlighted words by typing"` to TalkBack.
  - Tapping the idle pill continues to open the typed instruction box with `"Include the highlighted text (N words)"`; tapping the recording chip continues to open the `"Your N highlighted words"` Rewrite / Type-over sheet.

## What's New in v1.3 (Build 34)

- **Remembered Highlight Across Type Pill & Mic Tap + Blinking Cursor in Type Box (F-73)**:
  - `DictationAccessibilityService` and `FloatingButtonService` now remember the highlighted text, selection indices, package, window ID, and node at the moment the `"Type"` pill is shown (`RememberedHighlight`), capture it on `ACTION_DOWN` and `startRecording()`, and validate cached nodes via `isEditableNode()` (covering Jetpack Compose and WebView editors where raw `node.isEditable` is `false`), so tapping the floating mic button always captures the exact same highlight as the `"Type"` pill even if touching the overlay or a floating Copy/Paste toolbar popup collapses the live selection.
  - `SettingsRepository.applyQrPayload` now resets `transcriptionTarget` to `TranscriptionTarget.PC.id` when pairing a PC via QR code.
  - Configured the typed instruction box (`EditText` in `FloatingButtonService.openTypedInstructionPanel`) with an explicit high-contrast blinking cursor drawable (`#2FB79A`) and upper-screen placement so the soft keyboard never clips the `"Include the highlighted text"` checkbox.

## What's New in v1.3 (Build 33)

- **Foldable & Large-Screen Adaptive Tier 3 Readiness (F-71 & F-72)**:
  - Configured `MainActivity` (`android:configChanges`) and `FloatingButtonService.onConfigurationChanged` so active recordings, on-device model downloads, scroll positions, half-typed text inputs, and floating overlay sheets survive rotation, fold/unfold (1:1 and 4:3 inner displays $\leftrightarrow$ 10:16 outer display), and multi-window split-screen resizing without losing state or stranding the floating button off-screen.
  - Constrained `WordsScreen` and `TriggersScreen` (`Skills`, `Phrase Editor`, `Skill Editor`) to a centered `640.dp` max width on wide foldable displays, made all 7 `IntroScreen` pages vertically scrollable for landscape/split-screen, and switched the Phrase Editor 3-colour example to `FlowRow` so it wraps cleanly at half-width split-screen.

## What's New in v1.3 (Build 32)

- **Android 12+ Compatibility & Full-ABI Play Bundle (F-70)**:
  - Lowered `minSdk` from `34` to `31` (Android 12 or newer), expanding compatibility to Android 12, 12L, and 13 devices alongside Android 14+.
  - Configured the Google Play App Bundle (`bundlePlayRelease`) to carry all native ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` from `sherpa-onnx-2.0.1.aar`, including 32-bit `armeabi-v7a` on-device speech support) while keeping the standalone GitHub APKs (`assemblePlayRelease` and `assembleFullRelease`) trimmed to `arm64-v8a` only.

## What's New in v1.3 (Build 31)

- **Highlight Chip & Sheet During Recording (F-66 & F-66b)**:
  - When a recording starts with text highlighted in the focused field, a compact chip appears under the recording mic button:
    - **Rewrite mode (default when PC AI is available)**: Shows `✎ 113 words` (`1 word` for 1), hides the dictation style pill, and announces `"113 highlighted words will be rewritten"` to TalkBack.
    - **Type over mode (when chosen in the sheet, or automatically in on-device / cloud-key mode)**: Shows `113 words` struck through, restores the dictation style pill, and announces `"113 highlighted words will be typed over"` to TalkBack.
  - **Tap the chip while recording** to open the `"Your 113 highlighted words"` sheet without stopping the recording, offering two plain-language choices (`"Rewrite them"` vs `"Type over them"`) when PC AI is available, or explaining that what you say replaces the highlighted words when in on-device/cloud mode.
- **Honest Cached Text Box Lifecycle on Paste (F-68)**:
  - `DictationAccessibilityService` clears cached `focusedNode` and `lastSelectionNode` references whenever the active window/package changes (`TYPE_WINDOW_STATE_CHANGED`) or when focus leaves an editable field, and refreshes (`refresh()`) any cached node before paste to verify it is still both `isFocused` and `isEditable` — preventing text from ever being pasted into a text box the user has left while preserving fallback paste for apps where `findFocus(FOCUS_INPUT)` returns `null`.

## What's New in v1.3 (Build 30)

- **Voice Instruction Editing over Highlighted Text (F-65 / Contract 01 §14)**:
  - When a recording starts with text highlighted in the focused field (in self-hosted PC mode), the recording and highlighted selection are sent to `POST /dictate/v1/edit_selection`, and the AI's edited response replaces the highlight.
  - When the server's answer is unchanged or `edited: false` (on both the voice selection path and the typed instruction path), the app does not paste over the highlight and instead displays `"The AI made no changes."`.
  - When replacing an active highlight, `DictationAccessibilityService.replaceOrInsertText` prefers `ACTION_PASTE` before falling back to `ACTION_SET_TEXT` so rich-text editors (such as Gmail) preserve formatting outside the highlighted range.

## What's New in v1.3 (Build 29)

- **Skills UI Remodel (F-62 & F-63)**:
  - Renamed user-facing "Triggers" / "Custom triggers" to **Skills** ("New skill", "Edit skill", "Skill library", "Type an instruction, or / for your skills", and "Skill" picker in the dictation style sheet) while keeping all wire formats, endpoints (`/v1/custom_triggers`, `/v1/trigger_phrases`, `trigger=<id>`), and contracts untouched.
  - **Skills Screen**: Starts with an "Ask the AI to change your text" card dynamically built from the user's first AI-edit phrase (`GET /v1/trigger_phrases`) and a "Choose your own phrase" button, followed immediately (visible without scrolling) by "Your skills" (`+ New skill`, `Browse the library`, and compact rows showing `say "<first phrase>" (+N more)`).
  - **Phrase Editor Screen ("What you say to ask the AI")**: Dedicated screen featuring a 3-colour single-sentence portrait phone example (`You say: "send me the report by friday prompt AI make it polite"`) with centred labels (`message` in `#6CB4FF`, `phrase`, `directions` in `#F0B44C`), optional "End of instruction" help text, editable phrase list, "+ Add a phrase", and "Restore original phrases".
  - **New Skill Screen**: Dedicated screen housing the explanation of skills (`The phrase can be anything you like: "prompt intake", "skill intake", or your own words.`), the Client intake sheet example, and "How skills work" notes.
- **Google Play Store Readiness (F-64)**:
  - Signed Play release Android App Bundle (`bundlePlayRelease`, `targetSdk = 36`, `minSdk = 34`, `versionCode = 29`).
  - Added account-free **Delete all local data** action in `About` (`SettingsRepository.clearAllLocalData`) to wipe all preferences, encrypted API keys, cached vocabulary/skills, downloaded on-device models, and audio cache.
  - Enforced prominent disclosure and affirmative consent dialog before opening Android Accessibility Settings across `IntroScreen`, `PasteModeScreen`, and `PermissionsScreen`.
  - Generated Play Store listing screenshots on sample data under `docs/store/`.

## What's New in v1.3 (Build 28)

- **Selection-Gated Type Pill & Button Options Menu (F-59)**: The floating "Type" pill appears only when text is currently highlighted in the focused field, automatically hiding when selection collapses without moving the button. Long-pressing the floating button opens an options menu with "Type an instruction" (opening the typed instruction panel directly without selection checkbox), "Hide button", and "Settings".
- **Dedicated Words & Triggers Split (F-60)**: Split the unified "Words & Triggers" screen into two separate dedicated destinations with no tabs:
  - **Words**: Vocabulary helper words and Corrections & shortcuts with instant 2-way PC synchronization and offline mutation replay.
  - **Triggers**: Built-in trigger phrases backed by `GET`/`POST /v1/trigger_phrases` with "Keep at least one" edit phrase guard, and Custom triggers with interactive builder and library browser.
- **Refined Triggers Screen Layout & Examples (F-61)**: Updated Triggers screen top-to-bottom layout starting immediately with "Trigger phrases" and an inline Windows-style example ("prompt AI make it polite"), scroll-to "Custom triggers ↓" navigation link, clear explanation of custom triggers with the "prompt intake" example, direct link to add "Client intake sheet" from the library, and pointer to mean mode in the library under Fun.
- **Grouped Home Navigation with Dynamic Subtitles (F-60 §2)**: Reorganized Home screen into labelled Material 3 sections matching the desktop settings layout:
  - **Your text**: Words (with live counts e.g. "42 words, 6 corrections"), Triggers (e.g. "hey Whisper · 3 custom"), and AI Editing.
  - **The button**: Floating button and Paste into apps.
  - **This phone**: Permissions and Transcribe audio playing on this phone.
  - **About**: Privacy policy, source code, and version details.

## What's New in v1.3 (Build 27)

- **Typed Instruction Pill & Panel (F-54)**: Tap the "Type" pill under the idle floating mic to type an instruction when you'd rather not speak. Features autocomplete trigger suggestions (`/` or prefix search), whole-phrase detection, highlighted text selection inclusion/replacement, and in-memory draft preservation on dismiss or failure.
- **Custom Triggers & Shared Library (F-53)**: Custom triggers screen backed by `GET`/`POST /v1/custom_triggers` with interactive multi-line prompt editing, "Write your own" trigger builder, and upstream shared library browser (`library.json`) grouped by category with model requirements and one-tap Add / ✓ Added / Update actions.
- **Dictation Style Sheet Trigger Picker (F-54 §5)**: Select any enabled trigger from a dropdown directly in the recording style sheet to apply it to that dictation only, automatically greying out cleanup rows with "The trigger decides how this one comes out."
- **Corrections & Shortcuts (F-50)**: Renamed "Name corrections" to "Corrections & shortcuts" with "When I say → Type this" labels, expanded character limit up to 200 characters with commas allowed, multi-line wrapping, and rotating placeholder examples.
- **Pairing Code Alternate Addresses & Failover (F-55)**: QR codes support optional `alt_base_urls` for home and away connectivity, with automatic sequential failover to whichever address responds and memory of the last working address. The Home Wi-Fi warning is suppressed when any stored address is a Tailscale address.
- **Refreshed Button Colors & Launcher Icons (F-56)**: Periwinkle ready tile (`#8C9EFF`), recording tile (`#1B1D2A`), and new standalone pale periwinkle thinking state (`#E3E8FF` / `whisper_remote_thinking.png`) at full opacity.
- **Model Catalog Alignment & NVIDIA Open Model License (F-52 & F-57)**: Curated on-device model catalog to 3 models (`parakeet-unified-en-0.6b-int8` recommended, `110m`, and `v3-multi`), purged legacy v2 models, and added NVIDIA Open Model License notices across About, ATTRIBUTIONS, and download UI.
- **Always Send `processing` & Mean Mode Migration (F-49 & F-58)**: Explicitly sends `processing` (`clean` or `full`) on all self-hosted transcription paths. Mean mode is removed from core tone options and migrated to custom trigger `mean-mode`.

- **Interactive Helper Words & Name Corrections Editor**: Edit vocabulary helper words and "Heard as → Write as" name corrections directly from the phone. Features row tap-to-edit dialogs, inline add fields, "+ Add a correction" dialogs, and instant removal with ~4s Undo snackbars.
- **Floating Mic Retention on Silence (v1.3.0, versionCode 26)**: Floating mic button reliably returns to `IDLE` ready to record if the text box remains focused after a silent recording, quick tap, or empty response instead of vanishing.
- **First-Run Setup & Sync Improvements (v1.3.0, versionCode 26)**: Accessibility setup instructions updated to match Samsung One UI navigation (`Installed apps` → `Whisper Unchained Dictation`); AI Editing switch automatically syncs PC `cleanup_always` on pairing and `/health` checks; and floating service auto-starts on setup finish, app resume, and accessibility connection.
- **Hardened Offline Replay Queue (v1.3.0, versionCode 25)**: Replays queued mutations in strict order on reconnection. The 3-strike rule is narrowed strictly to 2xx responses with malformed/unparseable JSON bodies (`ParseError`). Authentication failures (401/403) preserve the queue indefinitely and prompt the user to re-scan pairing codes once. Server restarts (5xx, 429) and network blips keep mutations intact without counting strikes.
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
