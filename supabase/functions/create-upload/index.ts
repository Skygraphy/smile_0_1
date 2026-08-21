// Called by the sender app (authenticated Supabase Auth user) before an
// upload starts. Validates the sender is actually paired to every requested
// device, pre-creates the media_items + media_recipients rows, and returns a
// signed upload URL for the original file. Video transcoding to the
// media-display bucket happens asynchronously afterward (Phase 5).
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

interface CreateUploadRequest {
  media_type: "photo" | "video";
  mime_type: string;
  file_extension: string;
  file_size_bytes: number;
  device_ids: string[];
  width?: number;
  height?: number;
  duration_seconds?: number;
  caption?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader) return jsonResponse({ error: "missing_authorization" }, 401);

  // Client bound to the caller's own JWT so RLS applies for the read checks below.
  const supabaseAsUser = createClient(supabaseUrl, serviceRoleKey, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: userData, error: userError } = await supabaseAsUser.auth.getUser();
  if (userError || !userData.user) return jsonResponse({ error: "invalid_session" }, 401);
  const senderId = userData.user.id;

  let body: CreateUploadRequest;
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }
  if (!body.device_ids?.length) return jsonResponse({ error: "device_ids_required" }, 400);
  if (!body.media_type || !body.mime_type || !body.file_extension) {
    return jsonResponse({ error: "missing_fields" }, 400);
  }

  // Service-role client for writes, but only after we've verified pairing via RLS-scoped reads above.
  const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey);

  const { data: pairings, error: pairingError } = await supabaseAdmin
    .from("device_senders")
    .select("device_id, tenant_id")
    .eq("user_id", senderId)
    .in("device_id", body.device_ids);

  if (pairingError) return jsonResponse({ error: "pairing_check_failed" }, 500);

  const pairedDeviceIds = new Set((pairings ?? []).map((p) => p.device_id));
  const unpaired = body.device_ids.filter((id) => !pairedDeviceIds.has(id));
  if (unpaired.length > 0) {
    return jsonResponse({ error: "not_paired_to_devices", device_ids: unpaired }, 403);
  }

  const tenantId = pairings![0].tenant_id;
  if (pairings!.some((p) => p.tenant_id !== tenantId)) {
    return jsonResponse({ error: "devices_span_multiple_tenants" }, 400);
  }

  const { data: mediaItem, error: mediaError } = await supabaseAdmin
    .from("media_items")
    .insert({
      tenant_id: tenantId,
      sender_id: senderId,
      media_type: body.media_type,
      mime_type: body.mime_type,
      file_size_bytes: body.file_size_bytes,
      width: body.width ?? null,
      height: body.height ?? null,
      duration_seconds: body.duration_seconds ?? null,
      caption: body.caption ?? null,
      processing_status: "uploaded",
      storage_path_original: "",
    })
    .select()
    .single();

  if (mediaError || !mediaItem) return jsonResponse({ error: "media_item_creation_failed" }, 500);

  const storagePath = `${tenantId}/${mediaItem.id}/original.${body.file_extension}`;
  await supabaseAdmin
    .from("media_items")
    .update({ storage_path_original: storagePath })
    .eq("id", mediaItem.id);

  const recipientRows = body.device_ids.map((deviceId) => ({
    media_item_id: mediaItem.id,
    device_id: deviceId,
    tenant_id: tenantId,
  }));
  const { error: recipientsError } = await supabaseAdmin.from("media_recipients").insert(recipientRows);
  if (recipientsError) return jsonResponse({ error: "media_recipients_creation_failed" }, 500);

  const { data: uploadData, error: uploadError } = await supabaseAdmin.storage
    .from("media-originals")
    .createSignedUploadUrl(storagePath);

  if (uploadError || !uploadData) return jsonResponse({ error: "signed_upload_url_failed" }, 500);

  return jsonResponse({
    media_item_id: mediaItem.id,
    storage_path: storagePath,
    upload_url: uploadData.signedUrl,
    upload_token: uploadData.token,
  });
});
