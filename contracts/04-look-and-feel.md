# 04 · Look and feel: phone and desktop should feel like one product

**Version:** 1 (2026-11-10) · **Owner:** Claude (desktop side), Gemini (app side) · **Asked for by:** Dominic

Dominic: "the look and feel of the two should mirror each other a little bit, especially as it applies to choosing
a model, the IP addresses and whatnot, words and triggers." Not pixel-identical (Android vs. a desktop window),
but the same names, the same colors and the same way of picking things.

Desktop reference: Open_Whisper_Flow `client/settings_gui.py` (Settings window) and `client/model_catalog.py`.

## 1. Colors

| Token | Hex | Use |
| :--- | :--- | :--- |
| Accent | `#2FB79A` (hover/pressed `#3ACFAF`) | **The** accent: selected tab, buttons, switches, links, section titles. Replaces the app's blue. |
| Ground | `#15171C` | window/screen background |
| Surface | `#1B1E25` | cards, sidebar |
| Raised | `#222731` | input fields |
| Border | `#2B313C` | outlines, dividers |
| Text | `#E7E9EC` | primary text |
| Muted | `#868E9C` | help text, secondary labels |
| Danger / Warn | `#D9694A` / `#D9A441` | errors / warnings |

Teal was chosen 2026-11-10 (matches the logo and the desktop). Dominic is red/green color blind: never rely on
color alone to show state (a switch also moves; an error also has text).

## 2. Header

Logo + "Whisper Unchained" at the top of the page. The desktop Settings window now has this too (logo, name in the
accent color, then the page title). The app already matches.

## 3. Section names (same words on both)

| Desktop Settings section | Phone tab |
| :--- | :--- |
| Transcription (where it runs: this PC / another PC / cloud; model; address; key) | **Transcription** (was "Server & Model") |
| Words & Triggers (vocabulary, "heard as → write as" corrections, trigger words) | **Words & Triggers** (new tab; vocabulary moves here from Server & Model) |
| AI Editing (editor model, cleanup style, tone) | **AI Editing** (was "Edits") |
| (desktop-only: Hotkey, Startup, Denoise, ...) | **Phone** (was "Text Input": paste mode, permissions, behavior switches) |

## 4. Choosing a model

The desktop shows a list of model **cards** (name, size, speed, a one-line note, the recommended one marked),
not a free-text box. The phone should do the same:

- **Self-hosted preset:** the PC decides the model (changing it needs a restart there). Show it **read-only**
  from `GET /v1/models` ("Your PC runs large-v3-turbo"), with a note "Change it in Settings on your PC".
  The free-text Model Name box sends a value the server ignores, which is confusing.
- **Cloud presets:** cards from a small built-in list per provider, e.g. Groq: `whisper-large-v3-turbo`
  (recommended: fast, cheap), `whisper-large-v3` (most accurate); OpenAI: `whisper-1`, `gpt-4o-mini-transcribe`,
  `gpt-4o-transcribe`. Keep a "Custom model name" entry at the bottom for anything else.
  Note: the desktop's Groq default is `whisper-large-v3-turbo`; the app's is `whisper-large-v3`. Use turbo on both.
- **Custom endpoint:** free text is fine.

## 5. Server address

Same order and wording as the desktop's "Another PC" choice: address (ending in /v1), key (optional), then a
**Test connection** button that says in plain words what happened ("Connected: your PC runs large-v3-turbo" /
"Paused on your PC" / "Can't reach it: is Tailscale on?"). Pairing QR stays at the top as the easy way.

## 6. Words & Triggers tab

- **Vocabulary:** the user's words (add-only on self-hosted, contract 01 §8), plus the name-correction words shown
  greyed and labeled "from name corrections" so the list doesn't look incomplete.
- **Name corrections, "Heard as → Write as":**
  - Self-hosted: read-only list from `GET /v1/vocabulary` → `name_corrections` ("Edit these in Settings on your PC").
  - Cloud presets: an editable local list the app applies itself (whole word, any case, literal match: the same rule
    as the server's and the desktop cloud mode's `apply_name_corrections`). Its "write as" words are also sent as hints.
- **Trigger words:** show the "prompt AI" trigger and a line on what it does (contract 03). Not editable on the phone.
- The **hint echo trim** (contract 01 §10) belongs to this tab's behavior. It needs no user-visible switch once it
  follows the strict rule; if a switch stays, default on and label it "Remove the word list when Whisper reads it back".
