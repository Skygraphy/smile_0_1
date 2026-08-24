// Minimal HTTP wrapper around ffmpeg. Dispatched to by
// supabase/functions/complete-upload for every video upload, since Edge
// Functions can't run ffmpeg themselves. Nothing here ever sees the Supabase
// service-role key -- complete-upload does all the privileged DB writes
// itself (before dispatch: processing_status='processing'; after, via this
// service calling back into transcode-callback with only a scoped signed
// upload token and a shared callback secret).
"use strict";

const express = require("express");
const { spawn } = require("child_process");
const fs = require("fs/promises");
const os = require("os");
const path = require("path");
const { createClient } = require("@supabase/supabase-js");

const PORT = process.env.PORT || 8080;
const SERVICE_TOKEN = process.env.TRANSCODE_SERVICE_TOKEN || "";

const app = express();
app.use(express.json());

app.post("/transcode", (req, res) => {
  const authHeader = req.headers["authorization"] || "";
  if (!SERVICE_TOKEN || authHeader !== `Bearer ${SERVICE_TOKEN}`) {
    return res.status(401).json({ error: "unauthorized" });
  }

  const job = req.body;
  const required = ["media_item_id", "download_url", "display_path", "upload_token", "supabase_url", "supabase_anon_key", "callback_url", "callback_token"];
  const missing = required.filter((key) => !job || !job[key]);
  if (missing.length > 0) {
    return res.status(400).json({ error: "missing_fields", fields: missing });
  }

  // Accept immediately and do the actual work in the background -- a real
  // transcode can easily take longer than a typical request timeout (Cloud
  // Run defaults to 5 minutes), and the caller (complete-upload) isn't
  // waiting synchronously for this to finish anyway.
  res.status(202).json({ accepted: true });
  runJob(job).catch((err) => {
    console.error(`[${job.media_item_id}] unhandled job error:`, err);
  });
});

app.get("/health", (_req, res) => res.status(200).json({ ok: true }));

async function runJob(job) {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), "transcode-"));
  const outputPath = path.join(workDir, "display.mp4");

  try {
    await transcode(job.download_url, outputPath);
    await uploadResult(job, outputPath);
    await reportResult(job, { status: "ready", display_path: job.display_path });
    console.log(`[${job.media_item_id}] transcode complete`);
  } catch (err) {
    console.error(`[${job.media_item_id}] transcode failed:`, err);
    await reportResult(job, { status: "failed" }).catch((reportErr) => {
      console.error(`[${job.media_item_id}] failed to report failure:`, reportErr);
    });
  } finally {
    await fs.rm(workDir, { recursive: true, force: true }).catch(() => {});
  }
}

// Conservative, guaranteed-widely-playable profile (per the plan: this
// caps both device compatibility risk and, since the kiosk caches every
// video fully for offline viewing, local storage use). ffmpeg reads
// straight from the signed download URL -- no separate download step.
function transcode(inputUrl, outputPath) {
  return new Promise((resolve, reject) => {
    const args = [
      "-y",
      "-i", inputUrl,
      "-vf", "scale='min(1280,iw)':-2",
      "-c:v", "libx264",
      "-profile:v", "main",
      "-level", "4.0",
      "-preset", "veryfast",
      "-crf", "23",
      "-maxrate", "2M",
      "-bufsize", "4M",
      "-c:a", "aac",
      "-b:a", "128k",
      "-movflags", "+faststart",
      outputPath,
    ];
    const ffmpeg = spawn("ffmpeg", args);
    let stderr = "";
    ffmpeg.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    ffmpeg.on("error", reject);
    ffmpeg.on("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg exited with code ${code}: ${stderr.slice(-2000)}`));
    });
  });
}

async function uploadResult(job, outputPath) {
  const supabase = createClient(job.supabase_url, job.supabase_anon_key);
  const fileBuffer = await fs.readFile(outputPath);
  const { error } = await supabase.storage
    .from("media-display")
    .uploadToSignedUrl(job.display_path, job.upload_token, fileBuffer, { contentType: "video/mp4" });
  if (error) throw new Error(`upload failed: ${error.message}`);
}

async function reportResult(job, result) {
  const response = await fetch(job.callback_url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${job.supabase_anon_key}`,
      "X-Callback-Token": job.callback_token,
    },
    body: JSON.stringify({ media_item_id: job.media_item_id, ...result }),
  });
  if (!response.ok) {
    throw new Error(`callback responded ${response.status}: ${await response.text()}`);
  }
}

app.listen(PORT, () => {
  console.log(`transcode-service listening on ${PORT}`);
});
