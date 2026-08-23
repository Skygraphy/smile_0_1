// Mints and verifies short-lived JWTs for kiosk devices.
//
// Devices never get a normal Supabase Auth user account. Instead they hold a
// custom JWT (signed with the project's own JWT secret, so PostgREST/RLS
// accepts it exactly like a regular session token) carrying `device_id` and
// `tenant_id` claims. RLS policies check these via the `is_own_device()`
// helper defined in the core schema migration.
import { create, verify, getNumericDate, type Payload } from "https://deno.land/x/djwt@v3.0.2/mod.ts";

// Named DEVICE_JWT_SECRET (not SUPABASE_JWT_SECRET) because the Supabase CLI
// reserves every secret name starting with SUPABASE_ for its own auto-injected
// variables and silently refuses to set anything under that prefix. Value is
// the project's Legacy JWT Secret (Dashboard -> Project Settings -> API ->
// JWT Keys -> Legacy JWT Secret) -- still valid for verification even on
// projects migrated to the new JWT Signing Keys.
const JWT_SECRET = Deno.env.get("DEVICE_JWT_SECRET") ?? "";
// TODO(Phase 7 hardening): there is no refresh/rotation mechanism yet -- a
// device that goes this long without being re-provisioned just stops
// syncing silently (discovered during Phase 5a testing: a 12h TTL with no
// refresh call anywhere meant a kiosk left running overnight went dark).
// Until a proper rotation endpoint exists (e.g. the compliance loop
// refreshing well before expiry), use a long-lived credential instead of a
// short session-style one -- revocation for now is "change
// DEVICE_JWT_SECRET" (all devices) or re-provisioning a specific device.
const DEVICE_TOKEN_TTL_SECONDS = 60 * 60 * 24 * 365 * 5; // ~5 years.

let cachedKey: CryptoKey | null = null;

async function getKey(): Promise<CryptoKey> {
  if (cachedKey) return cachedKey;
  if (!JWT_SECRET) {
    throw new Error("DEVICE_JWT_SECRET is not set for this Edge Function.");
  }
  cachedKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(JWT_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
  return cachedKey;
}

export interface DeviceClaims {
  device_id: string;
  tenant_id: string;
}

export async function mintDeviceToken(claims: DeviceClaims): Promise<string> {
  const key = await getKey();
  const payload: Payload = {
    role: "authenticated",
    // PostgREST (unlike the Edge Functions gateway) rejects tokens missing
    // `sub`/`aud` with a 401 -- there's no real auth.users row for a device,
    // so `sub` is just the device's own id, which is fine since every RLS
    // policy for device-scoped tables checks the device_id/tenant_id claims
    // directly, never auth.uid().
    sub: claims.device_id,
    aud: "authenticated",
    iss: "supabase",
    device_id: claims.device_id,
    tenant_id: claims.tenant_id,
    iat: getNumericDate(0),
    exp: getNumericDate(DEVICE_TOKEN_TTL_SECONDS),
  };
  return await create({ alg: "HS256", typ: "JWT" }, payload, key);
}

export async function verifyDeviceToken(token: string): Promise<DeviceClaims> {
  const key = await getKey();
  const payload = await verify(token, key);
  if (!payload.device_id || !payload.tenant_id) {
    throw new Error("Token is missing device_id/tenant_id claims.");
  }
  return { device_id: String(payload.device_id), tenant_id: String(payload.tenant_id) };
}
