# ZoolChat v0.27 cloud beta deployment

Deploy this directory as a Docker service. The container listens on `PORT` (default 8080) and exposes `/health` and `/ready`.

Required for core chat:
- `DATABASE_URL` - PostgreSQL connection string
- `FREE_AUTH_ENABLED=true`
- `FIREBASE_AUTH_ENABLED=false`
- `DEV_AUTH_ENABLED=false`
- `TRUST_PROXY=true`

Recommended for durable media (Cloudflare R2):
- `R2_ENDPOINT`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`
- `R2_BUCKET=zoolchat-media`

Optional features:
- `FIREBASE_SERVICE_ACCOUNT_JSON` for Android push notifications
- `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` for calls

The server runs all SQL migrations automatically on startup.
