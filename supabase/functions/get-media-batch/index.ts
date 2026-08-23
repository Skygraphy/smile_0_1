// Called by a kiosk device (device JWT, not a user session) to fetch its
// current policy plus signed URLs for every media item assigned to it that
// has finished processing. This is the kiosk's sync endpoint, hit both on a
// fixed poll interval and on FCM wake.
//
// The device is assumed to have WiFi under normal operation, but playback
// itself must keep working with zero connectivity (e.g. the tablet gets
// carried out and presented elsewhere). So the kiosk app does NOT treat
// display_url as a stream-on-demand link: it downloads the bytes behind
// every returned URL into its own local persistent cache as soon as they
// appear here, and always renders from that local copy. This endpoint only
// needs to be reachable to learn about *new* items or policy changes;
// nothing already downloaded ever depends on the network again unless the
// admin issues a clear_media_cache remote command or the item is unassigned
// (hidden_at set, filtered out of this query already).
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";
import { verifyDeviceToken } from "../_shared/device-jwt.ts";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL") ?? "",
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
);

const SIGNED_URL_TTL_SECONDS = 60 * 60; // 1h

interface HeartbeatBody {
  battery_level?: number;
  is_charging?: boolean;
  network_type?: string;
  fcm_token?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "");
  if (!token) return jsonResponse({ error: "missing_device_token" }, 401);

  let claims;
  try {
    claims = await verifyDeviceToken(token);
  } catch {
    return jsonResponse({ error: "invalid_device_token" }, 401);
  }

  const body: HeartbeatBody = await req.json().catch(() => ({}));

  const { data: policy } = await supabase
    .from("device_policies")
    .select("*")
    .eq("device_id", claims.device_id)
    .maybeSingle();

  const { data: recipients, error: recipientsError } = await supabase
    .from("media_recipients")
    .select(
      "id, sort_order, media_items!inner(id, media_type, storage_path_display, storage_path_thumbnail, processing_status, duration_seconds, created_at)",
    )
    .eq("device_id", claims.device_id)
    .is("hidden_at", null)
    .eq("media_items.processing_status", "ready")
    .order("sort_order", { ascending: true });

  if (recipientsError) return jsonResponse({ error: "query_failed" }, 500);

  const items = [];

  for (const r of recipients ?? []) {
    const mediaItem = Array.isArray(r.media_items) ? r.media_items[0] : r.media_items;
    if (!mediaItem) continue;

    const { data: displayUrl } = await supabase.storage
      .from("media-display")
      .createSignedUrl(mediaItem.storage_path_display, SIGNED_URL_TTL_SECONDS);
    const { data: thumbUrl } = mediaItem.storage_path_thumbnail
      ? await supabase.storage
        .from("media-thumbnails")
        .createSignedUrl(mediaItem.storage_path_thumbnail, SIGNED_URL_TTL_SECONDS)
      : { data: null };

    items.push({
      media_recipient_id: r.id,
      media_item_id: mediaItem.id,
      media_type: mediaItem.media_type,
      duration_seconds: mediaItem.duration_seconds,
      sort_order: r.sort_order,
      display_url: displayUrl?.signedUrl ?? null,
      thumbnail_url: thumbUrl?.signedUrl ?? null,
    });
  }

  // delivered_at is intentionally NOT set here anymore -- this endpoint only
  // hands out metadata + signed URLs. The kiosk app itself PATCHes
  // media_recipients.delivered_at once it has actually downloaded and
  // verified the bytes locally (see SupabaseApi.markDelivered), since
  // "the device knows about it" and "the device has it cached offline" are
  // different things and only the latter is what delivered_at should mean.

  const deviceUpdate: Record<string, unknown> = {
    last_seen_at: new Date().toISOString(),
  };
  if (body.battery_level !== undefined) deviceUpdate.battery_level = body.battery_level;
  if (body.is_charging !== undefined) deviceUpdate.is_charging = body.is_charging;
  if (body.fcm_token) deviceUpdate.fcm_token = body.fcm_token;
  await supabase.from("devices").update(deviceUpdate).eq("id", claims.device_id);

  return jsonResponse({ policy, items });
});
