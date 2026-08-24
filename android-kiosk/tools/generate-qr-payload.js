#!/usr/bin/env node
// Builds the JSON payload for Android Enterprise QR provisioning of the
// kiosk app. There's no admin dashboard yet (Phase 6) to generate this from
// a button click, so this script is the stand-in for "Admin generiert
// QR-Provisioning-Code im Dashboard" until then.
//
// Usage:
//   node tools/generate-qr-payload.js <apk-download-url> <pairing-code> [wifi-ssid] [wifi-password]
//
// The APK must be reachable over plain HTTP(S) *before* the tablet has any
// app-level pairing -- e.g. a GitHub Release asset URL, or a signed URL from
// a public bucket. Render the printed JSON as an actual scannable QR code,
// e.g. with the `qrcode` npm package (no need to add it as a project
// dependency, npx fetches it on demand):
//   node tools/generate-qr-payload.js <url> <code> | npx qrcode -o provisioning-qr.png
// Then, on a freshly factory-reset tablet: tap the Welcome screen 6 times to
// unlock the QR scanner, and scan the generated image.
//
// SIGNATURE_CHECKSUM below is this machine's current *debug* keystore --
// it will only match a device-owner install if the APK offered at
// <apk-download-url> was built and signed with that same keystore. Recompute
// it (and update the constant here) any time the signing key changes, e.g.
// moving to a real release key before a production rollout:
//   keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android \
//     | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '='
const SIGNATURE_CHECKSUM = "0YAtrRUTWHNbmEi_Glc4th0Ph2ByVE0zU_mSRvIwzbs";
const PACKAGE_NAME = "com.smile.kiosk";
const ADMIN_COMPONENT = `${PACKAGE_NAME}/.KioskDeviceAdminReceiver`;
// Must match KioskDeviceAdminReceiver.EXTRA_PAIRING_CODE exactly.
const PAIRING_CODE_EXTRA_KEY = "com.smile.kiosk.PAIRING_CODE";

const [, , apkUrl, pairingCode, wifiSsid, wifiPassword] = process.argv;

if (!apkUrl || !pairingCode) {
  console.error("Usage: node generate-qr-payload.js <apk-download-url> <pairing-code> [wifi-ssid] [wifi-password]");
  process.exit(1);
}

const payload = {
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": ADMIN_COMPONENT,
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apkUrl,
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": SIGNATURE_CHECKSUM,
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    [PAIRING_CODE_EXTRA_KEY]: pairingCode,
  },
};

// Optional: lets the tablet reach the APK download URL and Supabase before
// anyone has manually joined it to WiFi. Omit for a tablet that's already
// on WiFi, or if testing over Ethernet/USB tethering instead.
if (wifiSsid) {
  payload["android.app.extra.PROVISIONING_WIFI_SSID"] = wifiSsid;
  if (wifiPassword) {
    payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifiPassword;
    payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = "WPA";
  }
}

console.log(JSON.stringify(payload, null, 2));
