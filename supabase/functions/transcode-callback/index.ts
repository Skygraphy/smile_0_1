// Called by the external transcode service (see transcode-service/) once it
// finishes (or fails) a video job dispatched from complete-upload. There's
// no logged-in user here -- the caller is a server, not the sender app --
// so this does NOT rely on the Authorization header for its real
// authorization the way every other function does. It still needs *some*
// valid bearer to pass the gateway's default JWT check (the project's
// public anon/publishable key, same value handed to the transcode service
// as supabase_anon_key), and does its actual authorization via the
// X-Callback-Token header against TRANSCODE_CALLBACK_TOKEN instead.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const callbackToken = Deno.env.get("TRANSCODE_CALLBACK_TOKEN") ?? "";

interface CallbackBody {
  media_item_id: string;
  status: "ready" | "failed";
  display_path?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  if (!callbackToken || req.headers.get("x-callback-token") !== callbackToken) {
    return jsonResponse({ error: "unauthorized" }, 401);
  }

  let body: CallbackBody;
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }
  if (!body.media_item_id || !body.status) return jsonResponse({ error: "missing_fields" }, 400);

  const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey);

  if (body.status === "ready") {
    if (!body.display_path) return jsonResponse({ error: "display_path_required" }, 400);
    const { error } = await supabaseAdmin
      .from("media_items")
      .update({ storage_path_display: body.display_path, processing_status: "ready" })
      .eq("id", body.media_item_id);
    if (error) return jsonResponse({ error: "update_failed", detail: error.message }, 500);
  } else {
    const { error } = await supabaseAdmin
      .from("media_items")
      .update({ processing_status: "failed" })
      .eq("id", body.media_item_id);
    if (error) return jsonResponse({ error: "update_failed", detail: error.message }, 500);
  }

  return jsonResponse({ ok: true });
});
