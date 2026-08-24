# Transcode service

External ffmpeg microservice for Phase 5b video transcoding. Supabase Edge
Functions can't run ffmpeg, so `complete-upload` dispatches every video
upload here instead of processing it inline.

## Not deployed yet

This is groundwork only -- no cloud account has been provisioned for it.
Until `TRANSCODE_SERVICE_URL` is set as a Supabase secret, `complete-upload`
leaves videos at `processing_status = 'uploaded'` exactly like before this
existed, so nothing breaks by this code existing undeployed.

Deploying needs a provider decision (Cloud Run needs a GCP project +
billing; Fly.io needs a Fly account + billing; a Render/Railway container is
also an option) -- that's a real new-infrastructure choice with cost
implications, so it's deliberately left for an explicit go-ahead rather than
picked here.

## What it does

1. Receives a job from `complete-upload` (`POST /transcode`, bearer-token
   protected): a signed download URL for the original, a signed upload
   token for the `media-display` destination, and a callback URL/token.
2. Runs `ffmpeg` straight against the signed download URL (no separate
   download step) to a conservative, widely-compatible profile: H.264 main
   profile capped at 1280px wide, CRF 23, ~2Mbps, AAC audio, faststart.
   Conservative on purpose -- the kiosk fully caches every video locally for
   offline viewing (see the plan's offline-cache section), so this also
   bounds tablet storage use, not just playback compatibility.
3. Uploads the result to the signed URL via `@supabase/supabase-js`
   (`uploadToSignedUrl`), then POSTs the outcome back to
   `transcode-callback`, which writes `storage_path_display` /
   `processing_status` on the `media_items` row.

Accepts the job with an immediate 202 and does the actual work in the
background, since a real transcode can run past typical request timeouts
(Cloud Run defaults to 5 minutes) and `complete-upload` isn't waiting
synchronously on it.

## Environment variables

- `TRANSCODE_SERVICE_TOKEN` -- shared secret; must match the Supabase
  project secret of the same name (`complete-upload` sends it as the
  `Authorization: Bearer` header on every dispatch).
- `PORT` -- defaults to 8080.

Everything else needed per-job (download URL, upload token, Supabase
project URL/anon key, callback URL/token) arrives in the request body from
`complete-upload` -- nothing else needs to be configured on this service.

## Deploying (once a provider is chosen)

Either target just needs the Dockerfile built and run with
`TRANSCODE_SERVICE_TOKEN` set, then these three Supabase secrets set to
match:

```
supabase secrets set --linked \
  TRANSCODE_SERVICE_URL=https://<deployed-url>/transcode \
  TRANSCODE_SERVICE_TOKEN=<same value the service has> \
  TRANSCODE_CALLBACK_TOKEN=<a separate random secret> \
  TRANSCODE_STORAGE_KEY=<the project's sb_publishable_... key>
```

**Cloud Run**: `gcloud run deploy smile-transcode --source . --allow-unauthenticated --set-env-vars TRANSCODE_SERVICE_TOKEN=...` (the service does its own bearer-token check, so `--allow-unauthenticated` at the Cloud Run layer is fine -- Cloud Run's own IAM auth would otherwise also need service-to-service credentials wired into `complete-upload`, which is unnecessary complexity here).

**Fly.io**: `fly launch` (accept the detected Dockerfile), `fly secrets set TRANSCODE_SERVICE_TOKEN=...`, `fly deploy`.

## Testing before hardware exists

Once deployed, exercise it directly without needing a real kiosk tablet:
insert a `media_items` row with `media_type='video'` and a real video
already sitting at its `storage_path_original`, then call `complete-upload`
for it (or just POST straight to this service's `/transcode` with a
manually-created signed download/upload URL pair) and watch
`processing_status` move `uploaded` -> `processing` -> `ready`/`failed` via
`supabase db query --linked`.
