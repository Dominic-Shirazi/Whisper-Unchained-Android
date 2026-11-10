# WhisperRemote: Architecture, Server Specification & Development Roadmap

This document outlines the unified vision, architecture, and step-by-step roadmap to align **WhisperRemote for Android** with the **Head PC Desktop Server**.

---

## 1. Project Vision

* **Core Goal:** A fast, private, open-source voice dictation system that works seamlessly alongside any Android keyboard, offering an open alternative to commercial services like Wispr Flow.
* **Cost & Privacy:** $0 subscription, zero telemetry, zero contact harvesting, and zero third-party cloud lock-in. Audio routes exclusively to the user's private hardware or user-chosen pay-as-you-go API.
* **Cross-Project Alignment:** The Android app and Desktop server share an OpenAI-compatible API contract (`/v1/audio/transcriptions`), ensuring interoperability across self-hosted hardware and low-cost cloud providers.

---

## 2. Desktop Server & Installer ("Head PC") Specification

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

## 4. Play Store Compliance & Build Flavors

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
| **Phase 1** | **Android Client Refactor** | • Upgrade `ApiClient.kt` to OpenAI `/v1/audio/transcriptions` standard.<br>• Add Settings UI presets, API key, model, and hints fields.<br>• Add client-side hint-loop sanitizer.<br>• Add `BootReceiver` for auto-start on device reboot. |
| **Phase 2** | **Desktop Server ("Head PC")** | • Build `setup_server.py` installer with hardware detection.<br>• Implement `faster-whisper` + FastAPI server with optional API key.<br>• Add terminal QR-code generator for 1-click Android pairing. |
| **Phase 3** | **Play Store & Dual Injection** | • Configure `play` vs `full` Gradle build flavors.<br>• Implement Clipboard fallback option.<br>• Prepare Google Play Data Safety declaration and Accessibility demo video. |
| **Phase 4** | **On-Device Offline Engine** | • Add optional on-demand model download (Sherpa-ONNX / Whisper TFLite).<br>• Show battery/thermal warnings for on-device processing. |
