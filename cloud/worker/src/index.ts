/**
 * Video Upscaler AI — Cloud proxy (Cloudflare Worker, free plan)
 *
 * Why a proxy?
 *  • The app never talks to third-party GPU hosts directly → we can add/replace backends
 *    (HF Spaces, Kaggle, Colab tunnels) without shipping a new APK.
 *  • Health-checked failover across several free GPU backends.
 *  • R2 content-addressed cache: identical frames (very common in 144p web video) are never
 *    processed twice, for anyone.
 *  • Hides the optional per-backend tokens.
 *
 * Endpoints
 *  GET  /health                    → { ok, backends:[{host, up, latencyMs}] }
 *  POST /frame?scale=4             body: image/png|jpeg|webp → upscaled image/webp
 *  POST /video?scale=2|4           body: video/mp4 (≤ MAX_VIDEO_BYTES) → SSE progress, final {url}
 *  GET  /result/<key>              → cached output object from R2
 *  POST /backends (admin, X-Admin-Key) → set dynamic backend list (e.g. Kaggle/Colab tunnel URL)
 */
export interface Env {
  R2: R2Bucket;
  HF_SPACES: string;
  MAX_VIDEO_BYTES: string;
  ADMIN_KEY?: string;
}

/** args=3 → backend accepts (file, scale, model) like our Kaggle notebook; args=2 → (file, scale) like public HF spaces */
type Backend = { host: string; fnImage: number; fnVideo: number; kind: "gradio"; args: 2 | 3; prefix?: string };

const prefixCache = new Map<string, string>();
/** Gradio 5 serves the API under /gradio_api; Gradio 4 at root. Detect once per host. */
async function apiPrefix(b: Backend): Promise<string> {
  if (b.prefix !== undefined) return b.prefix;
  const c = prefixCache.get(b.host);
  if (c !== undefined) { b.prefix = c; return c; }
  let p = "";
  try {
    const r = await fetch(b.host + "/config", { signal: AbortSignal.timeout(8000) });
    const j: any = r.ok ? await r.json() : {};
    if (typeof j?.api_prefix === "string") p = j.api_prefix.replace(/\/$/, "");
  } catch {}
  prefixCache.set(b.host, p); b.prefix = p;
  return p;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type,X-Admin-Key,X-Scale",
};

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    try {
      if (url.pathname === "/" || url.pathname === "/health") return health(env);
      if (url.pathname === "/frame" && req.method === "POST") return frame(req, env, ctx);
      if (url.pathname === "/video" && req.method === "POST") return video(req, env, ctx);
      if (url.pathname.startsWith("/result/")) return result(url.pathname.slice(8), env);
      if (url.pathname === "/backends" && req.method === "POST") return setBackends(req, env);
      return json({ error: "not found" }, 404);
    } catch (e: any) {
      return json({ error: e?.message ?? String(e) }, 500);
    }
  },
};

// ───────────────────────── backends ─────────────────────────

async function backends(env: Env): Promise<Backend[]> {
  // dynamic list (set via /backends) takes precedence, then static var
  const dyn = await env.R2.get("_config/backends.json").then((o) => o?.text()).catch(() => null);
  const raw = dyn || env.HF_SPACES;
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const [host, fi, fv, na] = s.split("|");
      return { host: host.replace(/\/$/, ""), fnImage: +fi || 0, fnVideo: +fv || 3, kind: "gradio" as const, args: (na === "3" ? 3 : 2) as 2 | 3 };
    });
}

async function probe(b: Backend): Promise<{ host: string; up: boolean; latencyMs: number; queue?: number }> {
  const t = Date.now();
  try {
    const r = await fetch(b.host + (await apiPrefix(b)) + "/queue/status", { signal: AbortSignal.timeout(6000) });
    const j: any = r.ok ? await r.json().catch(() => ({})) : {};
    return { host: b.host, up: r.ok, latencyMs: Date.now() - t, queue: j?.queue_size };
  } catch {
    return { host: b.host, up: false, latencyMs: Date.now() - t };
  }
}

async function pickBackends(env: Env): Promise<Backend[]> {
  const list = await backends(env);
  const probes = await Promise.all(list.map(probe));
  return list.map((b, i) => ({ b, p: probes[i], i })).filter((x) => x.p.up)
    .sort((a, b) => Math.floor((a.p.queue ?? 0) / 3) - Math.floor((b.p.queue ?? 0) / 3) || a.i - b.i).map((x) => x.b);
}

async function pickBackend(env: Env): Promise<Backend> {
  const list = await backends(env);
  const probes = await Promise.all(list.map(probe));
  // Order in the list = priority (dedicated Kaggle first, shared HF last). Among "up" backends,
  // keep list order unless one is clearly congested (queue >= 3) — tunnel latency is irrelevant
  // next to GPU time, so we don't sort by it.
  const ranked = list
    .map((b, i) => ({ b, p: probes[i], i }))
    .filter((x) => x.p.up)
    .sort((a, b) => Math.floor((a.p.queue ?? 0) / 3) - Math.floor((b.p.queue ?? 0) / 3) || a.i - b.i);
  if (!ranked.length) throw new Error("no GPU backend available");
  return ranked[0].b;
}

async function health(env: Env) {
  const list = await backends(env);
  const probes = await Promise.all(list.map(probe));
  const primary = probes.find((p) => p.up)?.host ?? null;
  return json({ ok: probes.some((p) => p.up), primary, gpu: primary ? (primary.includes("hf.space") ? "HF ZeroGPU (A10G, shared)" : "Kaggle 2× T4 (dedicated)") : null, backends: probes, version: 2 });
}

async function setBackends(req: Request, env: Env) {
  if (!env.ADMIN_KEY || req.headers.get("X-Admin-Key") !== env.ADMIN_KEY) return json({ error: "unauthorized" }, 401);
  const body = await req.text();
  await env.R2.put("_config/backends.json", body);
  return json({ ok: true, backends: await backends(env) });
}

// ───────────────────────── gradio client ─────────────────────────

async function gradioUpload(b: Backend, blob: Blob, name: string): Promise<string> {
  const fd = new FormData();
  fd.append("files", blob, name);
  const r = await fetch(b.host + (await apiPrefix(b)) + "/upload", { method: "POST", body: fd, signal: AbortSignal.timeout(120_000) });
  if (!r.ok) throw new Error(`upload ${r.status}`);
  const arr: string[] = await r.json();
  return arr[0];
}

function fileData(b: Backend, path: string, name: string, mime: string, size: number) {
  return { path, url: `${b.host}${b.prefix ?? ""}/file=${path}`, orig_name: name, mime_type: mime, size, meta: { _type: "gradio.FileData" } };
}

/** Runs a Gradio fn, streaming queue events to `onEvent`, resolves with output data array. */
async function gradioRun(b: Backend, fnIndex: number, data: any[], onEvent?: (e: any) => void, timeoutMs = 600_000): Promise<any[]> {
  const session = crypto.randomUUID().replace(/-/g, "").slice(0, 11);
  const px = await apiPrefix(b);
  const j = await fetch(b.host + px + "/queue/join", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ data, fn_index: fnIndex, session_hash: session }),
    signal: AbortSignal.timeout(30_000),
  });
  if (!j.ok) throw new Error(`queue/join ${j.status}`);
  const s = await fetch(`${b.host}${px}/queue/data?session_hash=${session}`, { signal: AbortSignal.timeout(timeoutMs) });
  if (!s.ok || !s.body) throw new Error(`queue/data ${s.status}`);
  const reader = s.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n\n")) >= 0) {
      const chunk = buf.slice(0, idx); buf = buf.slice(idx + 2);
      const line = chunk.split("\n").find((l) => l.startsWith("data: "));
      if (!line) continue;
      const ev = JSON.parse(line.slice(6));
      onEvent?.(ev);
      if (ev.msg === "process_completed") {
        if (!ev.success) throw new Error("backend: " + (ev.output?.error ?? "failed"));
        return ev.output.data;
      }
    }
  }
  throw new Error("stream ended without result");
}

// ───────────────────────── /frame ─────────────────────────

async function sha256(buf: ArrayBuffer): Promise<string> {
  const h = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(h)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function frame(req: Request, env: Env, ctx: ExecutionContext) {
  const url = new URL(req.url);
  const scale = clampScale(url.searchParams.get("scale") ?? req.headers.get("X-Scale") ?? "4");
  const body = await req.arrayBuffer();
  if (body.byteLength < 100 || body.byteLength > 8_000_000) return json({ error: "bad image size" }, 400);
  const mime = req.headers.get("Content-Type") || "image/png";

  const key = `frame/${scale}/${url.searchParams.get("model") || "general"}/${await sha256(body)}.webp`;
  const cached = await env.R2.get(key);
  if (cached) return new Response(cached.body, { headers: { ...CORS, "Content-Type": "image/webp", "X-Cache": "HIT" } });

  const ext = mime.includes("jpeg") ? "jpg" : mime.includes("webp") ? "webp" : "png";
  const model = url.searchParams.get("model") || "general";
  const candidates = await pickBackends(env);
  if (!candidates.length) throw new Error("no GPU backend available");
  let lastErr: any = null;
  for (const b of candidates) {
    try {
      const path = await gradioUpload(b, new Blob([body], { type: mime }), `f.${ext}`);
      const args: any[] = [fileData(b, path, `f.${ext}`, mime, body.byteLength), scale];
      if (b.args === 3) args.push(model);
      const out = await gradioRun(b, b.fnImage, args, undefined, 180_000);
      const outUrl: string = out[0]?.url ?? `${b.host}${b.prefix ?? ""}/file=${out[0]?.path}`;
      const img = await fetch(outUrl, { signal: AbortSignal.timeout(60_000) });
      if (!img.ok) throw new Error(`fetch result ${img.status}`);
      const bytes = await img.arrayBuffer();
      ctx.waitUntil(env.R2.put(key, bytes, { httpMetadata: { contentType: "image/webp" } }));
      return new Response(bytes, { headers: { ...CORS, "Content-Type": img.headers.get("Content-Type") || "image/webp", "X-Cache": "MISS", "X-Backend": b.host } });
    } catch (e: any) {
      lastErr = e; // try next backend
    }
  }
  throw lastErr ?? new Error("all backends failed");
}

// ───────────────────────── /video ─────────────────────────

async function video(req: Request, env: Env, ctx: ExecutionContext) {
  const url = new URL(req.url);
  const scale = clampScale(url.searchParams.get("scale") ?? "2");
  const max = +env.MAX_VIDEO_BYTES || 60_000_000;
  const len = +(req.headers.get("Content-Length") || 0);
  if (len > max) return json({ error: `video too large (max ${max} bytes)` }, 413);
  const body = await req.arrayBuffer();
  if (body.byteLength > max) return json({ error: "video too large" }, 413);

  const key = `video/${scale}/${url.searchParams.get("model") || "general"}/${await sha256(body)}.mp4`;
  const { readable, writable } = new TransformStream();
  const w = writable.getWriter();
  const enc = new TextEncoder();
  const send = (o: any) => w.write(enc.encode(`data: ${JSON.stringify(o)}\n\n`)).catch(() => {});

  ctx.waitUntil((async () => {
    try {
      const cached = await env.R2.head(key);
      if (cached) { await send({ stage: "done", url: `/result/${key}`, cache: "HIT" }); return; }
      await send({ stage: "picking_backend" });
      const b = await pickBackend(env);
      await send({ stage: "uploading", backend: b.host });
      const path = await gradioUpload(b, new Blob([body], { type: "video/mp4" }), "in.mp4");
      await send({ stage: "queued" });
      const fd = fileData(b, path, "in.mp4", "video/mp4", body.byteLength);
      // Gradio 5 Video component wants { video: FileData, subtitles: null }; Gradio 4 wants FileData
      const vin: any = (b.prefix ?? "") ? { video: fd, subtitles: null } : fd;
      const vargs: any[] = [vin, scale];
      if (b.args === 3) vargs.push(url.searchParams.get("model") || "general");
      const out = await gradioRun(b, b.fnVideo, vargs, (ev) => {
        if (ev.msg === "estimation") send({ stage: "queued", rank: ev.rank, eta: ev.rank_eta });
        else if (ev.msg === "process_starts") send({ stage: "processing", eta: ev.eta });
        else if (ev.msg === "log") send({ stage: "processing", log: ev.log });
      }, 900_000);
      const outUrl: string = out[0]?.video?.url ?? out[0]?.url ?? `${b.host}${b.prefix ?? ""}/file=${out[0]?.video?.path ?? out[0]?.path}`;
      await send({ stage: "downloading" });
      const r = await fetch(outUrl, { signal: AbortSignal.timeout(300_000) });
      if (!r.ok || !r.body) throw new Error(`fetch result ${r.status}`);
      await env.R2.put(key, r.body, { httpMetadata: { contentType: "video/mp4" } });
      await send({ stage: "done", url: `/result/${key}`, cache: "MISS", backend: b.host });
    } catch (e: any) {
      await send({ stage: "error", error: e?.message ?? String(e) });
    } finally {
      await w.close().catch(() => {});
    }
  })());

  return new Response(readable, { headers: { ...CORS, "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
}

async function result(key: string, env: Env) {
  const o = await env.R2.get(key);
  if (!o) return json({ error: "not found" }, 404);
  return new Response(o.body, { headers: { ...CORS, "Content-Type": o.httpMetadata?.contentType || "application/octet-stream", "Content-Length": String(o.size) } });
}

// ───────────────────────── utils ─────────────────────────

function clampScale(s: string): number {
  const n = parseInt(s, 10);
  return n === 2 || n === 4 || n === 8 ? n : 4;
}

function json(o: any, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}
