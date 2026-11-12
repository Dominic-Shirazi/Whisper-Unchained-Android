# 06 · Encrypted connection to the PC (HTTPS), required before Play

**Version:** 1 (2026-11-11) · **Owner:** Claude (spec + server), Gemini (app) · **Asked for by:** Dominic

Why: the pitch is "your own, secure, private". Play's Data safety form asks "Is all user data encrypted in transit?"
and shows the answer on the listing. Dominic: a "No" there reads as vibe-coded, even though Tailscale already
encrypts. So every connection the Play app makes must be TLS, and the answer becomes **Yes**.

## 1. How (no domain, no paid certificate)
- **The server makes its own certificate** the first time it starts: EC P-256, valid 20 years,
  CN "Whisper Unchained Server", SAN `127.0.0.1`, `localhost` and the PC's hostname. Stored next to `.env`
  (`certs/server.pem` + `certs/server.key`); git-ignored, never packaged, kept across updates. The private key is
  readable only by the service and admins.
- **The server speaks HTTPS only**, on the same port as today (default 5000). The desktop listener and settings
  window talk to `https://127.0.0.1:<port>` and verify against `certs/server.pem`.
- **Pinning instead of a certificate authority:** the pairing QR carries the certificate's fingerprint
  (`cert_sha256`, lowercase hex SHA-256 of the DER certificate). The phone trusts **exactly that certificate** for
  that PC. This works for LAN IPs, Tailscale IPs and MagicDNS names alike (the pin, not the name, is the
  identity), and a fake server on the same Wi-Fi can't impersonate the PC.
- A real certificate (e.g. `tailscale serve` at https://pc.tailnet.ts.net) also works: normal CA validation.

## 2. Pairing payload v2
```json
{"v":2,"base_url":"https://100.101.102.103:5000/v1","cert_sha256":"9f86d0…","model":"large-v3-turbo","name":"Head-PC","api_key":"wr_sec_…"}
```
`api_key` is still omitted when auth is off. v1 payloads (http, no pin) are rejected by the Play build with "This
pairing code is from an older server. Update Whisper Unchained Server, then scan again."

## 3. Phone (Gemini)
1. **Paired PC:** an OkHttp client whose `X509TrustManager` accepts the server chain only if the **leaf**
   certificate's SHA-256 equals the stored pin, and whose `HostnameVerifier` returns true for that client only
   (the pin is the identity). Never fall back to "trust all".
2. **Pin mismatch** (PC reinstalled, or someone in the middle): never connect. Message: "Your PC's security
   certificate changed. If you reinstalled Whisper Unchained Server, scan its pairing code again." Fallback to the
   phone model still applies.
3. **Play flavor:** refuse `http://` everywhere: pairing, Custom endpoint, cloud presets (all already https). A
   Custom endpoint must be https with a normally valid certificate, or paired via QR with a pin.
   `android:usesCleartextTraffic="false"` / network security config with cleartext off.
4. **Full flavor:** may still allow `http://` for a Custom endpoint, with a visible "Not encrypted" warning (icon +
   text).
5. **Old http pairing on upgrade:** banner on Home: "Your PC connection is now encrypted. Update Whisper Unchained
   Server on your PC, then scan its new pairing code." until re-paired.
6. **Home-Wi-Fi warning** (Dominic): after pairing to a non-Tailscale address (not 100.64.0.0/10, not `*.ts.net`):
   "Connected over your home Wi-Fi. Away from home your phone can't reach your PC unless you install Tailscale on
   both. [Set up Tailscale] [Home only for now]". Also offer the phone-model backup (F-36 item 7). Keep it visible in
   Settings → Transcription as a one-line note while the address is a LAN one.

## 4. Data safety answer
Encrypted in transit: **Yes** (pinned TLS to the user's PC; HTTPS to cloud providers; on-device mode sends nothing).

## 5. Done when
- Server serves only HTTPS; desktop dictation, game mode, settings tests all work over it (server tests pass).
- Phone pairs from the new QR on Wi-Fi and Tailscale; a wrong-pin server is refused; the Play APK has no cleartext.
- Dominic's Fold re-paired and dictating.
