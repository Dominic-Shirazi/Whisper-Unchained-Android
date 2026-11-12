# 01 · Transcription API contract

**Version:** 1 (2026-11-10; §1–§5 implemented in Whisper-Unchained-Server `03c1041`) · **Owner:** Claude · **Server:** Whisper-Unchained-Server `api/whisper_api.py`

Goal: the app speaks **plain OpenAI `/v1/audio/transcriptions`**, so the same client code works
against the Head PC, Speaches, Groq, or OpenAI. Head-PC-only extras are optional fields that
other providers simply ignore.

Default port **5000**. All examples use `http://100.x.y.z:5000` as a placeholder.

---

## 1. `POST /v1/audio/transcriptions` · LIVE (code; Head PC service restart pending)

Multipart form (`multipart/form-data`):

| Field | Required | Notes |
| :--- | :--- | :--- |
| `file` | yes | Audio. WAV (16 kHz mono PCM is ideal), M4A/AAC, MP3, OGG, WebM all accepted. |
| `model` | no | Accepted for OpenAI compatibility (`whisper-1` etc.) and **ignored**. The server uses whatever model it has loaded. The response's `model` (verbose) and `GET /v1/models` say which. |
| `prompt` | no | Vocabulary hints, e.g. `"Lesley, Ollama, Kubernetes"`. Added on top of the server's own vocabulary list (`processing_config.json`). |
| `language` | no | ISO-639-1 (`en`). Omit to auto-detect. |
| `response_format` | no | `json` (default), `text`, `verbose_json`. Others → 400. |
| `temperature` | no | Accepted and ignored (server uses its own decode settings). |
| `processing` | no | **Head PC extra.** `full` (default): name corrections + spoken edit commands ("prompt AI …"), same as the desktop hotkey; plus cleanup if the server's "Clean up every dictation" is on. `raw`: plain Whisper text, no edits. `clean`: `full` + the cleanup pass for this request (contract 02). `mean` (**added 2026-11-10**): the whole dictation goes to the server's mean mode (a roast in the speaker's voice), no spoken trigger needed; `cleanup_*` fields are ignored. Self-hosted preset only (it runs on the PC's chosen mean-mode model). |
| `cleanup_punctuation` / `cleanup_rewrite` / `cleanup_tone` / `cleanup_shorten` / `cleanup_emoji` | no | **Head PC extra.** Cleanup style overrides; see contract 02 §5. |

**Response, `json`:**
```json
{ "text": "Transcribed string." }
```

**Response, `text`:** the bare string, `Content-Type: text/plain`.

**Response, `verbose_json`:** OpenAI shape plus extras:
```json
{
  "task": "transcribe",
  "language": "en",
  "duration": 4.21,
  "text": "Transcribed string.",
  "segments": [ { "id": 0, "start": 0.0, "end": 4.1, "text": " Transcribed string.", "avg_logprob": -0.21, "no_speech_prob": 0.01 } ],
  "model": "large-v3-turbo",
  "raw_text": "transcribed string"
}
```
`raw_text` = Whisper's output before name corrections / edit commands.

**Empty audio / silence:** `200` with `"text": ""`. Not an error. The app should paste nothing and
may show "No speech detected."

**Timing:** typically 0.3–2 s for a short dictation on GPU. A spoken *edit command* runs an LLM and can
take up to ~10 s cold. Keep the client read timeout ≥ 60 s.

## 2. `GET /health` · LIVE (new fields in code; restart pending)

**Never needs auth.** Built for the app's connectivity / Tailscale-down check (ROADMAP §3.4): cheap, no GPU work.

```json
{ "status": "ok", "api_version": 1, "model": "large-v3-turbo", "auth_required": true }
```
- `status`: `ok`; `paused` when dictation is paused on the PC (see §9; **the server is reachable**, so show
  "Paused on your PC", never "Tailscale down", and don't run the Tailscale restart); or `error` if the Whisper
  model failed to load (transcription returns 503).
- `paused_reason`: `user` (paused from the tray), `game` (game mode; resumes by itself), or `null`.
  There is **no `loading` state**: the server doesn't accept connections until the model is loaded, so
  "connection refused" for ~10–60 s after a start/resume is normal. Treat it like "unreachable" and retry.
- `/health` stays fast while a transcription is running (the pipeline runs off the request thread),
  so a slow `/health` really does mean a network problem. A 3–5 s timeout is fine.
- `auth_required`: tells the app whether to prompt for an API key.
- Other fields may appear (device, edit model). **Don't depend on them.**
- No response / connection refused = server stopped or PC unreachable. That is the app's "Head PC unreachable" state.

## 3. `GET /v1/models` · LIVE (code)

OpenAI shape. **Needs auth when auth is on**, so the app's "Test connection" button can check the key in one call.
```json
{ "object": "list", "data": [ { "id": "large-v3-turbo", "object": "model", "owned_by": "local" } ] }
```

## 4. Auth · LIVE (code)

- **On by default for new installs** (2026-11-10): the setup wizard creates a key, and the pairing QR carries it.
  On when the server's `.env` has `WHISPER_API_KEY=<key>` and `WHISPER_API_KEY_REQUIRED` isn't `false`.
- The user can switch "Require the key" off (Settings > Phone) while keeping the key saved. Then the server
  accepts requests with or without it, and `/health` says `"auth_required": false`. **The app keeps its key and
  keeps sending it** regardless, so switching back on needs no re-pairing.
- Requests that carry a browser `Origin` header are refused (`403 origin_not_allowed`). OkHttp sends none; don't add one.
- Client sends `Authorization: Bearer <key>` (the standard OpenAI header).
- When on, **every route except `GET /health`** requires it, legacy routes included.
- Wrong/missing key → `401` (see errors). The app should show "API key rejected", not "server unreachable".
- Key format generated by the installer: `wr_sec_` + 32 url-safe random chars. The app must accept any string.

## 5. Errors · LIVE (code)

OpenAI error shape on every `/v1/*` failure:
```json
{ "error": { "message": "Invalid API key.", "type": "invalid_request_error", "code": "invalid_api_key" } }
```

| HTTP | `code` | Meaning / app behavior |
| :--- | :--- | :--- |
| 400 | `missing_file`, `invalid_response_format`, `invalid_processing` | Client bug |
| 401 | `invalid_api_key` | Show "API key rejected" |
| 413 | `file_too_large` | Over 100 MB |
| 503 | `server_paused` | Paused on the PC (§9). Keep the audio; show the message from the error; retry after `Retry-After` or when `/health` says `ok` |
| 503 | `model_unavailable` | Model failed to load on the server. `Retry-After: 30` is set. Show "Server error" |
| 500 | `server_error` | Keep the audio, offer retry (ROADMAP §3.4 step 5) |

## 6. Legacy routes · LIVE (unchanged)

`POST /transcribe` (`{"text","language","duration"}`), `/transcribe_time_stamped`, `/transcribe_words`, `/warmup`.
The desktop listener uses `/transcribe`, and so does the current app (`ApiClient.kt`).
They stay, but **new app code should use `/v1/audio/transcriptions`**. When auth is on, these require the key too.

## 7. Pairing QR payload · AGREED (Q-1), desktop generator BUILDING

The installer and the desktop Settings window will show a QR code the app scans. Proposed payload:
```json
{ "v": 1, "base_url": "http://100.x.y.z:5000/v1", "api_key": "wr_sec_…", "model": "large-v3-turbo", "name": "Head PC" }
```
- `base_url` ends in `/v1`. The app appends `/audio/transcriptions` and `/models`, and gets `/health` by
  dropping the trailing `/v1` (Q-1, agreed 2026-11-10).
- `api_key` is **omitted** (not empty) when the server has no key.
- `v` is the payload version. The app should reject `v` it doesn't know, with a "update the app" message.
- The QR holds a secret when `api_key` is present. The desktop UI shows it only on request, behind a "Show pairing QR" button.

## 8. Shared vocabulary · LIVE (code; Whisper-Unchained-Server `1c3a2b8`)

The Head PC's vocabulary list is **the** list. The app shows it and edits it, so a word typed on the
phone while out also works at the desk later (Dominic, 2026-11-10). Self-hosted preset only; other
providers have no such endpoint.

**`GET /v1/vocabulary`** (auth like every `/v1` route):
```json
{ "vocabulary": ["Qwen", "Ollama"], "from_name_corrections": ["Lesley", "Ame"],
  "name_corrections": {"Leslie": "Lesley", "Emma": "Ame"}, "chars": 25, "max_chars": 540 }
```
- `vocabulary`: the user's words. Show these as an editable list (add, change, remove).
- `from_name_corrections`: hinted automatically from the server's name corrections. **Show them** (greyed, read-only,
  labeled "from name corrections"): hiding them made Dominic think his words were missing (2026-11-10).
- `name_corrections` (**added 2026-11-10**, additive): the full "heard as → write as" pairs. **Editable from the phone
  since 2026-11-12** (server `659bc0a`), see below.
- `chars` / `max_chars`: how much of Whisper's hint space is used (~224 tokens ≈ 540 chars). Words past that are ignored by Whisper; warn when `chars` > `max_chars`.

**`POST /v1/vocabulary`** `{"add": ["Kubernetes"], "remove": ["Kubernets"]}` → same body as GET plus `"added": [...]`, `"removed": [...]`.
- `add` and `remove` are both optional, but at least one word is required (1–50 each). Removes run first, so fixing a
  spelling is one call. Added words: under 60 characters, no commas or line breaks. Otherwise `400 invalid_vocabulary`.
- Adding dedupes case-insensitively against both lists, so re-sending is harmless. Removing matches case-insensitively
  and only touches `vocabulary` (to drop a word in `from_name_corrections`, remove its name correction).
- Takes effect immediately for every dictation (desktop and phone); no restart.

**`POST /v1/name_corrections`** (**new 2026-11-12**) `{"set": {"Corey": "Cory"}, "remove": ["Emma"]}` → same body as GET
plus `"changed": {...}` (pairs that were new or different) and `"removed": [...]`.
- "Heard as" matches case-insensitively, like the correction itself: setting `"corey"` replaces an existing `"Corey"` in
  place. Removes run first, so changing the "heard as" side is one call (`remove` old + `set` new).
- Rules: 1–50 entries per call; heard-as at least 2 characters (a one-letter rule would rewrite every "a" or "I");
  each side under 60 characters, no commas or line breaks; not identical on both sides; no "heard as" listed twice.
  Otherwise `400 invalid_name_corrections` with a readable `message` (show it).
- Re-sending a pair that's already there is harmless (`changed` is empty).
- **Safety net:** any call that removes or replaces something first copies the PC's word file to
  `api/word_list_backups/` (newest 30 kept). Still confirm removals in the app (see below).
- Older servers return `404` for this route: show the list read-only with "Update the PC app to edit these here".

**Expected app behavior:**
- Self-hosted preset: the hints field shows the server list (GET on open/refresh). Adding, removing and editing POST
  right away. **Offline:** queue each edit locally, in order, and replay the queue on the next successful `/health`;
  drop an entry once a response confirms it (or once the list already shows the end state). A `400` drops the entry
  and shows its message once.
- Removing (a word or a name correction) shows an **Undo** snackbar for a few seconds; Undo re-sends what was removed.
- Cloud presets (Groq/OpenAI/...): keep a local list and send it as the `prompt` field, since there is no server list.
  The server-side list is not copied into the pairing QR (it would go stale).
- Sending per-request `prompt` hints to the Head PC still works and is merged case-insensitively, but with the shared list it's no longer needed.

## 9. Soft pause · LIVE (code; Whisper-Unchained-Server, 2026-11-10)

The PC can pause dictation to free its graphics card (from the tray, or automatically while a game runs). The server
stays up; the speech model moves off the GPU.

- `POST /v1/pause` `{"reason": "user" | "game"}` and `POST /v1/resume` exist for the desktop tray. **The app doesn't need to call them.**
- While paused: `GET /health` → `"status": "paused"`, `"paused_reason": "user" | "game"`; transcription → `503 server_paused`
  with a human message ("Dictation is paused on the PC (for gaming). Resume it from the tray icon.").
- App behavior: treat `paused` as **reachable** (no Tailscale-down state, no auto-restart of Tailscale). Show a calm "Paused on your PC"
  state on the button. On a 503 `server_paused`, keep the recording and offer retry (or retry automatically once `/health` is `ok`;
  `game` pauses end on their own when the game closes).

## 10. Hint echo (hallucinated word list) · LIVE on the server (Whisper-Unchained-Server, 2026-11-10)

**What happens:** on a faint noise after the last word (stop beep, key release), Whisper can read the hint list
back as speech, looping: *"…at the top of the page. Ollama, Lesley, Ame, Sara, Lesley, Ame, Sara, Lesley."*
Seen in 7 of Dominic's dictations; replayed and confirmed it disappears with no hints. Cloud Whisper
(`prompt` field) is prone to the same thing.

**Self-hosted preset: the server removes it. The app must not trim anything** (a second, looser trim on the
phone would only risk real words).

**Cloud presets: the app removes it, with the same rule.** A segment (or, for text-only replies, the trailing run
of words) is an echo only if **every** word in it is a hint word (case-insensitive, split on non-word characters),
**and** at least one of:
1. it ends more than 0.5 s past the audio's duration (needs `response_format=verbose_json` segments: OpenAI
   `whisper-1` and Groq support it);
2. it's faster than speech: more than 8 words per second of segment time (verbose_json only);
3. one hint word appears 3 or more times in it (the list looping; this is the only test for text-only replies).

For a text-only reply, the "trailing run" is the longest run of hint words at the very end (separated only by
spaces and `,.;:!?`). Never remove a run that fails these tests, even a single hint word at the end:
"Let's ask Ollama" and "Say hi to Sara" must paste unchanged. Server reference: `_is_hint_echo` in
`api/whisper_api.py` (tests in `tests/test_v1_api.py`).

The "leading recitation" trim (3+ hints in list order at the very start) can stay for cloud presets only.

