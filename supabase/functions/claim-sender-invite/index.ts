// Called by the sender app when a logged-in user redeems a sender-invite
// pairing code to join a group (tenant). Mirrors claim-device-provisioning's
// full-mesh default in the opposite direction: a newly joined sender gets
// device_senders rows for every device already in the group, so "send to
// everyone" works immediately without the admin manually re-pairing them.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

interface ClaimInviteRequest {
  code: string;
}

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
  const userId = userData.user.id;

  let body: ClaimInviteRequest;
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }
  if (!body.code) return jsonResponse({ error: "code_required" }, 400);

  const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey);

  const { data: pairingCode, error: codeError } = await supabaseAdmin
    .from("pairing_codes")
    .select("*")
    .eq("code", body.code)
    .eq("code_type", "sender_invite")
    .single();

  if (codeError || !pairingCode) return jsonResponse({ error: "invalid_code" }, 404);
  if (new Date(pairingCode.expires_at) < new Date()) return jsonResponse({ error: "code_expired" }, 410);
  if (pairingCode.use_count >= pairingCode.max_uses) return jsonResponse({ error: "code_already_used" }, 410);

  const tenantId = pairingCode.tenant_id as string;

  // Join the group as a sender. If they're already a member (e.g. an admin
  // being invited to a second group under the same account elsewhere, or
  // redeeming a stray duplicate code), leave their existing role alone
  // rather than downgrading it.
  const { data: existingMembership } = await supabaseAdmin
    .from("tenant_members")
    .select("id")
    .eq("tenant_id", tenantId)
    .eq("user_id", userId)
    .maybeSingle();

  if (!existingMembership) {
    const { error: memberError } = await supabaseAdmin
      .from("tenant_members")
      .insert({ tenant_id: tenantId, user_id: userId, role: "sender" });
    if (memberError) return jsonResponse({ error: "membership_creation_failed" }, 500);
  }

  const { data: existingDevices } = await supabaseAdmin
    .from("devices")
    .select("id")
    .eq("tenant_id", tenantId);

  if (existingDevices && existingDevices.length > 0) {
    await supabaseAdmin
      .from("device_senders")
      .upsert(
        existingDevices.map((d) => ({
          device_id: d.id,
          user_id: userId,
          tenant_id: tenantId,
        })),
        { onConflict: "device_id,user_id", ignoreDuplicates: true },
      );
  }

  await supabaseAdmin
    .from("pairing_codes")
    .update({ use_count: pairingCode.use_count + 1 })
    .eq("id", pairingCode.id);

  return jsonResponse({
    tenant_id: tenantId,
    device_ids: (existingDevices ?? []).map((d) => d.id),
  });
});
