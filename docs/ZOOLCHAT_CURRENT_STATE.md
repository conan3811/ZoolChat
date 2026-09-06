# ZoolChat Current State / Handoff

Last updated: 2026-09-06

## Purpose
This file is the durable handoff for continuing ZoolChat work in a new ChatGPT conversation. Use it as the source of truth for current project state and the pending v0.32 requirements.

## Repository / branches
- Repository: `conan3811/ZoolChat`
- Live backend branch: `zoolchat-v0.27-cloud-beta`
- Android source/reference branch: `zoolchat-v0.29-current-source`
- Main branch contains shared docs/runtime validation material.

## Live backend
Public backend:
`https://p01--zoolchat--8q4zpgbqbm94.code.run`

Current live backend state verified after Northflank restart:
- version: `0.31.0`
- ok: `true`
- db: `true`
- livekitConfigured: `true`
- firebaseConfigured: `true`
- adminConfigured: `true`
- mediaStorage: `local`

## Admin dashboard
Admin dashboard is deployed at:
`/admin`

Full URL:
`https://p01--zoolchat--8q4zpgbqbm94.code.run/admin`

Admin credentials are stored only in Northflank environment variables. Never store or request the admin password in GitHub or chat.

Current admin functionality includes:
- list/search users
- user details
- suspend/reactivate
- revoke sessions/devices
- password reset controls
- data export
- permanent delete
- reports/moderation area
- audit log

## Firebase / calling runtime
- Firebase Android client was configured for project `zoolchat-c2339`.
- Firebase Admin is active on the backend.
- LiveKit is active.
- A Firebase-ready v0.30 APK was previously produced in chat, but do not assume old sandbox artifact links persist into a new conversation; rebuild from project sources when needed.

## ZoolChat v0.32 — 11 primary requirements

### 1. Adaptive Android UI
Make the app fit every phone automatically using real system insets and available screen area.
Must correctly handle:
- status bar
- 3-button navigation (Home / Back / Recents)
- gesture navigation
- display cutouts/notches
- keyboard/IME
- portrait/landscape
- modern edge-to-edge Android behavior
- predictive back where applicable

The UI must expand/shrink automatically and must not hide app content behind Android system bars.

### 2. New registration flow
Registration screen should contain only:
- email
- mobile number
- password
- confirm password

Do NOT request username during registration.

Profile/account settings must allow the user to add/change:
- username
- email

The app must check and clearly show whether username/email is available or already used.

### 3. Login and password recovery
Login should require only:
- mobile number
- password

Forgot password flow:
1. user enters email
2. backend emails a one-time temporary recovery code
3. app shows fields for:
   - temporary code
   - new password
   - confirm new password
4. successful reset invalidates old sessions

Use a temporary one-time code rather than emailing a real reusable password.
Recommended code expiry: about 10–15 minutes.
Protect with rate limits / attempt limits and do not reveal account existence unnecessarily.

Email ownership must be verified before relying on an email for recovery. If email changes in profile, verify the new email before activating it.

### 4. Fix encrypted-looking message text
Users currently report messages appearing as encrypted/ciphertext text.
Fix the actual encryption/decryption path so users see normal plaintext.
Do not merely hide ciphertext.
If decryption fails, show a clear error such as `Unable to decrypt this message` rather than raw ciphertext, and investigate key/session synchronization.

### 5. Read state + notification clearing
When the user opens a conversation:
- mark new messages as read
- clear unread count for that conversation
- remove its notification
- update chat/conversation list state
- send read state to the other party where supported

If the conversation is already open, avoid generating an unnecessary message notification.

### 6. Fix no-audio call bug
Current accepted voice calls can connect with no audible audio.
Investigate and fix:
- local microphone track publication
- remote audio track subscription/playback
- Android audio focus
- MODE_IN_COMMUNICATION
- speaker/earpiece/Bluetooth routing
- microphone permission
- lifecycle at call accept

Do not consider this fixed until tested on two physical Android phones.

### 7. ZoolMeet invitations / waiting room
Inside ZoolMeet add Invite options:
- copy meeting/join code
- invite by username
- invite by mobile number
- invite by email

Invitation flow:
- recipient receives invite
- recipient accepts
- recipient enters waiting state/room
- host/admin receives join request
- host/admin can Admit or Deny

Suggested invitation states:
Pending -> Accepted -> Waiting for host -> Admitted / Denied / Expired

### 8. Message and call sounds / Android notifications
Messages currently have no sound; calls also need proper ringing behavior.

For incoming messages:
- one short notification sound only
- light vibration
- heads-up notification where Android allows
- notification should show sender and message preview according to privacy settings
- tapping opens the conversation
- group notifications intelligently
- opening the conversation clears the notification

Do not force full-screen UI for normal messages.

For incoming calls:
- separate ringtone behavior that continues until answer/decline/timeout
- vibration
- high-priority call notification / Android CallStyle where appropriate
- answer and decline actions
- missed-call notification

Use separate Android notification channels for at least:
- Messages
- Group messages
- Incoming calls
- Missed calls
- ZoolMeet invitations

Handle Android `POST_NOTIFICATIONS` permission and FCM token refresh/cleanup/multi-device delivery correctly.

### 9. Remove unnecessary server/internal wording from the app
Any user-visible text or field mentioning infrastructure that users do not need must be removed, including unnecessary:
- Server
- Server URL
- Host
- backend address
- technical connection settings

Keep server configuration internal and hidden from normal users.

### 10. Mobile-number field cleanup
Remove any real/personal phone-number example.
Do not place a personal number in placeholders or helper text.

Use only generic wording/examples, e.g.:
- `أدخل رقم الجوال بدون المفتاح`
- `Enter mobile number without country code`
- generic placeholders such as `05xxxxxxxx`

Country/calling code should be selected separately from the local number field when possible.

### 11. Privacy cleanup / no personal test data
Before shipping v0.32, remove or replace any user-visible personal/test/developer data, including:
- personal phone numbers
- personal email addresses
- test usernames
- test accounts
- admin credentials
- unnecessary Firebase/internal identifiers
- unnecessary server/internal information

Do not expose secrets, service-account keys, Firebase private keys, admin passwords, tokens, or private infrastructure configuration.

A privacy scan should be part of release validation.

## Additional agreed improvements
These are considered part of implementing the 11 requirements correctly:
- email verification for recovery/email changes
- one-time recovery code hashing, expiry and rate limiting
- notification channels and Android 13+ permission handling
- FCM token refresh, stale token cleanup and multi-device mapping
- Android audio focus and Bluetooth/speaker/earpiece routing
- message encryption payload versioning / key resynchronization strategy
- modern Android system-inset and edge-to-edge handling
- two-device real-world testing across foreground/background/locked-screen scenarios
- Wi-Fi <-> mobile-data call/message testing where practical

## Privacy note
Do not include any personal phone number or personal email from previous chats in source code, placeholders, screenshots, docs, logs, test fixtures, or release artifacts.

## Recommended next step in a new chat
Ask ChatGPT to:
1. read this file from GitHub
2. verify the live backend state
3. inspect the latest Android source branch
4. continue implementing v0.32 requirements systematically
5. keep this file updated as work is completed
