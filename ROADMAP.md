# WhisperRemote: Architecture, Server Specification & Development Roadmap

This document outlines the unified vision, architecture, and step-by-step roadmap to align **WhisperRemote for Android** with the **Head PC Desktop Server**.

---

## 1. Project Vision

* **Core Goal:** A fast, private, open-source voice dictation system that works seamlessly alongside any Android keyboard, offering an open alternative to commercial services like Wispr Flow.
* **Cost & Privacy:** $0 subscription, zero telemetry, zero contact harvesting, and zero third-party cloud lock-in. Audio routes exclusively to the user's private hardware or user-chosen pay-as-you-go API.
* **Cross-Project Alignment:** The Android app and Desktop server share an OpenAI-compatible API contract (`/v1/audio/transcriptions`), ensuring interoperability across self-hosted hardware and low-cost cloud providers.

---

## 2. Desktop Server & Installer ("Head PC") Specification

> **Build on what exists — don't rebuild the wheel.** The Head PC server is the existing
> [Whisper-Unchained-Server](https://github.com/Dominic-Shirazi/Whisper-Unchained-Server) repo (faster-whisper + FastAPI,
> Windows service, tray listener, LLM voice-edit layer). Phase 2 extends it rather than starting a new server.
> For plain transcription, [Speaches](https://github.com/speaches-ai/speaches) (formerly `faster-whisper-server`)
> already provides an OpenAI-compatible faster-whisper server with model download and auto-unload. Our
> differentiators are the dictation client, voice edit commands, and confidence filtering; any WhisperRemote
> client should also work against Speaches, Groq, or OpenAI unchanged.

When deploying on the Head PC, an interactive installer script (`setup_server.py` / `install.bat`) will guide the user through setup and automated configuration.

### 2.1 Hardware & Environment Inspection
The installer will run automated pre-flight checks:
* **GPU Detection:** Checks for NVIDIA CUDA (via `torch.cuda.is_available()`), AMD ROCm, or fallback to CPU.
* **VRAM Assessment & Recommended Model:**
  * `< 4 GB VRAM / CPU:` `base` or `small` with `faster-whisper` INT8 quantization.
  * `4 GB – 8 GB VRAM:` `medium` or `large-v3-turbo` with FP16/INT8.
  * `8 GB+ VRAM:` `large-v3-turbo` or full `large-v3` with FP16.
* **System Dependencies:**
  * Checks for `ffmpeg` (required for audio decoding).
  * Detects `tailscale` status and identifies the device's Tailscale 100.x.y.z IP.

### 2.2 Interactive Walkthrough
1. **Model Selection:** Recommends the optimal model tier based on detected VRAM.
2. **Security & API Key:**
   * Prompt: *"Do you want to secure this server with an API key? (Recommended) [Y/n]"*
   * Automatically generates a cryptographically secure token (or allows entering a custom key).
3. **Network Binding:**
   * Prompts whether to bind to Tailscale IP, Local LAN, or `0.0.0.0`.
4. **Android Pairing (Terminal QR Code):**
   * Generates a QR code in the terminal containing JSON configuration:
     ```json
     {
       "endpoint": "http://100.x.y.z:5000/v1/audio/transcriptions",
       "api_key": "wr_sec_...",
       "model": "large-v3-turbo"
     }
     ```
   * Scanning this inside the Android app instantly populates all server settings.

### 2.3 Server API Standard
The server will run via FastAPI / Uvicorn using `faster-whisper`:
* **Route:** `POST /v1/audio/transcriptions`
* **Form Parameters:**
  * `file`: Audio payload (`.wav`, `.m4a`).
  * `prompt` (optional): User vocabulary hints (e.g. `"Lesley, Ollama"`).
  * `model` (optional): Target Whisper model.
* **Authentication:** `Authorization: Bearer <API_KEY>` (if configured).
* **Response:** Standard JSON `{"text": "Transcribed string..."}`.

---

### 2.4 Model Picker (Desktop Settings UI)
A small picker in the desktop Settings window that lists the models, marks which are **already downloaded**
(✓, from the Hugging Face cache the *service* uses — note the LocalSystem service has its own cache separate
from the user's), downloads on demand with a progress bar, and switches the active model (with a warning that
dictation is offline for the reload, ~2 min on first download).

| Model | VRAM (approx, FP16) | CPU viable? | Languages | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `tiny` / `tiny.en` | ~1 GB | Yes, fast | Multi / English | Lowest accuracy; fine for quick notes |
| `base` / `base.en` | ~1 GB | Yes | Multi / English | |
| `small` / `small.en` | ~2 GB | Yes, usable | Multi / English | Best CPU-only choice |
| `medium` / `medium.en` | ~5 GB | Slow | Multi / English | Mostly superseded by turbo |
| `large-v3-turbo` | ~3–4 GB | No | Multi | **Default.** Near large-v3 accuracy, much faster; cannot translate |
| `distil-large-v3` | ~3–4 GB | No | English only | Very fast; English only |
| `large-v3` | ~5–10 GB | No | Multi | Highest accuracy, slowest |

* All Whisper models output **punctuation and capitalization natively**; that is not a differentiator between tiers.
* INT8 roughly halves VRAM at a small accuracy cost; the picker should offer `compute_type` (float16 / int8_float16 / int8).
* VRAM figures should be re-measured on real hardware before shipping (see the 2026-08 benchmark in Whisper-Unchained-Server).

### 2.5 Vocabulary Hints Across Providers
Faster-whisper's `initial_prompt` ("suggested words") has equivalents elsewhere, so the Android/desktop hints field maps per provider:

| Provider | Field | Notes |
| :--- | :--- | :--- |
| Self-hosted faster-whisper / Speaches | `prompt` (→ `initial_prompt`) | Same hallucination-loop risk; sanitizer applies |
| OpenAI (`whisper-1`, `gpt-4o-transcribe`) | `prompt` | Style/vocabulary hint |
| Groq (Whisper) | `prompt` | Limited to ~224 tokens |
| Deepgram (Nova-3) | `keyterm` | Separate keyword-boost list, not a prompt |
| AssemblyAI | `keyterms_prompt` | Keyword list |

### 2.6 Desktop Listener Hotkey Options
The listener is currently hard-wired to backtick (`` ` ``), which then has to delete the stray characters it typed.
Add a hotkey picker to the Settings window:
* **Presets:** `` ` `` (current), `Right Alt`, `Alt+Space`, `Ctrl+Alt+Space`, `F9`, `Pause/Break`, `Scroll Lock`, mouse side button (`XButton1`).
* **Custom:** "Press a key combo…" capture field.
* **Mode:** toggle (tap to start/stop, current) or push-to-talk (hold to record).
* Prefer keys that type nothing (F-keys, Pause, Scroll Lock, Right Alt) so no cleanup backspaces are needed; warn on combos that clash with common apps (e.g. `Alt+Space` opens the window menu / PowerToys Run).

### 2.7 "Free VRAM" Behavior
Today the tray's **Pause API (Free VRAM)** runs `net stop FasterWhisperAPI`: the server process exits and the Whisper model
is unloaded until **Resume API** is clicked (or the machine reboots and the service auto-starts). It does **not** depend on
restarting the UI. Gaps to fix:
* The Ollama edit model is pinned separately (`OLLAMA_EDIT_KEEP_ALIVE=-1`) and **stays in VRAM** after Pause. Pause should also unload it (`keep_alive: 0` request / `ollama stop`).
* Pause/Resume should be soft (an `/unload` + lazy reload endpoint) so no UAC prompt is needed.

### 2.8 Installer Plan (Dominic, 2026-11-10)
There is **no installer yet**: today a new user needs Python, the right GPU libraries, and the scripts in
`Whisper-Unchained-Server/scripts/`. The installer should:

* **Offer two paths up front:** *"Use recommended settings"* (one click, picks everything below automatically)
  or *"Walk me through it"*, which steps through the same pages as the desktop Settings window
  (Transcription, AI Editing, Hotkey, Phone, Startup), each with its plain-English intro. One source of truth:
  the wizard writes the same `.env` the Settings window edits.
* **Detect the GPU and recommend a model** (VRAM numbers from the Settings model picker):
  * NVIDIA ≥ 3 GB free VRAM → `large-v3-turbo` (default); ≥ 5 GB and multilingual accuracy wanted → offer `large-v3`.
  * NVIDIA < 3 GB → `small.en` (or `small` if a non-English language is chosen).
  * No usable GPU → CPU with `small.en` + int8, and say plainly it will be slower.
* **Install GPU support it needs:**
  * NVIDIA: install the CUDA 12 cuBLAS + cuDNN 9 runtime wheels (`nvidia-cublas-cu12`, `nvidia-cudnn-cu12`) into
    the venv instead of relying on a system-wide CUDA install (Head PC currently relies on the system one).
  * AMD: CTranslate2 reportedly gained ROCm support in 4.7 (early 2026), but on Windows it needs AMD's ROCm wheels and
    only covers RX 6000/7000 (RDNA 2/3). Treat as experimental; default AMD users to CPU unless they opt in.
    Long-term alternative: a whisper.cpp (Vulkan) backend, which runs on AMD/Intel GPUs on Windows.
* **Pre-download the chosen model** into `WHISPER_MODELS_DIR` before installing the service, so first start is fast.
* **Install the service** (NSSM, port from `.env`), the tray listener at login, and the right-click menu, with the
  server console window **hidden** (`API_VISIBLE=false`, now the default).
* **Finish on the Phone page:** show the pairing QR and the app download links.

**Transcription source (built 2026-11-10):** the desktop (and the installer) can transcribe on this PC, on another
PC running Whisper Unchained Server, or with a cloud service (Groq/OpenAI/OpenAI-compatible, user's key).
**Still to do for cloud mode:** "prompt AI" commands and dictation cleanup need an editor LLM; give the desktop
client the same cloud cleanup path the app gets (contract 02), using a chat endpoint + key the user picks.

**Play Store badge:** Google's official "Get it on Google Play" badge may be used to link to our own listing,
unaltered and with the attribution line ("Google Play and the Google Play logo are trademarks of Google LLC").
Add it to the desktop Settings / installer only once the listing is live; until then it stays a text link marked
"coming soon".

---

## 3. Android App Architecture & Settings UI

### 3.1 Settings Layout with Presets & Tooltips `(?)`

```
┌────────────────────────────────────────────────────────┐
│ Provider Preset                                        │
│ [ Head PC / Self-Hosted Server ▼ ]                     │
├────────────────────────────────────────────────────────┤
│ Server Endpoint (?)                                    │
│ [ http://100.x.y.z:5000/v1/audio/transcriptions      ] │
│                                                        │
│ API Key (?) [Optional]                                 │
│ [ ••••••••••••••••••••••••••••••••• ]                  │
│                                                        │
│ Model Name (?)                                         │
│ [ large-v3-turbo                                     ] │
│                                                        │
│ Custom Vocabulary / Hints (?)                          │
│ [ Lesley, Ollama, Kubernetes, WhisperRemote          ] │
│ [X] Auto-trim repeated hint hallucinations             │
├────────────────────────────────────────────────────────┤
│ Text Injection Mode                                    │
│ (o) Accessibility Service (Instant auto-paste)         │
│ ( ) Clipboard Fallback (1-tap chip on keyboard)        │
├────────────────────────────────────────────────────────┤
│ Behavior                                               │
│ [X] Start floating button on phone reboot              │
├────────────────────────────────────────────────────────┤
│ [ Phase 3: Download On-Device Offline Model ]          │
│ ⚠ High battery usage. Recommended for modern flagships.│
│ [ Download Whisper Tiny (75 MB) ]                      │
└────────────────────────────────────────────────────────┘
```

#### Provider Presets & Help Tooltips:
* **Head PC / Self-Hosted:** Points to home server via LAN or Tailscale. Zero cost.
* **Groq Cloud:** Ultra-fast cloud transcription (~200ms latency, ~$0.03/hr of audio). Tooltip links to Groq Console to grab a free/low-cost API key.
* **OpenRouter / OpenAI / Together:** Standards-compliant OpenAI endpoints using personal pay-as-you-go tokens.
* **Ollama / LocalAI:** Local desktop inference with no auth required.

### 3.2 Client-Side Hint Hallucination Sanitizer
Whisper models frequently regurgitate initial prompt words at the end of audio when there is background silence.
* **Solution:** A client-side cleaning function `sanitizeTranscript(rawText, promptHints)` runs before text injection.
* It strips exact trailing matches and repetitive loops of prompt words regardless of whether the user is connected to Head PC, Groq, or OpenAI.

### 3.3 Text Injection Modes
1. **Accessibility Service Mode:** Inspects the focused `EditText` and executes `ACTION_SET_TEXT`.
2. **Clipboard Mode (Privacy-Friendly Alternative):** Copies transcription to system clipboard; Gboard and SwiftKey immediately display a 1-tap paste chip. Users who do not wish to grant Accessibility permissions can use this mode.

---

### 3.4 Connectivity & Tailscale-Down Detection
**Problem:** Tailscale (set as Always-on VPN) periodically drops into an error state (red ⚠ triangle in the status bar).
The floating button silently stops working, and the only fix is toggling Tailscale off/on.

**Plan:**
1. **Health check:** Ping the server's `/health` on record start, plus a lightweight check every few minutes while the button is shown (skip when screen is off to save battery).
2. **Diagnose the cause** with `ConnectivityManager` / `NetworkCapabilities`:
   * No network at all → "No internet connection."
   * Network up, VPN transport missing or not validated → "Tailscale appears down."
   * VPN up but server unreachable → "Head PC unreachable (asleep, or server stopped)."
3. **Visible state on the button:** grey/red ring + small ⚠ badge instead of failing silently; tap shows the reason.
4. **Auto-restart Tailscale (with manual fallback):** Tailscale Android's exported `com.tailscale.ipn/.IPNReceiver` accepts
   `com.tailscale.ipn.DISCONNECT_VPN` and `com.tailscale.ipn.CONNECT_VPN` broadcasts — the same "tap off, tap on" fix, done by the app.
   *Verified present via `dumpsys package` on Tailscale 1.102.3 and 1.104.5 (2026-11-10).*
   * **Trigger:** A validated non-VPN network exists (Wi-Fi/cellular has internet) **and** either the VPN transport is missing/unvalidated,
     or the Tailscale VPN is up but `/health` fails.
   * **Action:** Send `DISCONNECT_VPN`, wait ~1.5 s, send `CONNECT_VPN` (explicit component broadcast), then poll `/health` for ~10 s.
   * **Guardrails:** At most one auto-restart per 5 minutes, max 2 attempts in a row. If `/health` still fails after a restart,
     report "Head PC unreachable" instead of looping (the PC is likely asleep or the server stopped).
   * **Setting:** "Auto-restart Tailscale when it stalls" toggle, default **on**. When off, or after the guardrail trips, show a
     **"Restart Tailscale"** button on the error badge and in the notification that sends the same two broadcasts on tap.
   * **Manifest:** Add `<queries><package android:name="com.tailscale.ipn"/></queries>` so the app can detect Tailscale is installed
     (Android 11+ package visibility). If it isn't installed, skip all of this and just show the connectivity message.
   * **Always-on VPN caveat:** With Always-on + "block connections without VPN", the off/on gap briefly blocks all traffic; this is
     the same thing the manual toggle does. Test that Always-on doesn't fight the `DISCONNECT_VPN` (it may reconnect by itself, which is fine).
   * **Play Store:** Uses no special permission — just a broadcast to another app. Fine for both `play` and `full` flavors.
   * Fall back to opening the Tailscale app if the broadcasts have no effect.
5. **Don't lose the dictation:** If the server is unreachable after recording, keep the audio and auto-retry once the connection returns, then paste (or offer to copy).

---

## 4. Play Store Compliance & Build Flavors

> **Before the first Play upload:** change `applicationId` / `namespace` from `com.example.whisperremote` to a real
> reverse-domain ID. Play rejects `com.example.*`, and the ID can never change after publishing.

To prevent Google Play rejections related to sensitive permissions:

### 4.1 Gradle Build Flavors (`app/build.gradle.kts`)
* **`play` (Google Play Store distribution):**
  * Permissions: `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `INTERNET`.
  * Removed: `FOREGROUND_SERVICE_MEDIA_PROJECTION`.
  * Meets all Google Play policies with minimal audit hurdles.
* **`full` (GitHub Releases / F-Droid / Personal Sideload):**
  * Retains `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `AudioPlaybackCaptureConfiguration` for transcribing app audio (YouTube, TikTok, podcasts).

---

## 5. Development Phases (re-planned 2026-11-11 with Dominic)

Phases 1 and 2 are done (Android v1.0.3, server v0.9.6).

**Goal for V1: get the app ready for the Play Store** (permissions, disclosures, demo video, listing) with the current
feature set, the split builds, the makeover, **and on-device transcription**: the Play app must work on its own,
without the user signing up for a cloud service or installing our server on other hardware. New features come after
launch (§5.5).

| Version | What | Notes |
| :--- | :--- | :--- |
| **v1.1** | **Split `play` / `full` flavors** (§4.1) | `play` = `com.cyrus_studio.whisperunchained`: drops `FOREGROUND_SERVICE_MEDIA_PROJECTION` and the internal-audio switch. `full` = `com.cyrus_studio.whisperunchained.full`, shown as "Whisper Unchained Full" (decided 2026-11-11; the suffix lets both sit side by side): keeps internal audio, relabeled without brand names (e.g. "Transcribe audio playing on this phone"; phone calls can't be captured). Both keep the AccessibilityService: Wispr Flow ships on Play the same way, with a prominent disclosure (§5.3). |
| **v1.2** | **UI makeover + first-run intro** (§5.1, §5.2) | Light and dark mode; the mic button keeps its brand color in both. |
| **v1.3** | **On-device transcription (minimum)** (§5.4) | Tap to start, tap to stop, transcribe on the phone, name corrections on top. Used when no PC/cloud is set up, and as the automatic fallback when they can't be reached. An on-device LLM for cleanup is optional and can come later. |
| **v1.4** | **Play Store submission** | Data Safety form, accessibility declaration + demo video, listing, badge (§2.8). |

### 5.1 UI makeover ("doesn't look AI-made")
Research (2026-11-11) on what reads as AI-generated UI: an unchosen default accent (our teal), a border around every group, a tagline under the name, identical spacing everywhere, emoji or mixed icon sets, mismatched indicators. Direction: **stock Android done well, close to One UI**, not a One UI clone (the only One UI library is Views-based).
* Drop the "Fast, private voice dictation" subtitle. Large title that collapses on scroll (`LargeTopAppBar`).
* Colors: dynamic color (Material You) with a neutral fallback; one accent, used only for values, switches and the primary action.
* Groups: rounded containers with **no borders or shadows** on a slightly different background (One UI: ~#F2F2F4 background, ~#FCFCFF groups, ~26dp radius, ~16dp between groups, hairline dividers inset to the text). Current value on the second line in the accent color. Helper text **between** groups. Split rows (label opens a subpage, switch toggles).
* Icons: one consistent set (Material Symbols) on top-level rows only.
* **Group by meaning, not just by screen** (One UI does this even in one long list: Connections + Connected devices together; Galaxy AI + Modes + Sounds + Notifications together; Display + Battery together). Each group answers one question ("where does my audio go?", "how does it type?", "how does it look?").
* **Show only what matters for the current choice** (we already hide options until their switch is on; keep doing it). Example, Transcription > pairing page: **Scan pairing code** first, then **Test connection** right under it, then a collapsed **"Manual setup ⌄"** row that expands to the server/provider fields. Scan *or* fill in, never both.
* Dark mode reference (One UI, Dominic's screenshots in `.tempUI`): pure black background, dark grey rounded groups, no borders, blue only for current values and switches, colored circle icons on the top-level page only, a "Looking for something else?" card at the bottom with related links.
* Tabs: `PrimaryTabRow` default indicator (sized to the label), or replace tabs with list → subpage.
* Colour-blind rule: never show state by red vs. green alone.
* Process: reference screenshots → `DESIGN.md` tokens → Compose previews rendered to PNG (Roborazzi) in light + dark → checklist review → Dominic approves the images before release. Library to consider: alorma/Compose-Settings (MIT).

### 5.2 First-run intro (ideas from Wispr Flow, not a copy)
* A **practice sandbox** in our own screen: a mock chat / email / list card; tap the field, tap the mic, speak, see the real transcription from the user's PC (also proves the connection). Ask for the **mic permission on that first tap**.
* Ask for each permission when it's needed, one per screen, each with the concrete consequence: accessibility (short "what we read / what we never touch" card, then a visual step guide with mock One UI rows and "leave the shortcut off"), battery exemption and notifications (skippable).
* Segmented progress bar; big headline + one line; one illustration; primary + secondary button at the bottom.
* Our privacy story replaces their account/survey screens: no account, audio goes to your own PC.
* Final "what to expect" screen.
* Feature gaps seen in Wispr Flow worth adding: bubble size/opacity, show the bubble only while a text field is focused, never in password/card fields, custom dictionary (feeds the server's hotwords), snippets, per-app styles.

### 5.3 Accessibility on Google Play
Wispr Flow is on Play with an AccessibilityService for field detection and insertion, justified by "Android has no standard API to detect text-field focus or insert text into other apps", plus an in-app disclosure (why, what is read, what is never touched) before the system screen. Ours (F-14) follows the same pattern; recheck Google's current policy text before the v1.6 submission.

### 5.4 On-device engine (research 2026-11-11, to be measured)
**Decided and measured (2026-11-11): spec is contracts/05-on-device.md.** Speech to text on the phone with
sherpa-onnx + **Parakeet TDT 110M int8 (108 MB)**: on Dominic's 29 recordings it differed from his PC's turbo by
~7 % (~4 % on short clips), and on the S25's CPU it transcribed 12 s of speech in 0.5 s and 31 s in 0.9 s. The
0.6B models (~480 MB) are optional downloads. Model files are hosted on this repo's `models-v1` release with
SHA-256 pins. The notes below are the earlier research.

* **Speech to text:** sherpa-onnx (Apache-2.0) with Parakeet TDT 0.6B INT8 (~465 MB, English roughly on par with large-v3-turbo; weights CC-BY-4.0) and Moonshine v2 as the fast/streaming option. kafkasl/phone-whisper (Apache-2.0) is a working reference for the sherpa-onnx wiring and model download.
* **Cleanup LLM:** LiteRT-LM with Gemma 4 E4B (best small model in the PC bench: 9/10 trap tests) on the phone GPU; E2B is ~2.5x faster but missed "scratch that". Google's S26 Ultra numbers: E4B ~22 tok/s decode, E2B ~52 tok/s; multi-token prediction claims ~2.2x more.
* **Latency is the problem** (a 100-word cleanup is ~150 output tokens). Ways to cut it: skip the LLM when the text has no fillers or correction words (most short dictations need nothing); E2B for light cleanup, E4B only for corrections/mean; NPU backends on Snapdragon; paste the raw text and offer the cleaned version only when it's ready.
* **Not usable:** Gemini Nano (ML Kit GenAI) only runs while our app is in the foreground, which rules out dictating into other apps; the Fold6 isn't on its list. Samsung has no public Galaxy AI API.
* **First step:** measure E2B/E4B with our real prompt (e.g. Google AI Edge Gallery) and Parakeet vs. turbo on Dominic's recordings, on the S25 and Fold6.
* Fallback order: PC → on-device LLM → raw text.
* **First measurements (S25, Snapdragon 8 Elite, llama.cpp v0.9.0 OpenCL, 2026-11-11):**
  * Qwen 3.5 4B Q4_0 on the GPU works and cleans correctly (scratch-that, no-wait, short and long clips): reading the prompt ~2.5 s (cacheable: the system prompt is the same every time), writing ~19 tok/s cool, dropping to ~12-16 tok/s as the phone heats up. A 30-word clip ≈ 2 s of writing; a 200-word clip ≈ 15 s.
  * Gemma 4 on llama.cpp's phone GPU path produces garbage, and on CPU it ignores "thinking off". Gemma 4 needs Google's LiteRT-LM to test fairly.
  * SpeakoFlow Mini (Qwen 3.5 0.8B fine-tuned for cleanup, Apache-2.0) is very fast but **deleted whole spoken sentences** and answered a dictated question in our PC tests; not safe even as a fallback. Its MIT rules layer (AbhishekBarali/dictation-cleanup-rules: fillers, numbers, dates, currency, no AI) is a good candidate for the instant no-model fallback.

### 5.5 After launch: features, easiest first, grouped so related work ships together
Competitor scan (2026-11-11): SpeakoFlow, Handy, OpenWhispr, Amical, VoiceTypr, superwhisper, Wispr Flow, Aqua Voice, Willow, MacWhisper.
None lets a phone use your own PC's GPU (VoiceTypr shares a PC's transcription with other PCs on the LAN, desktop only;
Amical's Android bubble uses Amical's paid cloud models, not your desktop). That stays our edge.
**Not goals:** live text while you talk (our chunked Whisper doesn't stream well); meeting recording / notes / speaker labels; translate-to-English; hold-to-talk on the phone bubble (holding drags the bubble; the bubble stays tap to start, tap to stop).

**Group A: desktop polish (small)**
1. **Sharper, easier-to-read text on the desktop.** Next to the Claude app our letters look thin (one-pixel stems, jagged diagonals). Try in this order: one size larger and a sturdier font (Segoe UI Variable, or semibold for labels); per-monitor DPI awareness (2) in every window before Tk starts (`settings_gui.py` sets only system-level awareness, the wizard sets none) so Windows doesn't stretch the windows as a picture. Compare screenshots side by side on Head-PC's monitors.

**Group B: "what you said, your way" (server text rules, shared by phone and desktop)**
2. **Smart Replace**: spoken phrase → text ("enter my email" → `you@example.com`, so "let me give you my email enter my email" becomes "let me give you my email you@example.com"). One list on the server, synced to the app like the vocabulary (also used in the app's cloud mode). Broader version of name corrections.
3. **Per-app styles**: e.g. casual in Messages, professional in Outlook. The desktop knows the foreground program, the phone knows the app's package name; each sends it and the server picks the style. Reuses the existing style presets.
4. **Dictionary that learns from your corrections** (OpenWhispr does this, MIT: `src/utils/correctionLearner.js`): after a paste, watch the field briefly, diff what we pasted against what's there now (word-level LCS), keep swaps that look like mishearings (skip common words, words under 3 letters, unrelated words), and offer them for the vocabulary / name corrections. The Android accessibility service can already read the field; the desktop needs UI Automation. Suggest, don't silently add.

**Group C: a "results" window on the desktop (one window, three uses)**
5. **Transcribe a file**: a button in the desktop app (not only the right-click menu): drag-and-drop or "Select file", audio or video, result in a text box with Copy / Save.
6. **Subtitles** for files: SRT/VTT export (segments already have timestamps). (Translate-to-English: not a goal, plenty of apps do that.)
7. **History**: search past dictations, paste or copy again, raw vs. cleaned side by side, re-run cleanup. The transcript log already has the data.

**Group D: voice editing**
8. **Voice-edit selected text in any app**: highlight, hotkey/bubble, say "make this shorter". Reuses "prompt AI" (Aqua Voice and SpeakoFlow do this). `full` flavor on Android; desktop via copy-selection → edit → paste.

**Group E: install and upkeep**
9. **Auto-updates** with stable and beta channels, plus a **model manager** screen (§2.4).

**Group F: reach (largest)**
10. **PCs without an NVIDIA GPU**: CPU Parakeet (sherpa-onnx) or whisper.cpp with Vulkan for AMD/Intel. Our biggest install blocker (Handy and OpenWhispr already do this).
