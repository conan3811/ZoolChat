# ZoolChat v0.32 deployment handoff

This file intentionally contains no credentials, public service addresses, personal data, Firebase client identifiers, or secret values.

## Image
Build from repository root using:

`cloud/Dockerfile.v032`

The image builds directly from the readable `server/` source, installs only production dependencies in the runtime layer, runs as the non-root `node` user, and starts through `npm start`. The start script applies pending additive migrations before starting the HTTP service.

## Existing production variables that must remain present
Keep the current production values for the database, LiveKit, Firebase Admin, admin access, trust-proxy/CORS policy, storage and any existing operational limits. Do not copy secrets into Git.

## v0.32 email delivery variables
The production environment must provide:
- `MAIL_HOST`
- `MAIL_PORT`
- `MAIL_FROM`
- `MAIL_USER` when the provider requires authentication
- `MAIL_PASSWORD` when the provider requires authentication
- `MAIL_SECURE` when explicit port/TLS behavior is required
- `MAIL_REQUIRE_TLS` when explicit STARTTLS behavior is required

The server health payload exposes only the boolean `emailConfigured`; it never returns provider credentials.

## Release order
1. Pass the v0.32 source/CI gate.
2. Build/install the Firebase-enabled Android acceptance APK.
3. Pass two-real-phone call audio/routing and FCM foreground/background/locked-screen tests.
4. Configure the production SMTP provider without changing existing Firebase/LiveKit/database secrets.
5. Deploy the v0.32 image in a controlled rollout.
6. Confirm health/readiness and run login, recovery, message, notification, call and ZoolMeet smoke tests.
7. Roll back immediately to the previous production image if health/readiness or core smoke tests regress.

Do not treat a successful Docker build as proof of physical call audio or device push delivery.
