// Called by the sender app right after it finishes uploading bytes to the
// signed URL from create-upload. For photos, this is currently a passthrough
// (display = original) so the thin end-to-end slice works without a real
// transcoding pipeline. Phase 5 replaces the photo passthrough with actual
// resizing (still pending -- videos are handled first below since they're
// the one that actually crashes without it).
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// External ffmpeg microservice (see transcode-service/) -- Edge Functions
// can't run ffmpeg themselves. Deliberately unset until that service is
// actually deployed somewhere (Phase 5b, pending a provider decision):
// videos then just stay "uploaded" instead of erroring, same graceful
// fallback the pre-5b code had.
const transcodeServiceUrl = Deno.env.get("TRANSCODE_SERVICE_URL");
const transcodeServiceToken = Deno.env.get("TRANSCODE_SERVICE_TOKEN");
const transcodeCallbackToken = Deno.env.get("TRANSCODE_CALLBACK_TOKEN");
// The project's publishable (anon) key -- safe to hand to an external
// service since it's meant to be public, unlike serviceRoleKey above.
// Stored as its own secret rather than trusting the CLI-injected
// SUPABASE_ANON_KEY, since that var may still hold the legacy anon JWT on a
// project migrated to the new key system, and the storage upload endpoint
// needs the new sb_publishable_... key (same one SupabaseConfig already
// uses in both client apps).
const transcodeStorageKey = Deno.env.get("TRANSCODE_STORAGE_KEY") ?? "";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader) return jsonResponse({ error: "missing_authorization" }, 401);

  const supabaseAsUser = createClient(supabaseUrl, serviceRoleKey, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: userData, error: userError } = await supabaseAsUser.auth.getUser();
  if (userError || !userData.user) return jsonResponse({ error: "invalid_session" }, 401);

  const { media_item_id } = await req.json().catch(() => ({}));
  if (!media_item_id) return jsonResponse({ error: "media_item_id_required" }, 400);

  const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey);

  const { data: mediaItem, error: fetchError } = await supabaseAdmin
    .from("media_items")
    .select("*")
    .eq("id", media_item_id)
    .eq("sender_id", userData.user.id)
    .single();

  if (fetchError || !mediaItem) return jsonResponse({ error: "media_item_not_found" }, 404);

  if (mediaItem.media_type === "photo") {
    // Passthrough copy into the bucket get-media-batch actually reads from.
    // Phase 5 replaces this with a real resize step; for now display = original bytes.
    const { error: copyError } = await supabaseAdmin.storage
      .from("media-originals")
      .copy(mediaItem.storage_path_original, mediaItem.storage_path_original, {
        destinationBucket: "media-display",
      });
    if (copyError) return jsonResponse({ error: "display_copy_failed", detail: copyError.message }, 500);

    const { data: updated, error: updateError } = await supabaseAdmin
      .from("media_items")
      .update({
        storage_path_display: mediaItem.storage_path_original,
        processing_status: "ready",
      })
      .eq("id", media_item_id)
      .select()
      .single();
    if (updateError) return jsonResponse({ error: "update_failed" }, 500);
    return jsonResponse({ media_item: updated });
  }

  // Videos: hand off to the external transcode service. Not configured yet
  // (no TRANSCODE_SERVICE_URL secret) -- stay "uploaded" gracefully rather
  // than erroring, same as before this was wired up.
  if (!transcodeServiceUrl || !transcodeServiceToken || !transcodeCallbackToken) {
    return jsonResponse({ media_item: mediaItem, note: "transcode_service_not_configured" });
  }

  const { data: downloadData, error: downloadError } = await supabaseAdmin.storage
    .from("media-originals")
    .createSignedUrl(mediaItem.storage_path_original, 60 * 60); // 1h -- plenty for a transcode job
  if (downloadError || !downloadData) return jsonResponse({ error: "download_url_failed" }, 500);

  const displayPath = mediaItem.storage_path_original.replace(/original\.[^./]+$/, "display.mp4");
  const { data: uploadData, error: uploadError } = await supabaseAdmin.storage
    .from("media-display")
    .createSignedUploadUrl(displayPath);
  if (uploadError || !uploadData) return jsonResponse({ error: "upload_url_failed" }, 500);

  await supabaseAdmin.from("media_items").update({ processing_status: "processing" }).eq("id", media_item_id);

  try {
    const dispatchResponse = await fetch(transcodeServiceUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${transcodeServiceToken}`,
      },
      body: JSON.stringify({
        media_item_id,
        download_url: downloadData.signedUrl,
        display_path: displayPath,
        upload_token: uploadData.token,
        supabase_url: supabaseUrl,
        supabase_anon_key: transcodeStorageKey,
        callback_url: `${supabaseUrl}/functions/v1/transcode-callback`,
        callback_token: transcodeCallbackToken,
      }),
    });
    if (!dispatchResponse.ok) {
      throw new Error(`transcode service responded ${dispatchResponse.status}`);
    }
  } catch (err) {
    // Dispatch itself failed (service unreachable, rejected the job, etc) --
    // mark it failed now rather than leaving it stuck at "processing"
    // forever with nothing ever going to complete it.
    await supabaseAdmin.from("media_items").update({ processing_status: "failed" }).eq("id", media_item_id);
    return jsonResponse({ error: "transcode_dispatch_failed", detail: String(err) }, 502);
  }

  return jsonResponse({ media_item: { ...mediaItem, processing_status: "processing" }, note: "transcoding_started" });
});
