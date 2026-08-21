// Called once by a kiosk tablet right after Android Enterprise QR provisioning
// completes. Exchanges a one-time pairing code for a bound `devices` row plus
// a device JWT the kiosk app will use for all further Supabase calls.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders, jsonResponse } from "../_shared/cors.ts";
import { mintDeviceToken } from "../_shared/device-jwt.ts";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL") ?? "",
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
);

interface ClaimRequest {
  code: string;
  device_name?: string;
  android_id?: string;
  app_version?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  let body: ClaimRequest;
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }
  if (!body.code) return jsonResponse({ error: "code_required" }, 400);

  const { data: pairingCode, error: codeError } = await supabase
    .from("pairing_codes")
    .select("*")
    .eq("code", body.code)
    .eq("code_type", "device_provisioning")
    .single();

  if (codeError || !pairingCode) return jsonResponse({ error: "invalid_code" }, 404);
  if (new Date(pairingCode.expires_at) < new Date()) return jsonResponse({ error: "code_expired" }, 410);
  if (pairingCode.use_count >= pairingCode.max_uses) return jsonResponse({ error: "code_already_used" }, 410);

  let deviceId = pairingCode.device_id as string | null;

  if (!deviceId) {
    const { data: newDevice, error: insertError } = await supabase
      .from("devices")
      .insert({
        tenant_id: pairingCode.tenant_id,
        name: body.device_name ?? "Neues Tablet",
        android_id: body.android_id ?? null,
        current_app_version: body.app_version ?? null,
        enrollment_status: "provisioned",
        last_seen_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (insertError || !newDevice) return jsonResponse({ error: "device_creation_failed" }, 500);
    deviceId = newDevice.id;
  } else {
    const { error: updateError } = await supabase
      .from("devices")
      .update({
        android_id: body.android_id ?? null,
        current_app_version: body.app_version ?? null,
        enrollment_status: "provisioned",
        last_seen_at: new Date().toISOString(),
      })
      .eq("id", deviceId);
    if (updateError) return jsonResponse({ error: "device_update_failed" }, 500);
  }

  // Ensure a policy row exists (defaults from the table's own column defaults).
  const { data: existingPolicy } = await supabase
    .from("device_policies")
    .select("*")
    .eq("device_id", deviceId)
    .maybeSingle();

  let policy = existingPolicy;
  if (!policy) {
    const { data: newPolicy, error: policyError } = await supabase
      .from("device_policies")
      .insert({ device_id: deviceId })
      .select()
      .single();
    if (policyError || !newPolicy) return jsonResponse({ error: "policy_creation_failed" }, 500);
    policy = newPolicy;
  }

  await supabase
    .from("pairing_codes")
    .update({ use_count: pairingCode.use_count + 1 })
    .eq("id", pairingCode.id);

  const token = await mintDeviceToken({ device_id: deviceId, tenant_id: pairingCode.tenant_id });

  return jsonResponse({
    device_id: deviceId,
    tenant_id: pairingCode.tenant_id,
    device_token: token,
    policy,
  });
});
