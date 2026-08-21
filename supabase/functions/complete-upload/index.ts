// Called by the sender app right after it finishes uploading bytes to the
// signed URL from create-upload. For photos, this is currently a passthrough
// (display = original) so the thin end-to-end slice works without a real
// transcoding pipeline. Phase 5 replaces the photo passthrough with actual
// resizing and adds real video transcoding here instead of leaving videos
// pending.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

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

  // Videos: leave as "uploaded" until Phase 5 wires up real transcoding.
  return jsonResponse({ media_item: mediaItem, note: "video_transcoding_not_yet_implemented" });
});
