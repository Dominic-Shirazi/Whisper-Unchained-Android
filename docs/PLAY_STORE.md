# Google Play launch kit (v1.4)

Everything Play asks for, with our answers drafted. Researched 2026-11-11 from Google's policy pages (links inline);
re-check the forms when submitting, they change. Status marks: ✅ done · 🟡 drafted · ⬜ to do.

## 0. Long lead items (start now)
- ✅ **Closed-test requirement doesn't apply:** the 12-testers-for-14-days rule is only for *personal* accounts created
  after 13 Nov 2023 ([source](https://support.google.com/googleplay/android-developer/answer/14151465)). We publish
  under **Cyrus Studio LLC's organization account** (EIN + D-U-N-S verified). A short internal test is still wise.
- ✅ Public developer contact email: **Dominic@cyrus-studio.com** (also the privacy contact).
- ✅ Website **https://whisperunchained.cyrus-studio.com**; privacy policy URL
  **https://whisperunchained.cyrus-studio.com/privacy/** (the official policy; `PRIVACY.md` in the repo just points to it); linked in the app's About page.

## 1. Build
- ✅ targetSdk 36 (Play's minimum for new apps since 31 Aug 2026). minSdk 31 (Android 12+).
- 🟡 **App bundle** built for build 38 (commit f4db020, version code 38, minSdk 31, all four ABIs, 60,836,240 bytes, SHA-256
  `216c5c2f935bd9b3f66b5fc420a654d14ab2d98f506388850dd1857fa0835bdb`, at
  `app/build/outputs/bundle/playRelease/app-play-release.aab`); not uploaded yet.
  (`gradlew :app:bundlePlayRelease`), signed with our key, which becomes the **upload key**; enroll in
  **Play App Signing** (Google holds the app-signing key).
- ⬜ **ABIs:** GitHub APKs are trimmed to `arm64-v8a` (v1.3, saves ~40 MB). The Play **bundle** should include all ABIs
  (Play delivers only the device's own), so 32-bit Android Go phones and x86 Chromebooks aren't excluded: apply the
  `abiFilters` only to APK builds, not `bundlePlayRelease`.
- ⬜ Only the `play` flavor goes to Play (no MediaProjection, verified in v1.1).
- ✅ (checked: `app/src/play/AndroidManifest.xml` removes it) The `play` APK must **not** declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (restricted; dictation isn't an allowed
  use case). Play opens the battery settings list instead; `full` keeps the direct prompt (F-27). Check with
  `aapt2 dump permissions`.

## 2. In-app requirements (contract 04 §4 + F-22)
- 🟡 **Accessibility prominent disclosure** before sending the user to Accessibility settings: separate screen, says what
  the service reads (the focused text field) and why (to insert dictated text), that nothing is stored, and nothing is sent except words the user highlights and asks the AI to rewrite, that it
  never touches password/payment fields; **affirmative "Agree"** button (back/home is not consent); a "Not now" path;
  the disclosure can be **shown again** later (Paste into apps page / Run setup again).
  ([policy](https://support.google.com/googleplay/android-developer/answer/10964491))
- 🟡 **Microphone disclosure**: one line before the system prompt: "Audio is recorded only while you tap the mic, and is
  sent to the place you chose (this phone, your PC or your cloud service)."
- 🟡 **Appear on top**: explain, then send to the system page; respect "no".
- 🟡 Privacy policy link in About (F-22, URL updated in F-23).

## 3. Play Console forms

### Accessibility declaration (App content → Accessibility API)
- isAccessibilityTool: **false** (a dictation app isn't an accessibility tool under Play's definition).
- Purpose: **App functionality**: "Inserts the user's dictated text into the text field they are typing in, at the
  cursor. Android has no other API that lets an app type into another app's text field."
- Collects or shares personal/sensitive data via the API: **Yes, since build 30** (was No). When the user highlights
  text and asks for a rewrite, the highlighted words are read through the API and sent to the AI on the PC or service
  the user set up. Only on that request, only the highlighted words, never to us. Answering No would no longer be
  true. Wording for the form: "When the user highlights text and asks the app to rewrite it, the highlighted text is
  sent to the user's own computer or the AI service they configured, and the result replaces it. Nothing is sent
  otherwise, and nothing is sent to the developer."
- **Video** (unlisted YouTube): see §5.
- The listing's full description must mention the accessibility use (§4).

### Foreground service declaration (App content → Foreground service permissions)
- Type **microphone**: "Records the user's voice after they tap the floating mic button, so dictation keeps working while
  another app is in front." Impact if deferred/interrupted: "The dictation is cut off and the user's words are lost."
- Video: the user taps the floating button, recording runs with the notification visible, text appears in another app.

### Data safety
Google defines "collected" as leaving the device, whoever receives it
([source](https://support.google.com/googleplay/android-developer/answer/10787469)); there is no FAQ for a server the
user configures, so we declare conservatively:

| Data type | Collected | Shared | Optional? | Purpose | Ephemeral |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Audio → Voice or sound recordings | **Yes** (when transcribing on a PC or cloud service) | **No** (sent only where the user chose) | Required for that mode (on-device mode sends nothing) | App functionality | No (cloud services keep data under their own terms) |
| App activity → Other user-generated content (dictated text sent for AI cleanup) | **Yes** | No | **Optional** | App functionality | No |

| App activity → Other user-generated content (text the user highlighted and asked the AI to rewrite; typed instructions) | **Yes** (since build 30) | No | **Optional** | App functionality | No |

- Nothing else (no identifiers, location, contacts, diagnostics, analytics).
- **Encrypted in transit:** **Yes**, once contract 06 ships (until then a LAN `http://` address is allowed).
  **Decision (Dominic, 2026-11-11): HTTPS is required before launch; the answer will be Yes.** "It's your own, it's
  secure, it's private": a "No" on the listing would undercut that. Design: contracts/06-https.md (the server makes its
  own certificate; the pairing QR carries its fingerprint; the Play app refuses http://).
- **Deletion:** "No data is stored on developer servers. Users delete data on their own computer or with their chosen
  provider; recordings on the phone are deleted after transcription."

### Other App content forms
Content rating questionnaire (utility, no user-to-user content), target audience 18+ / not designed for children,
ads: no, government/financial/health/news: no.

## 4. Store listing (🟡 draft)
- **Name:** Whisper Unchained
- **Short description (≤ 80):** "Dictate into any app. Your voice goes to your own PC, the phone, or your cloud key."
- **Full description:**

> Talk instead of typing, in any app. Tap the floating mic, speak, tap again, and your words appear where your cursor is.
>
> Whisper Unchained is free and open source, with no account, no ads and no tracking. You decide where your voice goes:
> • **On this phone**: fast offline transcription, nothing leaves the device.
> • **Your own computer**: pair with the free Whisper Unchained Server (Windows) for the most accurate results, at home
> or anywhere through Tailscale.
> • **Your cloud service**: use your own Groq or OpenAI key.
>
> Optional AI cleanup removes "um"s, applies "no wait, I mean…" corrections and fixes punctuation, using your own
> computer or your own AI service. Say "prompt AI, make this formal" to rewrite what you just said, or highlight text
> and say what to change. Skills save a long instruction under a short phrase: say "skill grocery list" and your
> shopping is grouped by aisle.
>
> **Accessibility service:** Whisper Unchained uses Android's Accessibility service only to type your dictated text into
> the text field you're using, and to rewrite text you highlight when you ask it to. It reads the focused field so it
> can insert at the cursor. If you highlight text and ask for a rewrite, those words go to the AI on your own computer
> or the service you set up, and nowhere else. It does not read password or payment fields, and nothing is stored or
> sent to us. You can use the clipboard instead if you prefer.
>
> Source code: github.com/Dominic-Shirazi/Whisper-Unchained-Android

- **Graphics:** icon 512×512 PNG (alpha OK, ≤ 1 MB); feature graphic 1024×500 (no alpha); 4–8 phone screenshots
  (1080×1920 or similar, no alpha). Take them on a real phone from build 38: the six screens are listed in the relay
  message A-68 (home; mic recording with the style sheet; highlight chip and its sheet; Skills; phrase editor;
  on-phone mode). The images in `docs/store/` are 720 px test renders and are not usable as they are.
- **Foldable screenshots** (required from April 2027, accepted now; Play Console > Grow > Store presence > Store
  listings). Google asks for at least one unfolded 1:1 screen, one unfolded 4:3 screen and one folded 10:16 portrait
  screen. Ready to upload, taken on the emulators with sample data only, in `docs/play/screenshots/foldable/`:
  - 1:1 unfolded: `01-fold-1to1-home.png`, `02-fold-1to1-skills.png` (2160×2160)
  - 4:3 unfolded: `03-fold-4to3-on-device.png`, `04-fold-4to3-phrase-editor.png` (2160×1620)
  - 10:16 folded: `05-fold-outer-10to16-home.png` (1080×1728)
  - optional 16:9 phone landscape: `06-phone-16to9-landscape-home.png` (1920×1080)
- **Adaptive quality (Tier 3, required from April 2027):** checked and fixed in commit 440c2f5 (relay A-71): rotation,
  fold and unfold keep recordings, typed text, scroll position and downloads; the floating button stays on screen;
  split-screen works at half width. Checked on emulators, not on a real foldable. Rule:
  developer.android.com/blog/posts/google-play-adaptive-quality-standards. Separate deadline: target API 37 by
  August 2027.

## 5. Declaration video script (one recording covers both forms; unlisted YouTube)
Screen-record on the S25, with captions:
1. Open Whisper Unchained (fresh install).
2. Reach the **accessibility disclosure**; scroll slowly so all the text is visible.
3. Tap **Not now** → show the app still works with the clipboard chip → open Paste into apps → show the disclosure again.
4. Tap **Agree** → Android's Accessibility settings → turn on Whisper Unchained (leave the shortcut off).
5. Open another app (e.g. Messages), tap the text box, tap the floating mic: **recording with the notification visible**
   (foreground service), speak, tap again: the text appears at the cursor.
6. Show Settings → Paste into apps → how to turn it off.

## 6. Privacy page updates (whisperunchained.cyrus-studio.com/privacy/)
The page is accurate for v1.1. Update it **before** each of these ships:
- **v1.2:** "What stays on your phone": recordings are no longer kept until the next one. New text: each recording is
  deleted as soon as its text is inserted; if it couldn't be delivered, that one recording is kept for a retry and
  deleted after the retry or 24 hours at most. Add to "Accessibility service": it never reads or types into password,
  PIN or card-number fields.
- **v1.3:** add "On this phone" to "Where your voice and text go" (speech recognition runs on the phone; nothing leaves
  it), and that the app downloads the speech model files from Hugging Face (the live page wrongly says GitHub; Hugging Face sees your internet address, like any download).
- Mention the microphone notice shown before Android's permission prompt (optional; the page already explains it).
- **Build 30 and later (must be fixed before the Play submission; the live page is wrong today):**
  - "Accessibility service" says "It does not read ... what you type, and nothing it sees is sent anywhere." Replace
    with: "It reads the text box you are in so it can put your words at the cursor. If you highlight text and ask
    for a rewrite, the highlighted words are sent to the AI you set up (usually on your own PC) and the answer
    replaces them. Nothing else it sees is stored or sent, and nothing is ever sent to us. It never reads or types
    into password, PIN or card number fields."
  - "The optional AI editing features" sentence: add highlighted text and typed instructions to what is sent.
  - Remove "from v1.3, coming soon" (three places): 1.3 is out.
  - "What stays on your phone": add that About > Delete all local data clears it.
  - Skills are saved on the PC, not the phone.
  - The page says "MIT License". The licence has not been decided (AGPL or MIT); make the page match the LICENSE
    file once there is one.
  - Windows section: setup can also download a second speech engine and its model (from GitHub and Hugging Face) for
    AMD and Intel graphics cards.
  - Change the effective date.

