# ZoolChat v0.30 — Runtime finalization

This document records the final runtime work for the private Android beta without claiming external configuration that has not been verified.

## Verified in the v0.30 APK

- Package: `com.zoolchat.app`.
- Public API base URL embedded in the APK: `https://p01--zoolchat--8q4zpgbqbm94.code.run`.
- LiveKit Android/WebRTC runtime is present.
- ZoolChat realtime WebSocket client is present.
- `RealtimeForegroundService` is present and uses a sticky foreground connection for private-beta background message/call signaling.
- Firebase Messaging runtime is present.
- `ZoolFirebaseMessagingService` handles call/message data pushes.
- Incoming-call notification code and Android CallStyle integration are present.
- v0.30 includes the newer calls/groups/meeting API surface.

## Important Firebase finding

The v0.30 APK contains the Firebase libraries but does **not** contain a configured `google_app_id`. In other words, Firebase support is implemented but the Android app has not been registered with a real Firebase project yet.

This is why the Firebase layer cannot be truthfully marked complete from source code alone.

## What already works without Firebase

After an authenticated bootstrap, ZoolChat starts `RealtimeForegroundService`. It maintains the realtime WebSocket and handles:

- `message.new`
- `call.invite`
- `call.ended`
- `call.declined`

This is the private-beta fallback while the service remains alive. Firebase remains the required reliability layer for cases where Android has killed the process and the app must be awakened by a high-priority push.

## External runtime values required

### Android client

A real Firebase Android app for package:

`com.zoolchat.app`

must provide `google-services.json`.

### Backend / Northflank

The service needs these secret runtime values:

- `FIREBASE_SERVICE_ACCOUNT_JSON`
- `DATABASE_URL`
- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`

Use `server/.env.northflank.example` as the non-secret reference.

## Safe validation gate

The workflow `.github/workflows/validate-v030-runtime.yml` expects two GitHub Actions secrets:

- `ZOOLCHAT_GOOGLE_SERVICES_JSON_B64`
- `ZOOLCHAT_FIREBASE_SERVICE_ACCOUNT_JSON_B64`

It validates that the Android Firebase client and Firebase Admin service account belong to the same project and that the Android package is exactly `com.zoolchat.app`. It then checks the public ZoolChat backend `/health` endpoint.

A strict run passes only when the backend reports the database, LiveKit, and Firebase as ready.

## Final two-phone acceptance test

Do not call the runtime finished until all of the following pass on two physical Android phones:

1. Account A and account B are signed in.
2. A can send a text message to B and receive delivered/read updates.
3. B receives an incoming voice call from A while ZoolChat is foregrounded.
4. B receives an incoming video call while ZoolChat is backgrounded.
5. B receives an incoming call after Android has reclaimed/killed the ZoolChat process; this verifies FCM wake-up delivery.
6. Answer, decline, remote hang-up, and missed-call paths work.
7. Repeat once with one phone on Wi-Fi and the other on cellular data.
8. Reboot one phone, open/sign in if required by the OS, then repeat the incoming-call test.

## Completion rule

- Code path present: yes.
- Northflank URL embedded in v0.30: yes.
- Realtime background service present: yes.
- LiveKit client present: yes.
- Firebase client code present: yes.
- Firebase project configuration verified: **not until real Firebase credentials are supplied to the runtime**.
- Two-physical-phone acceptance verified: **not until the physical-device test is run**.

Do not hard-code Firebase Admin private keys, LiveKit secrets, database passwords, or other credentials in the repository or APK.
