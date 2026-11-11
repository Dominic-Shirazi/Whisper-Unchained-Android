# 03 · "prompt AI" voice commands

**Version:** 1 (2026-11-10) · **Owner:** Claude · **Server reference implementation:** Open_Whisper_Flow `api/edit_commands.py`

The speaker dictates some text, says the trigger ("prompt AI"), then an instruction:
*"The meeting moved to noon. Prompt AI, make this formal."* The editor LLM applies the instruction to the
text before the trigger. Dominic tests it with *"… prompt AI, this was a test, so simply reply past"* and
expects exactly `past`.

| Where | Who runs it |
| :--- | :--- |
| Head PC (self-hosted preset; also the desktop hotkey) | The server, automatically in `processing=full` / `clean`. **The app does nothing extra.** |
| The app with a cloud ASR preset | The app, with the user's editor LLM (the chat endpoint from the Edits tab) |

Both build from `contracts/03-edit-prompt.json`, byte-identical to the server's `api/edit_prompt.json`
(a server test checks). Bundle it as an asset. The system prompt is Dominic's tuned editor prompt: don't reword it.

Mean mode ("prompt roast / TikTok / disses") is **Head PC only** (it needs an uncensored model and a local
insults file). It's not part of this contract.

## 1. Detecting and splitting (exact)

- **Trigger patterns:** `trigger_patterns` (regular expressions). Ignore any pattern containing the letters
  `end` (that's the terminator, never a split point).
- Combined regex: `\b(` + patterns joined with `|` + `)\b`, **case-insensitive**. Use the **first** match.
- `content` = text before the match; `instruction` = text after it, with every `end_patterns` match replaced by
  a space (removes a spoken "end prompt").
- Trim both of the characters in `strip_chars` (space, tab, newline, `,.;:!?-`) from both ends.
- No match → not a command: paste the transcript as usual (and run cleanup if it's on, contract 02).

## 2. The request (exact)

| content | instruction | user message |
| :--- | :--- | :--- |
| non-empty | non-empty | `user_template_both` |
| empty | non-empty | `user_template_instruction_only` |
| non-empty | empty | `user_template_content_only` |
| empty | empty | **nothing to send: paste an empty string** |

Replace `{instruction}` and `{content}` in the template. System message = `system_prompt`.
Call: OpenAI-compatible `chat/completions`, `temperature: 0.2`, `stream: false`; add
`"chat_template_kwargs": {"enable_thinking": false}` when "skip thinking" is on.

## 3. The reply (exact)

- If it contains `</think>`: keep only what follows the **last** `</think>`. Else if it contains `<think>`: keep only
  what precedes the first `<think>`. Trim.
- **Failure** (unreachable, error, timeout, empty reply): paste `content` if non-empty, else the original transcript.
  Never paste the trigger phrase or an error message.
- No length guard: a one-word answer like `past` is the point.

## 4. Interaction with cleanup (contract 02)

When a command fired, cleanup does **not** run on that dictation (the editor already handled it).

## 5. Measured on Head PC (2026-11-10, Bonsai2 27B, no-think)

0.7–1.0 s. "…great work. Prompt AI, this was a test, so simply reply past" → `past`.
"…send me the report by friday thanks. prompt AI make this professional" → "Could you please send me the report by Friday?"
"prompt AI write a two line poem about rain" → a two-line poem.
