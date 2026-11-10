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
> [Open_Whisper_Flow](https://github.com/Dominic-Shirazi/Open_Whisper_Flow) repo (faster-whisper + FastAPI,
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
* VRAM figures should be re-measured on real hardware before shipping (see the 2026-08 benchmark in Open_Whisper_Flow).

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
4. **One-tap fix:** Notification/button action "Restart Tailscale". Tailscale Android exposes broadcast intents for automation
   (`com.tailscale.ipn.DISCONNECT_VPN` / `CONNECT_VPN` to `com.tailscale.ipn/.IPNReceiver`, used by Tasker) — **verify on current
   Tailscale builds**; fall back to opening the Tailscale app.
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

## 5. Development Phases

| Phase | Component | Tasks |
| :--- | :--- | :--- |
| **Phase 1** | **Android Client Refactor** | • Upgrade `ApiClient.kt` to OpenAI `/v1/audio/transcriptions` standard.<br>• Add Settings UI presets, API key, model, and hints fields.<br>• Add client-side hint-loop sanitizer.<br>• Add `BootReceiver` for auto-start on device reboot.<br>• Add connectivity/Tailscale-down detection, button error state, and retry queue (§3.4). |
| **Phase 2** | **Desktop Server ("Head PC")** | • Extend Open_Whisper_Flow: add `/v1/audio/transcriptions` + optional API key.<br>• Build `setup_server.py` installer with hardware detection.<br>• Add terminal QR-code generator for 1-click Android pairing.<br>• Settings UI: model picker (§2.4), hotkey picker (§2.6), Pause also unloads Ollama (§2.7). |
| **Phase 3** | **Play Store & Dual Injection** | • Configure `play` vs `full` Gradle build flavors.<br>• Implement Clipboard fallback option.<br>• Prepare Google Play Data Safety declaration and Accessibility demo video. |
| **Phase 4** | **On-Device Offline Engine** | • Add optional on-demand model download (Sherpa-ONNX / Whisper TFLite).<br>• Show battery/thermal warnings for on-device processing. |
