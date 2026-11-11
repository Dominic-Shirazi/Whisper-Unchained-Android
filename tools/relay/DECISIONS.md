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

## Open (escalated to Dominic)

_None._
