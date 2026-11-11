# Google Play launch kit (v1.4)

Everything Play asks for, with our answers drafted. Researched 2026-11-11 from Google's policy pages (links inline);
re-check the forms when submitting, they change. Status marks: ✅ done · 🟡 drafted · ⬜ to do.

## 0. Long lead items (start now)
- ✅ **Closed-test requirement doesn't apply:** the 12-testers-for-14-days rule is only for *personal* accounts created
  after 13 Nov 2023 ([source](https://support.google.com/googleplay/android-developer/answer/14151465)). We publish
  under **Cyrus Studio LLC's organization account** (EIN + D-U-N-S verified). A short internal test is still wise.
- ⬜ Public developer **contact email** (Play shows it; also the privacy contact). Use a project address, not a personal one.
- ⬜ **Privacy policy URL**: `PRIVACY.md` (🟡 drafted) published at a stable public URL (GitHub Pages or the repo file),
  and linked inside the app (About page).

## 1. Build
- ✅ targetSdk 36 (Play's minimum for new apps since 31 Aug 2026). minSdk 34 (Android 14+).
- ⬜ **App bundle** (`gradlew :app:bundlePlayRelease`), signed with our key, which becomes the **upload key**; enroll in
  **Play App Signing** (Google holds the app-signing key).
- ⬜ Only the `play` flavor goes to Play (no MediaProjection, verified in v1.1).

## 2. In-app requirements (contract 04 §4 + F-22)
- 🟡 **Accessibility prominent disclosure** before sending the user to Accessibility settings: separate screen, says what
  the service reads (the focused text field) and why (to insert dictated text), that nothing is stored or sent, that it
  never touches password/payment fields; **affirmative "Agree"** button (back/home is not consent); a "Not now" path;
  the disclosure can be **shown again** later (Paste into apps page / Run setup again).
  ([policy](https://support.google.com/googleplay/android-developer/answer/10964491))
- 🟡 **Microphone disclosure**: one line before the system prompt: "Audio is recorded only while you tap the mic, and is
  sent to the place you chose (this phone, your PC or your cloud service)."
- 🟡 **Appear on top**: explain, then send to the system page; respect "no".
- ⬜ Privacy policy link in About.

## 3. Play Console forms

### Accessibility declaration (App content → Accessibility API)
- isAccessibilityTool: **false** (a dictation app isn't an accessibility tool under Play's definition).
- Purpose: **App functionality**: "Inserts the user's dictated text into the text field they are typing in, at the
  cursor. Android has no other API that lets an app type into another app's text field."
- Collects or shares personal/sensitive data via the API: **No** (the field's content never leaves the phone).
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

- Nothing else (no identifiers, location, contacts, diagnostics, analytics).
- **Encrypted in transit:** answer **Yes only if** the app refuses plain `http://` to non-local hosts or we decide
  Tailscale/HTTPS-only. Today a LAN `http://` address is allowed, so the honest answer is **No** unless we change that.
  (Open decision for Dominic.)
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
> computer or your own AI service. Say "prompt AI, make this formal" to rewrite what you just said.
>
> **Accessibility service:** Whisper Unchained uses Android's Accessibility service only to type your dictated text into
> the text field you're using. It reads the focused field so it can insert at the cursor; it does not read password or
> payment fields, and nothing it reads is stored or sent anywhere. You can use the clipboard instead if you prefer.
>
> Source code: github.com/Dominic-Shirazi/Whisper-Unchained-Android

- **Graphics:** icon 512×512 PNG (alpha OK, ≤ 1 MB); feature graphic 1024×500 (no alpha); 4–8 phone screenshots
  (1080×1920 or similar, no alpha). Take them from the v1.2 build in light mode + one dark.

## 5. Declaration video script (one recording covers both forms; unlisted YouTube)
Screen-record on the S25, with captions:
1. Open Whisper Unchained (fresh install).
2. Reach the **accessibility disclosure**; scroll slowly so all the text is visible.
3. Tap **Not now** → show the app still works with the clipboard chip → open Paste into apps → show the disclosure again.
4. Tap **Agree** → Android's Accessibility settings → turn on Whisper Unchained (leave the shortcut off).
5. Open another app (e.g. Messages), tap the text box, tap the floating mic: **recording with the notification visible**
   (foreground service), speak, tap again: the text appears at the cursor.
6. Show Settings → Paste into apps → how to turn it off.
