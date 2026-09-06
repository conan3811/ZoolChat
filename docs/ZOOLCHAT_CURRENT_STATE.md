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

## ZoolChat v0.32 — 13 primary requirements

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

### 12. Login-first start screen and automatic navigation after successful authentication
The first authentication screen shown to a signed-out user must be the Login screen.

Login screen order/behavior:
- show mobile-number field
- show password field
- show Login action
- show `Forgot password?` underneath the login controls
- below `Forgot password?`, show `Create new account` / `تسجيل حساب جديد`
- tapping `Create new account` opens the new-registration screen described in requirement 2

Successful login behavior:
- when the entered mobile number and password are valid, complete authentication/session persistence immediately
- automatically navigate directly to the main Messages/Chats screen
- do not require the user to press another button, reopen the app, go back, or manually refresh
- fix the current bug where correct login details may succeed but the app does not open the authenticated area automatically

Successful new-registration behavior:
- when registration data is valid and account creation succeeds, show a clear on-screen success message such as `تم تسجيل الحساب بنجاح`
- create/store the authenticated session immediately after successful registration, without requiring a separate manual login
- automatically navigate directly to the Messages/Chats screen after the success message
- do not leave the user stuck on the registration screen after the account has been created
- email verification may still be required before using email-based password recovery or activating a changed email, but it must not block the requested immediate first entry into the app unless the product requirement is explicitly changed later

Release acceptance test must verify both paths on a real device:
1. valid Login -> Messages/Chats opens automatically
2. valid new Registration -> success feedback -> Messages/Chats opens automatically

### 13. WhatsApp-style contact profile and group-member quick actions
Implement contact/profile access and group-member interactions inspired by the current WhatsApp Android interaction model.

#### A. Individual chat: tap name/avatar -> contact profile/info
While an individual conversation is open, tapping the person's name or profile image in the top app bar must open a dedicated contact/profile information screen.

The screen should include, subject to the other user's privacy settings:
- large profile photo
- display name
- username if configured
- mobile number only when product/privacy rules allow it
- About/bio text if implemented
- online / last-seen state only when allowed by privacy settings

Provide quick actions near the top:
- Message / return to chat
- Voice call
- Video call
- Search messages in this conversation

Provide a shared-content section similar to WhatsApp:
- `Media, links and documents`
- preview recent shared media
- open a full view separated/filterable as Photos/Videos, Links, and Documents

Provide chat controls/information:
- Notifications
- Mute notifications with useful durations such as 8 hours, 1 week, or Always
- Starred messages when supported
- Disappearing messages when supported
- Encryption/security information and verification entry when ZoolChat's encryption implementation supports a user-verifiable identity/security view
- groups in common when available and useful
- Block user
- Report user

Do not expose private email, mobile number, last-seen, profile photo, or other profile details contrary to that user's privacy settings.

#### B. Group chat: tap participant -> compact member bottom sheet
In a group conversation, tapping a participant's profile image/name/message sender identity should open a small modern bottom sheet/card rather than immediately leaving the group.

The compact member card should show:
- profile photo
- display name
- username or other safe identifying detail where appropriate
- relevant membership/admin badge

Quick actions should include:
- `Message privately` / `إرسال رسالة خاصة` -> open a direct 1:1 chat with that member
- `Voice call`
- `Video call`
- `View profile` / `عرض الملف الشخصي` -> open the full contact/profile screen described above

For a person not already saved/known as a contact, optionally show an appropriate Add/Save contact action if the Android/contact-permission design supports it.

If the current user is a group admin, the same member sheet may additionally show role-management actions only when authorized, such as:
- Make group admin / promote
- Dismiss as admin where applicable
- Remove from group

Normal members must never see or invoke admin-only actions.

Tapping the current user's own identity in a group should not offer meaningless actions such as messaging or calling themselves; show their own profile or relevant self/group options instead.

#### C. Group info page
Tapping the group name in the chat header should open a full Group Info screen inspired by WhatsApp's current layout, including:
- group icon and name
- description if supported
- shared Media / Links / Documents
- notifications / mute controls
- participant list with admin badges
- search participants
- group settings/actions based on user role
- disappearing-message/chat-privacy options when supported
- leave group
- report group where applicable

Release testing must confirm that individual profile information and group member actions obey privacy, group roles, and authorization rules and do not leak registration email/phone details that are not intended to be public.

## Additional agreed improvements
These are considered part of implementing the 13 requirements correctly:
- email verification for recovery/email changes
- one-time recovery code hashing, expiry and rate limiting
- notification channels and Android 13+ permission handling
- FCM token refresh, stale token cleanup and multi-device mapping
- Android audio focus and Bluetooth/speaker/earpiece routing
- message encryption payload versioning / key resynchronization strategy
- modern Android system-inset and edge-to-edge handling
- two-device real-world testing across foreground/background/locked-screen scenarios
- Wi-Fi <-> mobile-data call/message testing where practical
- authentication success must update the app navigation/session state immediately, without requiring restart or manual refresh
- profile/contact and group-member sheets must enforce server-side authorization as well as client-side UI visibility

## Privacy note
Do not include any personal phone number or personal email from previous chats in source code, placeholders, screenshots, docs, logs, test fixtures, or release artifacts.

## Recommended next step in a new chat
Ask ChatGPT to:
1. read this file from GitHub
2. verify the live backend state
3. inspect the latest Android source branch
4. continue implementing v0.32 requirements systematically
5. keep this file updated as work is completed


## v0.32 development checkpoint — 2026-09-06
- Development branch: `zoolchat-v0.32-development`; production branch and deployment configuration unchanged.
- Live `/health` rechecked: 0.31.0, database/LiveKit/Firebase/admin configured; this is not a two-phone call test.
- Actual newest successful Android build: Actions run 34020449403, commit `6e6790e221a757cd78fb3af06b70be7c882ce66a`. The v0.29 reference branch is older. The v0.30 build workflow reconstructs the base + overlays + Kotlin/build corrections; all seven archive SHA-256 checks passed locally.
- Readable sources restored under `android/` and `server/` on the development branch. Use these for v0.32, not the historical packed overlays. Live Dockerfile still uses its original 0.31 packages.
- First code batch: explicit Compose authentication state; registration success no longer waits for a legacy recovery-code dialog; first auth tab is Login; system safe drawing/IME insets; personal numeric placeholder and translation removed; versioned audible message channels; visible-chat notification suppression/clearing; local unread clearing independent of receipt privacy; server enforces private read receipts and monotonic read cursor; ciphertext prefix detection and one stale-peer-key refresh on AEAD failure.
- Validation: local backend TypeScript build PASS; source privacy scan PASS. Android assemblePublicDebug + lintPublicDebug and isolated PostgreSQL read-state integration tests PASS in Actions run 34032713007 at commit `1a1df362823ae6c50d3b1ec98e5462920d7e8500`. None of the 13 primary requirements is declared fully accepted yet.
- Firebase client configuration retrieved and project/package verified locally, without modifying it. It is excluded from Git. CI compile artifact is explicitly validation-only without Firebase and must NOT be delivered as a final APK.
- Remaining: complete phone-only login/new registration/email recovery and verification/profile changes; remaining notification lifecycle/grouping/token work; local cache key migration and encryption verification; call audio/routing and two-phone proof; ZoolMeet invitations; contact/group profile actions and permission tests; device insets/auth acceptance tests.
- Do not confuse successful source compilation with physical-device acceptance. No production deployment performed in this batch.

### Second batch in development (not yet accepted)
- Phone-only login UI; registration email/phone/password/confirmation, no username; compatible backend supports optional username and phone login.
- Account identity screen checks username/email availability, saves username, verifies email before activation.
- Additive migration 010 adds email verification and one-time code records. Codes are scrypt-hashed, expire after 15 minutes, allow at most five failed attempts, and have per-account resend cooldown plus IP limits. Reset revokes sessions/devices and closes live sockets. Existing emails are deliberately not auto-verified.
- SMTP delivery requires MAIL_HOST, MAIL_FROM and the provider's MAIL_PORT/MAIL_USER/MAIL_PASSWORD. No production environment changed and no real email sent. SMTP delivery remains a release blocker until configured and tested.
- Local cache alias pinned to prevent future username changes from invalidating cache; lifecycle-aware read marking added.
- Second batch PASS: Android build/lint, read-state tests, email-recovery integration tests in Actions run 34033027622. SMTP delivery and real-device auth remain untested.

### Third batch in development (pending CI/device validation)
- LiveKit 2.28.1 AudioSwitchHandler now owns audio focus/communication routing; explicit microphone publication success check; remote audio enabled at subscription; call key refreshed; foreground call service starts before publishing; cleanup survives Compose scope disposal. This is NOT proof of audible two-phone audio.
- Relation-authorized contact profile endpoint excludes email/phone and respects presence privacy. Contact header opens profile, group message sender opens member sheet; self actions excluded. Basic shared-content filters, common groups, mute durations, block/report added. Photo rendering, full media browsing, additional role actions and complete localization still require further work.
- ZoolMeet invitations by username/phone/verified email, list and recipient-bound acceptance, waiting without token, host notification and existing admit/deny integration. Denied participants cannot bypass with the join code. No real invite has been sent.
- Five notification channels; incoming ringing bounded to 60 seconds; missed-call and meeting notifications; stale FCM tokens revoked in batches. Full device lifecycle, duplicate push/realtime alerts, multi-device cleanup and privacy previews remain to verify/complete.
- New profile/invitation authorization integration tests added. Version label 0.32.0-dev, versionCode 32.
- All 13 requirements remain tracked. This development branch is not release-ready and has not replaced live 0.31.

### Fourth batch in development — continuation from the saved state
- FCM lifecycle hardened on Android: account-specific push payloads are not surfaced while signed out, token-refresh work no longer retries indefinitely while signed out, and the current token is re-registered during authenticated bootstrap.
- Message notifications now select the dedicated group-message channel for groups and active message notifications are grouped by conversation while retaining one stable notification per chat.
- Group Info now includes participant search, mute presets, group reporting, and role-safe member controls. Owners/admins no longer see client actions they cannot legally execute; owner leave is blocked in the UI instead of deliberately producing a server error.
- Fixed a functional group-message regression: the pending-send/edit path no longer requires a single `peerUserId` for group chats. Direct chats retain the current direct E2EE beta. Group chats use the authenticated member-only transport with local pending media still encrypted at rest; group E2EE itself is NOT implemented or claimed yet.
- Direct-chat E2EE/security UI is no longer shown for groups. Previously dead chat-menu actions were removed or routed to the existing contact/group info controls instead of appearing to work while doing nothing.
- Contact/profile and group-member identity cards were polished: safe username display, clearer profile layout, reporting limits, self identity label, and no private-message/voice/video actions against the current user's own identity.
- Presence privacy UI now synchronizes `showLastSeen` and `showOnline` with the server-side privacy policy instead of saving those choices only on-device. Profile-photo/about visibility selectors remain local-only and are still incomplete server-side.
- The settings version subtitle now uses the current Android build version instead of the stale v0.25 label.
- Validation checkpoint: Actions run 34034747376 at commit `5decef78f8d518200ad1abee81534f130adb3abd` passed backend, privacy scan, migrations, Android build and lint after the group transport fix. Later UI/privacy commits are undergoing their own v0.32 CI runs and must be confirmed before release acceptance.
- Commits in this continuation include `3753fb48d0c16a9b6242b713190e53b283e120d3`, `5eed337a3b1c97052089f2c7eb8b6d262a47cd8b`, `52fafed92a1cf66866edee263f94b6b3b67e8887`, `5decef78f8d518200ad1abee81534f130adb3abd`, `715668947c8d7f377893cb25bea9e7ffefb039a0`, `271b0ce8398f4ec293f8470dcf60fbb6e2de90cc`, and `8e1c22603048d504344628c2989caecca6f96f21`.
- Still not accepted/release-ready: audible call verification on two physical Android phones; real-device login and new-registration automatic-navigation tests; SMTP recovery delivery; full foreground/background/locked-screen FCM and call testing; profile photo upload/render plus server-side photo/about privacy; full group/shared media browsing; complete localization; group member role badge/admin actions inside the compact sheet; and any true group-E2EE protocol if group encryption is a release requirement.
- Production/live 0.31 deployment remains unchanged. Do not deploy v0.32 until CI is green and the physical-device acceptance blockers above are resolved.


## v0.32 final automated-development checkpoint — 2026-09-06
- Development source gate accepted at commit `8ab955823570129a0c88149119b58719aee7412e`, Actions run `34042048120`.
- The v0.32 backend source now identifies itself as `0.32.0-dev`. Production remains on v0.31 and was not replaced or redeployed during this work.
- Final automated gate PASS: live-backend smoke; privacy scan with zero violations; TypeScript build; migrations 001-012; Android `assemblePublicDebug`; Android `lintPublicDebug`; validation artifact upload.
- Authentication PASS in integration: registration establishes an authenticated session immediately; phone + password login; refresh-token rotation; logout revocation.
- Email recovery PASS end-to-end through an actual SMTP transport in CI (Mailpit): request -> delivered email -> 8-digit one-time code -> password reset -> login with the new password. Ownership verification, expiry, five-attempt limit, session/device revocation and one-time use also PASS. Production SMTP-provider delivery still requires the production deployment environment and is not represented by the CI provider.
- Privacy/data PASS: contact-profile privacy, shared-content membership authorization, FCM device-token revoke, no registration email/phone leakage through the contact-profile endpoint, and source privacy scan.
- ZoolMeet PASS in integration: invitation authorization, recipient acceptance, waiting state and host admit/deny authorization.
- Calling backend lifecycle PASS in integration: call create, participant authorization, accept, LiveKit token issuance, end, ended-call reaccept guard, call timeline synchronization and decline. This is not a substitute for hearing real audio on two physical Android phones.
- Android adaptive-system configuration is present: edge-to-edge, safe drawing/IME insets, adjustResize and predictive-back callback support. Physical layout acceptance across representative devices is still a device test.
- Notifications are implemented with separate Messages, Group messages, Incoming calls, Missed calls and ZoolMeet channels; signed-out pushes are suppressed; token refresh/re-registration and logout revocation are implemented. Actual FCM foreground/background/locked-screen delivery remains a physical-device acceptance test.
- A secure Firebase-enabled APK workflow exists at `.github/workflows/build-v032-firebase.yml`. It requires the Firebase Android client configuration through repository secret `ZOOLCHAT_GOOGLE_SERVICES_JSON_BASE64`, verifies package `com.zoolchat.app`, removes the source configuration before artifact upload, and never commits `google-services.json`. The Firebase client file is intentionally absent from Git.
- Direct-chat E2EE remains the supported encrypted-message scope. Group E2EE has NOT been implemented and must not be claimed as completed.
- Remaining release acceptance is external/physical rather than missing application logic: (1) build the Firebase-enabled APK after secure secret injection; (2) two-real-phone audible call/routing test; (3) FCM foreground/background/locked-screen test; (4) real-device Login -> Chats and Registration -> success -> Chats UI acceptance; (5) production SMTP provider test after v0.32 deployment; (6) controlled production deployment only after those acceptance checks.
- Automated implementation status is approximately 99%; release acceptance remains below 100% until the physical/external checks above pass.
