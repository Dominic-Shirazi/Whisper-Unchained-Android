# Contracts

The interfaces the Android app and the Head PC server both build against.
A contract file is the source of truth. A relay `contract` message only points at a change here.

- **Owner:** Claude (server side) writes and commits these files. Gemini proposes changes with a relay `question`.
- **Status per section:** `LIVE` (implemented and running), `BUILDING` (being implemented now,
  shape agreed), `PROPOSED` (open for discussion; don't build against it yet without asking).
- **Breaking changes:** never silently. Bump the file's version line, list what broke, send a `contract` message.

| File | What |
| :--- | :--- |
| `01-transcription-api.md` | Transcription, health, models, auth, errors, pairing, shared vocabulary |
| `02-cleanup.md` + `02-cleanup-prompt.json` | Optional LLM dictation cleanup: shared prompt, styles, tidying, failure rules |
