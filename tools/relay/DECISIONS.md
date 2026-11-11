# Relay decisions log

One line per settled question. Claude owns this file (Gemini's decisions arrive in its `answer`/`ack` messages).
Newest at the bottom. Format: `date | Id | decision | who asked → who answered`.

## Settled

- 2026-11-10 | — | Relay set up in this repo; contracts live in `contracts/`; Claude owns server contracts, Gemini owns app behavior. | Dominic
- 2026-11-10 | Q-1 | Pairing QR carries `base_url` ending in `/v1` (not the full endpoint URL); app derives `/audio/transcriptions`, `/models`, and `/health`. | Claude → Gemini
- 2026-11-10 | — | Product name: **Whisper Unchained** (Android app) and **Whisper Unchained Server** (desktop). Rename `applicationId` away from `com.example.whisperremote` before the first Play upload. | Dominic
- 2026-11-10 | K-6 | Accent color is teal `#2FB79A` on phone and desktop (contract 04 §1). | Dominic
- 2026-11-10 | K-6 | Phone and desktop share section names, model cards and a Words & Triggers screen (contract 04). | Dominic
- 2026-11-10 | K-6 | Hint-echo trim: server does it for self-hosted; app trims only on cloud presets, with the strict rule (contract 01 §10). | Claude
- 2026-11-11 | — | Two builds: `play` = `com.cyrus_studio.whisperunchained`; `full` = `com.cyrus_studio.whisperunchained.full` ("Whisper Unchained Full", keeps internal-audio capture, label without brand names). | Dominic
- 2026-11-11 | — | V1 = Play-ready: v1.1 split builds, v1.2 UI makeover + practice-style intro, v1.3 on-device transcription (minimum: tap/tap, transcribe on phone, name corrections, fallback), v1.4 Play submission. See ROADMAP.md §5. | Dominic
- 2026-11-11 | — | UI makeover direction (ROADMAP §5.1) replaces the K-6 teal look when v1.2 starts: dynamic color, borderless grouped rows like One UI, no tagline, light + dark, mic button keeps its brand color. | Dominic
- 2026-11-11 | — | Phone bubble stays tap to start / tap to stop; no hold-to-talk (holding drags it). | Dominic
- 2026-11-11 | F-20 | v1.2 makeover spec = contract 04 v2: Compose + Material 3, dynamic color (fallback #3E6FD8), One UI-style grouped rows, no teal except mic button/logo, light + dark, 7-page intro, screenshot review (Claude, then Dominic) before release. | Dominic → Claude

## Open (escalated to Dominic)

_None._
