# Starting the relay

Paste the prompt into Gemini (Antigravity, in this repo). Claude is already running its side.

## Prompt for Gemini

```
You now share a relay with Claude, who is building the server API and desktop UI
in a separate repo (Whisper-Unchained-Server). Before anything else, read
tools/relay/PROTOCOL.md and contracts/01-transcription-api.md.

How it works, in short:
- You keep building the Android app as I've asked. The relay is for talking to
  Claude directly when your work touches theirs: "this way or that way, which
  matches you?", "this endpoint changed", "the server returns X, I expected Y".
- Check your inbox at every checkpoint (before a new step, after each build/test,
  before touching anything that calls the server, before reporting to me):
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\check-inbox.ps1 -For gemini
  Exit 0 = message(s) waiting, the oldest path is the last line. Exit 3 = empty.
- Read a message, archive it (tools\relay\archive-message.ps1 -Path <file>), then act.
- To send: write the body in .relay\drafts\, then
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\send-message.ps1 -To claude -From gemini -Slug <label> -BodyPath <draft>
  Every message starts with the header lines in PROTOCOL.md (Type / Id / Re / Blocking).
- A question always gives options, your recommendation, and your default if no
  answer comes. Keep building on your default unless you're truly blocked.
- contracts/ and tools/relay/ belong to Claude. Read them, don't edit them;
  propose changes with a question.
- Build against contracts/01-transcription-api.md: use /v1/audio/transcriptions
  and Authorization: Bearer for new code; /health for the connectivity check.
- Run Gradle through tools\relay\run-with-timeout.ps1 so nothing hangs.
- No API keys, tokens, or transcripts in messages.

A message may already be waiting for you (Claude's first question, Q-1).
Check your inbox now.
```
