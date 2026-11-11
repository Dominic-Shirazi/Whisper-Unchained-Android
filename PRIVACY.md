# Privacy Policy: Whisper Unchained

**Effective:** 2026-11-11 (draft, to be finalized before the Google Play release)
**Developer:** Cyrus Studio (cyrus-studio.com) · **Contact:** [privacy contact email to be added]

Whisper Unchained is a voice dictation app. It is free, open source, has no account, no ads, no analytics and no
tracking. **We (the developer) do not run any server that receives your data, and we never see your voice or text.**
This policy explains what the app handles on your phone and where it sends it, which is always a destination you choose.

## What the app accesses and why

| Data | When | Why | Where it goes |
| :--- | :--- | :--- | :--- |
| **Microphone audio** | Only while you have tapped the mic button to dictate | To turn your speech into text | Depends on your setting: transcribed **on your phone** (nothing leaves the device), or sent to **your own computer** running Whisper Unchained Server, or to the **cloud speech service you chose** (for example Groq or OpenAI) using your own API key |
| **Transcribed text** | After each dictation | To type it into the app you're using; optionally to clean it up | Inserted into the text field you are typing in. If you turned on AI cleanup, also sent to the language model you configured (your own computer or your chosen cloud service) |
| **The focused text field** (Accessibility service, optional) | Only when you dictate | To know which text box to type into and where the cursor is, so your words go in the right place | Stays on your phone. It is never stored or sent anywhere |
| **Settings** (server address, API keys, vocabulary, style choices) | Always | To remember your choices | Stored only on your phone. API keys are encrypted with the Android Keystore |

The app does **not** read password fields or payment fields, does not record your screen, does not record phone calls,
and does not collect contacts, location, identifiers or usage statistics.

*Whisper Unchained Full* (the version distributed outside Google Play) can also transcribe audio that is playing on the
phone (for example a video), only after you turn that on and grant Android's screen/audio capture permission each
session. The Google Play version does not include this feature.

## Third parties

We do not sell, rent or share your data with anyone. When you choose a cloud speech or language-model service, your
audio or text is sent directly from your phone to that service under **its** privacy policy and terms (for example
Groq's or OpenAI's). Choose a service you trust, or keep everything on your own computer or on the phone.

## Security

- Connections to cloud services use HTTPS. Connections to your own computer are as secure as the address you set:
  through Tailscale (encrypted) or `https://` they are encrypted; a plain `http://` address on your home network is not.
- API keys are encrypted on the phone with the Android Keystore.
- The app is open source, so anyone can check what it does:
  https://github.com/Dominic-Shirazi/Whisper-Unchained-Android

## Retention and deletion

Each recording is saved briefly in the app's private storage while it is transcribed, and deleted as soon as the text
is inserted. If the transcription couldn't be delivered (for example your computer was unreachable), that one recording
is kept so the app can retry, and deleted after the retry or after 24 hours at most. The app keeps no transcripts. Your settings stay on the phone until you clear the app's data or uninstall it. Anything you sent to your
own computer or to a cloud service is kept or deleted according to that computer's settings or that service's policy;
we never have a copy, so there is nothing for us to delete.

## Children

Whisper Unchained is not directed at children under 13 and does not knowingly collect any data from them.

## Changes

If this policy changes, the new version will be published at this address with a new effective date.
