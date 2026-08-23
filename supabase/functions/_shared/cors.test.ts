import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { corsHeaders, jsonResponse } from "./cors.ts";

Deno.test("jsonResponse defaults to status 200 and includes CORS + JSON headers", async () => {
  const response = jsonResponse({ ok: true });
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("Content-Type"), "application/json");
  assertEquals(response.headers.get("Access-Control-Allow-Origin"), corsHeaders["Access-Control-Allow-Origin"]);
  assertEquals(await response.json(), { ok: true });
});

Deno.test("jsonResponse honors a custom status code", () => {
  const response = jsonResponse({ error: "not_found" }, 404);
  assertEquals(response.status, 404);
});
