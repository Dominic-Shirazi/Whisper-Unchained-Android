# 05 · On-device transcription (v1.3)

**Version:** 1 (2026-11-11) · **Owner:** Claude (spec), Gemini (app) · **Asked for by:** Dominic

Why: the Play app has to work on its own. Dominic: "I don't want to launch an app that doesn't actually do anything
unless you happen to have one of my other products." Minimum: **tap to start, tap to stop, transcribe on the phone,
name corrections on top, and fallback** when the PC or cloud can't be reached. An on-device language model for
cleanup is *not* part of v1.3.

## 1. Measurements that picked the model (2026-11-11)

Dominic's 29 real recordings (996 s), each compared with what his PC's large-v3-turbo wrote (turbo isn't perfect,
so "difference" ≠ "error"; spot checks showed the phone models mostly keep stutters turbo smooths over).
PC CPU, 4 threads, sherpa-onnx 2.0.1, audio cut at pauses (Silero VAD, ≤ 25 s pieces):

| Model | Download | Differs from turbo | Short clips | Speed |
| :--- | :--- | :--- | :--- | :--- |
| **Parakeet TDT 110M int8** (English) | **108 MB** | **7.1 %** | **3.8 %** | 34× real time |
| Parakeet TDT 0.6B v2 int8 (English) | 482 MB | 6.6 % | 5.7 % | 15× |
| Parakeet TDT 0.6B v3 int8 (25 languages) | 487 MB | 9.8 % | 8.3 % | 14× |
| Whisper small.en int8 | 636 MB | 8.4 % | 5.7 % | 3.7× |
| Moonshine base (quantized) | 111 MB | 11.3 % | 9.1 % | 29× |
| Whisper base.en int8 | 209 MB | 12.2 % | 14.4 % | 10× |
| Moonshine tiny (quantized) | 30 MB | 17.0 % | 12.5 % | 48× |

**On the phone** (Galaxy S25, Snapdragon 8 Elite, CPU only, 4 threads, sherpa-onnx static build):

| Clip | Parakeet 110M | Parakeet 0.6B v2 |
| :--- | :--- | :--- |
| 2 s | 0.05 s | 0.54 s |
| 12 s | **0.54 s** | 1.28 s |
| 31 s | **0.94 s** | 2.46 s |
| 99 s | 5.2 s | 5.3 s |

No GPU or NPU needed. The Fold6 (8 Gen 3) should be somewhat slower; still well under real time.

## 2. What to build

1. **Engine:** sherpa-onnx (Apache-2.0) through its Android Kotlin/JNI API (the official AAR or the
   `sherpa-onnx-*-android.tar.bz2` jniLibs), `OfflineRecognizer` with a NeMo transducer, CPU provider, 4 threads.
   kafkasl/phone-whisper (Apache-2.0) is a working reference for the wiring and the download screen.
2. **Models** (downloaded, not bundled, so the APK stays small):
   - **Default: Parakeet TDT 110M int8** (English).
   - Optional "Higher accuracy (482 MB)": Parakeet TDT 0.6B v2 int8 (English).
   - Optional "Other languages (487 MB)": Parakeet TDT 0.6B v3 int8 (25 European languages).
   - **Silero VAD** (`silero_vad.onnx`, MIT, ~2 MB) always, to cut recordings at pauses into ≤ 25 s pieces
     (bounds memory on long dictations; the pieces are transcribed in order and joined with a space).
   - Host the exact files we tested on **our own GitHub release** (tag `models-v1` in this repo) and pin each file's
     SHA-256 in the app; verify after download, delete on mismatch. (Claude will create that release.)
   - Licenses: NVIDIA Parakeet weights are **CC-BY-4.0** → credit in About and ATTRIBUTIONS.md; sherpa-onnx Apache-2.0;
     Silero VAD MIT.
3. **Where your voice is transcribed** (Transcription page, top): **On this phone** / **My PC** / **Cloud service**.
   - Fresh install defaults to *On this phone* once the model is downloaded, so the app works with nothing else.
   - **"Use this phone when the PC can't be reached"** switch (on by default when a model is downloaded): if the PC
     or cloud request fails or times out, transcribe the same audio on the phone instead and paste that, with a short
     note ("Transcribed on this phone: your PC couldn't be reached"). The dictation is never lost.
4. **After transcription, on the phone** (same order as the server, contract 01):
   - **Name corrections** ("heard as → write as", whole word, any case, literal), from the local list; for paired
     users, the PC's list cached from `GET /v1/vocabulary` so corrections still apply offline.
   - **Filler sounds** ("Remove filler sounds" switch, off by default): exactly the server rule (`api/fillers.py`):
     only standalone um/umm/uh/uhh/hmm, never "like"/"you know". Port the function and its test cases.
   - If a cloud chat model is configured for cleanup, cleanup can run as today; otherwise the text is pasted as is.
   - No hint-echo trim needed (no prompt is sent to the model).
5. **Vocabulary as hotwords (try it, keep if it helps):** sherpa-onnx transducers support contextual biasing
   (`hotwordsFile` / `hotwordsScore` with `modified_beam_search`). Feed the vocabulary list; compare a few
   "Telnyx / Ollama / Qwen" recordings with and without. Drop it if it adds noticeable delay or wrong words.
6. **Download screen:** model name, size, "Downloading 42 of 108 MB", Wi-Fi-only by default with a "Use mobile
   data" override, resumable, cancel. Settings → Transcription → **On-device model** shows what's installed and lets
   the user delete/switch models.
7. **Intro (contract 04 §4):** the **Try it** page now works out of the box: the intro downloads the default model
   (with the screen above) and transcribes the practice box on the phone. **Connect** becomes "Use your PC for the best
   accuracy (optional)".
8. **Keep it light:** load the recognizer when the floating button starts and keep it while it's shown; release it
   when the button is stopped. Run decoding off the main thread. No wake locks beyond the existing foreground service.

## 3. Not in v1.3
On-device language-model cleanup (later; the S25 tests ran Qwen 3.5 4B on the GPU at ~19 tokens/s, so short
cleanups are possible but long ones are slow). Live text while speaking. Smart Replace (post-launch, ROADMAP §5.5).

## 4. Done when
- A fresh install with no PC and no cloud key can dictate into any app after the intro.
- With the PC paired, turning Wi-Fi off mid-use still transcribes (fallback) and says so.
- Dominic's corrections list applies to on-device text.
- Release build on the Fold; Claude reviews the screenshots of the new screens (contract 04 §7) before Dominic.
