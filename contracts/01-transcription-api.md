# 01 · Transcription API contract

**Version:** 1 (2026-11-10; §1–§5 implemented in Whisper-Unchained-Server `03c1041`) · **Owner:** Claude · **Server:** Whisper-Unchained-Server `api/whisper_api.py`

Goal: the app speaks **plain OpenAI `/v1/audio/transcriptions`**, so the same client code works
against the Head PC, Speaches, Groq, or OpenAI. Head-PC-only extras are optional fields that
other providers simply ignore.

Ports (**since 2026-11-12**): plain HTTP on **5000** for other programs, HTTPS on **5001** for phones (the pairing QR carries the full `base_url`, so the app needs no port logic). Examples use `http://100.x.y.z:5000` as a placeholder.

> **2026-11-12: dictation vs transcription.** With **no `processing` field**, `/v1/audio/transcriptions` is now a **plain transcription**: no name corrections, no trigger phrases, no cleanup, and not written to the PC's dictation log or audio corpus. That's what any OpenAI program expects. **The app must keep sending `processing` on every self-hosted request** (it already does: `full` / `clean` / `mean`). A dictation twin, `POST /dictate/v1/audio/transcriptions` (+ `GET /dictate/v1/models`), defaults to `full`, for OpenAI programs that can only change the base URL. The app doesn't need it.

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
| `processing` | no (**the app always sends it**) | **Head PC extra.** Omitted: `raw`, not logged (since 2026-11-12; was `full`). `full`: name corrections + spoken edit commands ("prompt AI …"), same as the desktop hotkey; plus cleanup if the server's "Clean up every dictation" is on. `raw`: plain Whisper text, no edits. `clean`: `full` + the cleanup pass for this request (contract 02). `mean` (**added 2026-11-10**): the whole dictation goes to the server's mean mode (a roast in the speaker's voice), no spoken trigger needed; `cleanup_*` fields are ignored. Self-hosted preset only (it runs on the PC's chosen mean-mode model). |
| `trigger` | no | **Head PC extra (2026-11-16).** The `id` of one of the user's custom triggers (§11). Its saved instruction is applied to the whole dictation, as if its phrase had been spoken. Skipped when a spoken trigger already fired, when the text is empty, and with `processing=raw`. An unknown or switched-off id is ignored (the dictation comes back as `full`), never an error. |
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

> **2026-11-16: mean mode left the server's core.** It is now an ordinary custom trigger in the shared library
> (id `mean-mode`, category "Fun") that the user adds like any other. `processing=mean` is **deprecated**: the
> server still accepts it from older builds and treats it as `processing=full` + `trigger=mean-mode`, so it is a
> plain dictation unless the user has added that trigger. New builds stop sending it (F-58) and use `trigger`.

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
- **`alt_base_urls` (optional, added 2026-11-16; v1 and v2 payloads):** up to two more addresses of the *same*
  server, same shape as `base_url`, e.g. `"alt_base_urls": ["https://192.168.0.2:5001/v1"]`. The PC puts its
  home-network address there when `base_url` is the Tailscale one, and the other way round, so one code works at
  home and away. Same `api_key`, same `cert_sha256` (the pin is the identity, not the host name). Absent when the
  PC has only one address. **App behaviour (F-55):** store them with the pairing. Before a request, use the address
  that last worked; if it can't be reached (connect failure or timeout, not an HTTP error), try the others in
  order with a short connect timeout (about 1.5 s each) and remember the one that answers. `/health` checks do the
  same. The Home-Wi-Fi warning of contract 06 §3.6 applies only when *no* stored address is a Tailscale one.
  Older builds ignore the field (it was verified that build 26 reads only the keys it knows).

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
- **2026-11-12: "Corrections and shortcuts".** The same `name_corrections` list now also holds shortcuts
  ("here's my link" → "here's my link: cyrus-studio.com"). Call the section **"Corrections & shortcuts"** in the app,
  columns "When I say" / "Type this". Only name-like targets are spelling hints (≤ 3 words, ≤ 30 chars, no digits,
  @, / or web address), so a shortcut target never appears in `from_name_corrections` and doesn't count in `chars`.
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
  "heard as" under 60 characters, no commas; "write as" up to **200** characters, **commas allowed** (since
  2026-11-12, for shortcuts like an address); no line breaks; not identical on both sides; no "heard as" listed twice.
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


## 11. Custom triggers · LIVE in server code (Whisper-Unchained-Server, 2026-11-16; app side NEW)

A custom trigger is a phrase the user says that stands for a saved instruction to their AI ("prompt ticket" turns a
rambling bug description into a ticket). The server detects the phrase and runs the instruction, so **dictating
through the PC already works from the phone with no app change.** This section is for the app's screen that lists,
writes and edits them. The PC's list is the list, as with vocabulary (§8). Self-hosted preset only.

A trigger:
```json
{ "id": "bug-ticket", "name": "Bug ticket", "phrases": ["prompt ticket", "ticket this"],
  "prompt": "Rewrite the text as a bug ticket ...", "enabled": true,
  "library_id": "ticket", "library_version": 1, "author": "Whisper Unchained" }
```
`library_id`, `library_version`, `author` (only on triggers added from the library) and `temperature` are optional.

**`GET /v1/custom_triggers`** (auth like every `/v1` route):
```json
{ "custom_triggers": [ ... ],
  "limits": {"triggers": 100, "name": 40, "phrases": 6, "phrase": 60, "prompt_min": 10, "prompt": 6000} }
```

**`POST /v1/custom_triggers`** `{"set": [trigger, ...], "remove": ["id", ...]}` → same body as GET plus
`"removed": [...]` and `"replaced": [...]`.
- A trigger in `set` whose `id` matches an existing one **replaces the fields sent** (send only `{"id", "enabled"}` to
  flip the switch; send `name`, `phrases`, `prompt` to edit). One with a new `id`, or none, is added; the server
  gives it an id from its name. Removes run first. 1 to 20 entries per call.
- **The instruction (`prompt`) must be editable in the app**, in a multi-line box, for every trigger including ones
  added from the library (Dominic, 2026-11-16). Changing the prompt of a library trigger drops its `library_id`:
  it is now the user's own.
- Rules, checked on the whole list before anything is written (a refused call changes nothing): name 1 to 40
  characters; 1 to 6 phrases, each 2 to 60 characters, no commas or line breaks, and either two words or at least 6
  letters; prompt 10 to 6,000 characters; no phrase used twice, and none that is already a built-in trigger phrase
  ("prompt AI", "hey Whisper", "end prompt"). Otherwise `400 invalid_custom_triggers` with a
  readable `message` (show it).
- Takes effect immediately; no restart. Replacing or removing first backs up the PC's word file (as §8).
- Older servers return `404`: hide the screen's editing and say "Update the PC app to use custom triggers".

**The shared library** is one public file, fetched by the app directly (plain GET, send nothing about the user):
`https://raw.githubusercontent.com/Dominic-Shirazi/Whisper-Unchained-Server/main/trigger_library/library.json`
```json
{ "version": 1, "updated": "2026-11-16", "triggers": [
  { "id": "ticket", "name": "Bug ticket", "description": "...", "category": "Work writing",
    "phrases": ["prompt ticket", "ticket this"], "prompt": "...", "model": "any", "author": "...", "version": 1 } ] }
```
- `model`: `"any"` → show "Works with small local models"; `"strong"` → "Needs a strong model". Words, not colour.
- To add one: POST it in `set` with `id` = the library id, `library_id` = the same id, `library_version` = its
  `version`, plus `name`, `phrases`, `prompt`, `author`, `enabled: true` (and `temperature` if it has one).
- State per entry: not added → "Add"; an installed trigger has that `library_id` → "✓ Added"; the library's
  `version` is higher than the installed `library_version` → "Update".
- Show the full instruction before adding ("Show the instruction"), with the line "A trigger is only instructions
  for your AI: read them before adding."
- If the fetch fails, say so; the app does not need to bundle a copy.

**Expected app behavior:** a "Custom triggers" screen next to Corrections & shortcuts: each trigger with its name,
`Say: "…" or "…"`, an on/off switch, Edit and Remove (Undo snackbar, as §8); "Browse the library" and "Write your
own". Same wording as the Windows Settings page. Offline edits queue and replay as in §8. On-device and cloud
transcription don't run custom triggers yet; say so on the screen when the PC isn't the transcriber.

## 12. Typed instruction · LIVE in server code (Whisper-Unchained-Server, 2026-11-16; app side NEW, F-54)

For when the user would rather not talk (an airport, an open office): they **type** what the AI should do. Same
editor, same custom triggers, no audio.

`POST /dictate/v1/edit_text` (JSON, same auth as everything else)
```json
{ "instruction": "make it more formal", "selection": "see you tmrw at the usual place" }
```
→ `200 { "text": "…", "edited": true }`

- `instruction` (required, 1 to 4,000 characters) is what the user typed. `selection` (optional) is the text to
  work on.
- **With `selection`:** the reply is the selection rewritten to follow the instruction (the app replaces the
  selection with it).
- **Without `selection`:** the instruction is all there is, so the AI writes new text ("a two-line thank-you note
  to a client") and the app inserts it at the cursor.
- **Custom trigger phrases work in the typed text.** If the instruction contains a trigger's phrase, the trigger's
  saved instruction is applied: to `selection` when there is one, otherwise to the rest of the typed text
  (`"prompt ticket the login page crashes when you tap pay twice"` → a bug ticket). Only the phrase and nothing to
  work on → `edited: false` and the input back.
- `edited: false` means the AI gave no usable answer; `text` is then the selection unchanged, or `""` when there
  was none. **Never paste an empty or unchanged result as if it worked**: keep what the user typed in the box and
  say "The AI editor didn't answer."
- Errors (§5 shape): `400 missing_instruction`, `413 instruction_too_long`, `413 selection_too_long`,
  `503 server_paused`. Older servers answer `404`/`405`: say "Update the PC app to type instructions".
- Not logged as a dictation and not added to the audio corpus (there is no audio).
- Self-hosted preset only for now: with on-device or cloud transcription and no PC, the app would have to call the
  user's editor LLM itself (contract 03 prompt); out of scope for F-54, hide the feature there.

**Choosing a trigger without saying or typing its phrase** uses the `trigger` field of §1: the id from §11's list.

## 13. Built-in trigger phrases · LIVE in server code (Whisper-Unchained-Server, 2026-11-16; app side NEW, F-60)

The phrases that hand a dictation to the AI ("hey Whisper", "prompt AI") and the one that ends the spoken
instruction ("end prompt") were only editable on the PC. Now the phone can show and change them too.

`GET /v1/trigger_phrases` (same auth as everything else)
```json
{ "trigger_phrases": [ {"phrase": "prompt AI", "action": "edit"}, {"phrase": "hey Whisper", "action": "edit"},
                       {"phrase": "end prompt", "action": "end"} ],
  "actions": [ {"id": "edit", "label": "AI edit"}, {"id": "end", "label": "End of instruction"} ],
  "limits": { "phrases": 40, "phrase_min": 2, "phrase": 60 } }
```
`POST /v1/trigger_phrases` with `{"phrases": [ {"phrase": "...", "action": "edit" | "end"}, ... ]}` replaces the
**whole list** (it is short; send every row, edited or not) and returns the same shape as GET.
- Rules, checked before anything is written (a refused call changes nothing): 1 to 40 rows; each phrase 2 to 60
  characters with at least one letter, no commas or line breaks; no phrase twice (case-insensitive); **at least one
  `edit` phrase must remain**; none may be a phrase one of the user's custom triggers already uses. Otherwise
  `400 invalid_trigger_phrases` with a readable `message` (show it).
- Takes effect immediately. The PC backs up its word file first.
- Matching is forgiving on the server side: words may run together or be separated by punctuation, and an
  ALL-CAPS word of 2 to 5 letters ("AI") also matches spelled out ("a.i.", "A. Eye"). So the user types the phrase
  the way they'd write it; tell them to add a second row for a mishearing they keep getting ("prompte I").
- Older servers answer `404`: show the phrases screen read-only with "Update the PC app to change these".
- Older PC configs may still hold retired mean-mode rows; the server never returns them and drops them on the
  first save.

**Connection code (PC to PC, 2026-11-16).** The same payload as §7, as one line of text for a PC with no camera:
`"WU1-" + base64url(payload JSON, no padding)`. The Windows app copies it from Settings > Devices & Apps and the
other PC pastes it in Settings > Transcription. Mentioned here so the format is written down in one place; the
phone keeps using the QR code and needs nothing new. (If the app ever wants a "paste code" fallback for a phone
whose camera is broken, this is the format.)
