# 04 · Look and feel (v1.2 makeover)

**Version:** 2 (2026-11-11) · **Owner:** Claude (spec, desktop side), Gemini (app side) · **Asked for by:** Dominic
**Replaces** version 1 (teal look, tabs). Kept from v1: the shared section names (§4) and the model/address/Words &
Triggers behavior (§6), which still apply.

Dominic: the current app "looks like what the internet calls AI slop": the teal, the border around every section, the
"Fast, private voice dictation" subtitle, a tab underline that doesn't fit the word. He wants it to feel native next to
Samsung's One UI settings (grouped, calm, consistent), **not** a pixel copy of One UI, in **light and dark mode**.
References: Dominic's screenshots in `.tempUI/` (One UI Settings light + dark, Wispr Flow's onboarding), ROADMAP §5.1–5.2.

## 1. Rules (the checklist every screen is reviewed against)

1. **No borders, no shadows, no gradients, no glow.** Groups are separated by background color and spacing only.
2. **One accent color**, used only for: current values (the second line of a row), switches that are on, the primary
   button, links. Everything else is neutral.
3. **No emoji as icons** (today: ⚙️ ✨ 📷 ✓). Use Material Symbols (Rounded), one set, one weight.
4. **No tagline** under the app name. The name appears once, in the large title.
5. **Spacing has hierarchy:** rows inside a group sit close; groups are clearly apart; helper text sits *between*
   groups, not inside them.
6. **Show only what matters for the current choice.** Options appear when the switch/choice that enables them is on.
   Rarely-needed fields hide behind a collapsed row (§3.2).
7. **Group by meaning.** Each group answers one question ("where does my audio go?", "how does it type?").
8. **Colour-blind safe** (Dominic is red/green colour blind): state is never color alone. A switch also moves; a
   status also has a word and an icon ("Allowed ✓" / "Not allowed"). No red-vs-green pairs.
9. **Plain words.** Sentence case. No jargon where a plain word works ("Paste into apps", not "Text Injection Mode").
10. **Light and dark** both designed and reviewed. The floating mic button keeps its current brand color in both.
11. Touch targets ≥ 48dp; text contrast ≥ 4.5:1; works at the system's largest font size without clipping.

## 2. Technology

- **Rebuild `MainActivity` (settings + intro) in Jetpack Compose with Material 3** (Compose BOM, `material3`).
  The current `activity_main.xml` (1,478 lines) is replaced, not restyled. Move logic out of the Activity into a
  ViewModel per screen where it helps; keep `SettingsRepository` as the single source of truth so behavior and
  stored values don't change.
- The floating button, the style pill and the style sheet (in `FloatingButtonService`, Views) **stay Views** for v1.2;
  they only get the new color/shape tokens (§5) and light/dark support.
- **Dynamic color** (`dynamicLightColorScheme` / `dynamicDarkColorScheme`, Android 12+) so the accent follows the
  user's wallpaper like the rest of One UI. Fallback scheme (older Android, or dynamic color off): seed a calm blue
  close to One UI's (`#3E6FD8`). Do not use teal `#2FB79A` anywhere except the mic button / logo.
- Fonts: the system default (`FontFamily.Default`) so Samsung phones show Samsung's font. No custom font.
- Grouped rows: either Material 3's segmented list items (`ListItemDefaults.segmentedShapes`, if available in the
  stable BOM you use) or one small `SettingsGroup { … }` composable of our own. Library allowed if it helps:
  `alorma/Compose-Settings` (MIT). Don't add a One UI library (the only real one is Views-only and fragile).

## 3. Structure

### 3.1 Main screen (like One UI's Settings home)
`LargeTopAppBar` titled **Whisper Unchained** that collapses on scroll (`exitUntilCollapsedScrollBehavior`). Then:

1. **Status card** (where One UI shows the account card): one line saying what's happening now, with an icon:
   "Connected to your PC · large-v3-turbo" / "Using Groq (cloud)" / "Not connected yet · Set up". Tapping opens
   Transcription. While the setup isn't finished, this card says "Finish setup (2 of 5 steps left)" and opens the intro.
2. Groups (rows have a **colored circle icon** on this screen only, like One UI; icons on subpages are optional and
   monochrome). Suggested grouping:
   - **Transcription** (where your voice goes) · **Words & Triggers**
   - **AI Editing** (cleanup + style defaults)
   - **Floating button** (start on boot, Tailscale auto-restart, behavior) · **Paste into apps** · **Permissions**
   - **Phone audio** (Full build only: "Transcribe audio playing on this phone")
   - **About** (version, open-source licenses, "Run setup again", GitHub link)
3. A One UI-style **"Looking for something else?"** card at the bottom is optional (links to Permissions, Run setup).

Row anatomy: title (≈17sp), optional second line with the **current value in the accent color** ("Groq · whisper-
large-v3-turbo", "On", "Clipboard chip"), optional trailing switch. **Split rows** where a row both opens a page and
toggles (a thin vertical divider before the switch).

### 3.2 Transcription page (pairing first, manual setup collapsed)
Dominic's layout:
1. **Scan pairing code** (primary button) with one line: "Open Settings on your PC → Phone → Show pairing QR."
2. **Test connection**, right under it, with a plain result line (contract 01 / v1 §5 wording: "Connected: your PC
   runs large-v3-turbo" / "Paused on your PC" / "Can't reach it: is Tailscale on?"). After a scan the user sees this
   immediately without scrolling.
3. **Manual setup ⌄** — a collapsed row (chevron). Expanded it shows: provider preset, server address (/v1), API key,
   model (read-only "Your PC runs …" for self-hosted; cards for cloud, v1 §4), "Paste code manually".
   Scan *or* fill in, never both. Collapsed by default once a connection works; expanded by default for cloud
   presets that need a key.
4. Cloud-only: **Language model for edits** (chat endpoint, key, model) appears only when a cloud preset is chosen.

### 3.3 Other pages
- **Words & Triggers:** as v1 §6 (vocabulary with budget, sync with PC, name corrections read-only from PC or editable
  locally for cloud, trigger explanation). "Remove the word list when Whisper reads it back" switch lives here.
- **AI Editing:** cleanup switch with the smart-default note ("Following your PC's setting"); the five style defaults
  (Punctuation, Grammar & flow, Tone, Conciseness, Emoji) shown **only when cleanup is on**, each as a row whose
  second line is the current value, opening a simple choice dialog or sheet.
- **Paste into apps:** choice between "Paste automatically (Accessibility)" and "Clipboard chip", each with one line;
  the accessibility choice shows its status and an "Open settings" row.
- **Permissions:** one row per permission (Microphone, Appear on top, Accessibility, Notifications, Battery: no
  restrictions), second line "Allowed" / "Not allowed" with an icon, tap opens the right system page. Status refreshes
  on resume.
- **Floating button:** start on boot; auto-restart Tailscale (shown only if Tailscale is installed); button size /
  opacity later (ROADMAP §5.2).

## 4. First-run intro (replaces the setup checklist card)
Full-screen pages with a **segmented progress bar** at the top, **Back** available, one big headline + one line of
body, one simple illustration (vector, our colors, no emoji), primary button at the bottom, secondary text button
("Skip" / "Not now") where skipping is allowed. Ideas from Wispr Flow, not their text or art.

1. **Welcome:** what it is in one line ("Talk instead of typing, in any app. Your voice goes to your own PC.").
   No account, no sign-up.
2. **Connect:** Scan pairing code (primary) / "Use a cloud service instead" / "Set up later". Shows the Test
   connection result after a scan.
3. **Try it** (practice sandbox): a mock message card inside our own screen. "Tap the box, then tap the mic." The
   **microphone permission is requested on that first mic tap** (not on an earlier screen). Real transcription from
   the user's PC lands in the box; one line celebrates it. If not connected, this page explains and offers Connect
   or Skip.
4. **Appear on top:** why ("so the mic button can float over your apps"), Allow → system page, comes back here.
5. **Paste into apps:** the **prominent disclosure** (Play requires it before the system screen): what the
   accessibility service reads (the focused text field, to put your words in it), what it never does (no passwords
   or payment fields, nothing is stored or sent anywhere except your dictation to your chosen server), that it can be
   turned off any time. Buttons: **Agree** / **Not now (use clipboard chip)**. After Agree: a 3-step visual guide
   (Installed apps → Whisper Unchained → On; "leave the shortcut switch off") and "Open settings".
6. **Stay ready** (skippable): notifications (to tell you when the PC can't be reached) and battery "no
   restrictions" (so Android doesn't kill the button). Each says what goes wrong without it.
7. **What to expect:** where the button appears, tap to start / tap to stop, drag to move; Finish.

Existing users who already completed the checklist don't see the intro; "Run setup again" in About reopens it.

## 5. Tokens

| Token | Light | Dark | Notes |
| :--- | :--- | :--- | :--- |
| Screen background | `surfaceContainerLow` (≈ `#F2F2F4`) | pure black `#000000` | One UI dark is pure black |
| Group container | `surfaceContainerLowest` (≈ `#FCFCFF`) | ≈ `#1C1C1E` (`surfaceContainer`) | no border |
| Group corner radius | 26dp | 26dp | |
| Screen gutter (around groups) | 12dp | | |
| Gap between groups | 16dp | | helper text sits in this gap, 8dp from the group above |
| Row padding | 20dp horizontal, 14dp vertical | | min height 56dp (one line) / 72dp (two lines) |
| Divider | hairline, `outlineVariant` at ~50%, inset to the text start | | only between rows inside a group |
| Row title / value / helper | `bodyLarge` (~17sp) / `bodyMedium` accent / `bodySmall` muted | | |
| Large title | `headlineLarge`, collapsing to `titleLarge` | | |
| Accent | dynamic `primary` (fallback seed `#3E6FD8`) | dynamic `primary` | values, switches, primary button, links only |
| Mic button | current brand color, unchanged | unchanged | Dominic: "our button stays the same color" |

## 6. Shared with the desktop
Section names stay the same on both (v1 §3): **Transcription**, **Words & Triggers**, **AI Editing**; phone-only
pages are **Paste into apps**, **Floating button**, **Permissions**, **Phone audio**. The desktop Settings window keeps
its sidebar; Claude will move it toward the same rules (no borders, one accent, grouped rows) after the phone lands.

## 7. Review process (before anything ships)
1. **Screenshot tests** for every screen and intro page, light and dark, at three sizes: phone (S25, 1080×2340),
   Fold inner (≈1856×2160) and Fold cover (≈904×2316). Roborazzi (Robolectric) or Compose Preview Screenshot Testing;
   output PNGs to `app/screenshots/` (git-ignored) with clear names (`main_dark_s25.png`, …).
2. Gemini sends the folder path in its note. **Claude reviews every PNG against §1** and sends fixes.
3. Then **Dominic approves** on the Fold (release build, not published). Nothing is published before his OK.

## 8. Not in v1.2
On-device transcription (v1.3, ROADMAP §5.4), Smart Replace, per-app styles, history. Don't add features while
rebuilding: every existing setting must still be there and still work (parity list in F-20).

## Appendix: kept from version 1 (still applies)

### v1 §4. Choosing a model

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

### v1 §5. Server address

Same order and wording as the desktop's "Another PC" choice: address (ending in /v1), key (optional), then a
**Test connection** button that says in plain words what happened ("Connected: your PC runs large-v3-turbo" /
"Paused on your PC" / "Can't reach it: is Tailscale on?"). Pairing QR stays at the top as the easy way.

### v1 §6. Words & Triggers tab

- **Vocabulary:** the user's words (add-only on self-hosted, contract 01 §8), plus the name-correction words shown
  greyed and labeled "from name corrections" so the list doesn't look incomplete.
- **Name corrections, "Heard as → Write as":**
  - Self-hosted: read-only list from `GET /v1/vocabulary` → `name_corrections` ("Edit these in Settings on your PC").
  - Cloud presets: an editable local list the app applies itself (whole word, any case, literal match: the same rule
    as the server's and the desktop cloud mode's `apply_name_corrections`). Its "write as" words are also sent as hints.
- **Trigger words:** show the "prompt AI" trigger and a line on what it does (contract 03). Not editable on the phone.
- The **hint echo trim** (contract 01 §10) belongs to this tab's behavior. It needs no user-visible switch once it
  follows the strict rule; if a switch stays, default on and label it "Remove the word list when Whisper reads it back".
