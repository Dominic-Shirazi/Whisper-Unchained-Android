# 05 · On-device transcription (v1.3)

**Version:** 2 (2026-11-15: model list changed, see §1.1 and §2 item 2; build item F-52) · **Owner:** Claude (spec), Gemini (app) · **Asked for by:** Dominic

Why: the Play app has to work on its own. Dominic: "I don't want to launch an app that doesn't actually do anything
unless you happen to have one of my other products." Minimum: **tap to start, tap to stop, transcribe on the phone,
name corrections on top, and fallback** when the PC or cloud can't be reached. An on-device language model for
cleanup is *not* part of v1.3.

## 1. Measurements that picked the model (2026-11-11)

Dominic's 29 real recordings (996 s), each compared with what his PC's large-v3-turbo wrote (turbo isn't perfect,
so "difference" ≠ "error"; spot checks showed the phone models mostly keep stutters turbo smooths over).
PC CPU, 4 threads, sherpa-onnx 2.0.1, audio cut at pauses (Silero VAD, ≤ 25 s pieces):

| Model | Download (unpacked) | Differs from turbo | Short clips | Speed |
| :--- | :--- | :--- | :--- | :--- |
| **Parakeet TDT 110M int8** (English) | **136 MB** | **7.1 %** | **3.8 %** | 34× real time |
| Parakeet TDT 0.6B v2 int8 (English) | 661 MB | 6.6 % | 5.7 % | 15× |
| Parakeet TDT 0.6B v3 int8 (25 languages) | 671 MB | 9.8 % | 8.3 % | 14× |
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

### 1.1 Second round (2026-11-15): the model list changes

40 of Dominic's real recordings (627 s, 2 to 87 s each), same comparison against his PC's large-v3-turbo,
sherpa-onnx 2.0.1, greedy decoding, no hotwords, whole clip in one piece (no VAD). Punctuation and capitals are
ignored in "words differ"; commas are counted separately (turbo wrote 92).

| Model (int8) | Words differ | Clips word-identical | Commas | S25, typical clip | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Parakeet unified-en 0.6B, non-streaming** (English) | **2.7 %** | **29 / 40** | 59 | ~0.31 s (same design as v2; PC speed identical) | **add: "Higher accuracy"** |
| Parakeet TDT 0.6B v2 (English) | 3.7 % | 22 / 40 | 69 | 0.31 s | remove (unified replaces it) |
| Parakeet TDT 0.6B "ultra" (Moondream, from v3) | 4.0 % | 25 / 40 | 69 | same | not added |
| Parakeet TDT 1.1B (third-party conversion) | 4.0 % | 23 / 40 | none (lowercase only) | 1.09 s | not added |
| **Parakeet TDT 110M** (English) | 5.1 % | 22 / 40 | 38 | **0.13 s** | stays the default |
| Parakeet TDT 0.6B "redux" (Moondream, 1.58-bit v3) | 7.3 % | 15 / 40 | 56 | same | not added |
| Parakeet TDT 0.6B v3 (25 languages) | 12.1 % | 22 / 40 | 45 | same | **remove: it drops speech** |

v3 returned an empty string for one 22 s clip and lost about 50 words of the 87 s clip. Losing what was said is the
one failure dictation can't have, so it goes even though that leaves no "other languages" option for now.

## 2. What to build

1. **Engine:** sherpa-onnx (Apache-2.0) through its Android Kotlin/JNI API (the official AAR or the
   `sherpa-onnx-*-android.tar.bz2` jniLibs), `OfflineRecognizer` with a NeMo transducer, CPU provider, 4 threads.
   kafkasl/phone-whisper (Apache-2.0) is a working reference for the wiring and the download screen.
2. **Models** (downloaded, not bundled, so the APK stays small):
   - **Default: Parakeet TDT 110M int8** (English).
   - Optional "Higher accuracy (633 MB)": **Parakeet unified-en 0.6B int8, non-streaming** (English), model id
     `parakeet-unified-en-0.6b-int8`. Same recognizer settings as the others (`model_type = nemo_transducer`,
     encoder/decoder/joiner/tokens). Source package: sherpa-onnx `asr-models` release,
     `sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2`. Files (bytes, SHA-256):
     - `encoder.int8.onnx` 654040552 `6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0`
     - `decoder.int8.onnx` 7257753 `a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6`
     - `joiner.int8.onnx` 1735860 `869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a`
     - `tokens.txt` 8952 `dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff`
   - **Removed in version 2:** Parakeet TDT 0.6B v2 and 0.6B v3 (§1.1). Take both out of the model list and the
     manifest. On update, delete their downloaded files; if one of them was the selected model, switch to the 110M
     (download it if it isn't there) and say so once ("That model was replaced. Using the standard model.").
   - Open, Dominic to decide: whether the 110M stays as the small default (135 MB, 0.13 s) or unified becomes the
     only model. Until he says, 110M stays the default.
   - **Silero VAD** (`silero_vad.onnx`, MIT, ~2 MB) always, to cut recordings at pauses into ≤ 25 s pieces
     (bounds memory on long dictations; the pieces are transcribed in order and joined with a space).
   - Host the exact files we tested on **our own GitHub release** (tag `models-v1` in this repo) and pin each file's
     SHA-256 in the app; verify after download, delete on mismatch. (Claude will create that release.)
   - The unified model is under the **NVIDIA Open Model License**, not CC-BY-4.0. Claude adds its files to our
     models release only after Dominic has okayed re-hosting under that licence; until then the build can pin the
     hashes above but must not ship a download link for it.
   - Licenses: NVIDIA Parakeet 110M weights are **CC-BY-4.0** → credit in About and ATTRIBUTIONS.md; unified-en 0.6B
     is NVIDIA Open Model License → name the licence and link it in About and ATTRIBUTIONS.md; sherpa-onnx Apache-2.0;
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
     only standalone um/umm/uh/uhh/hmm, never "like"/"you know". Port the function; the port must pass
     every case in `contracts/05-text-rules-tests.json` (generated from the server code), as must the name-correction port.
   - If a cloud chat model is configured for cleanup, cleanup can run as today; otherwise the text is pasted as is.
   - No hint-echo trim needed (no prompt is sent to the model).
5. **Vocabulary as hotwords (measured, keep it):** use `decodingMethod = "modified_beam_search"`,
   `hotwordsFile` = the vocabulary (one term per line, plus the "write as" side of the name corrections),
   `hotwordsScore = 2.0`, `modelingUnit = "bpe"`, `bpeVocab` = the model's `tokens.txt`. On 16 of Dominic's
   recordings containing vocabulary words (Parakeet 110M): plain decoding got 10/20 right, beam search 12/20,
   **beam + hotwords 14/20, with zero wrong insertions**, ~20 % slower (still well under a second for short clips).
   The remaining misses are near-misses ("telenix", "Olama") that the name corrections fix afterwards.
   Rewrite the hotwords file when the vocabulary changes; if the recognizer fails to load with hotwords, fall back to
   plain greedy decoding rather than failing.
6. **Download screen:** model name, size, "Downloading 42 of 136 MB", Wi-Fi-only by default with a "Use mobile
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
