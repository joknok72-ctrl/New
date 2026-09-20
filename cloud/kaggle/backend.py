# Video Upscaler AI — Kaggle GPU backend (dual T4)
# Runs as a Kaggle script kernel with GPU T4 x2 + internet. Registers itself with the
# Cloudflare Worker so the Android app uses it automatically; unregisters on exit.
import os, subprocess, glob, time, threading, tempfile, urllib.request, atexit

WORKER_URL = os.environ.get("WORKER_URL", "https://upscaler-cloud.cracknew37.workers.dev")
ADMIN_KEY = os.environ.get("ADMIN_KEY", "")
HF_FALLBACK = "https://nick088-real-esrgan-pytorch.hf.space|0|3|2"

# Kaggle secrets (if attached)
try:
    from kaggle_secrets import UserSecretsClient
    _s = UserSecretsClient()
    ADMIN_KEY = ADMIN_KEY or _s.get_secret("ADMIN_KEY")
    try: WORKER_URL = _s.get_secret("WORKER_URL") or WORKER_URL
    except Exception: pass
except Exception:
    pass


def sh(cmd):
    print("$", cmd, flush=True); return subprocess.run(cmd, shell=True)

# ── 1) deps ───────────────────────────────────────────────────────────────────────
sh("pip install -q 'gradio>=5.0' realesrgan basicsr facexlib gfpgan opencv-python-headless requests 2>&1 | tail -1")
for f in glob.glob("/usr/local/lib/python3*/dist-packages/basicsr/data/degradations.py") + glob.glob("/opt/conda/lib/python3*/site-packages/basicsr/data/degradations.py"):
    s = open(f).read().replace("from torchvision.transforms.functional_tensor import rgb_to_grayscale", "from torchvision.transforms.functional import rgb_to_grayscale")
    open(f, "w").write(s)

import torch, cv2, numpy as np, requests, gradio as gr
from PIL import Image
from basicsr.archs.rrdbnet_arch import RRDBNet
from realesrgan.archs.srvgg_arch import SRVGGNetCompact
from realesrgan import RealESRGANer

NGPU = torch.cuda.device_count()
print(f"GPUs: {NGPU} → {[torch.cuda.get_device_name(i) for i in range(NGPU)]}", flush=True)

# ── 2) weights ─────────────────────────────────────────────────────────────────────
os.makedirs("weights", exist_ok=True)
W = {
    "general": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth",
    "wdn": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-wdn-x4v3.pth",
    "anime": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-animevideov3.pth",
    "x4plus": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
}
for k, u in W.items():
    p = f"weights/{k}.pth"
    if not os.path.exists(p): urllib.request.urlretrieve(u, p); print("downloaded", k, flush=True)


def build(kind, gpu):
    if kind == "x4plus": net = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32, scale=4)
    elif kind == "anime": net = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4, act_type="prelu")
    else: net = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type="prelu")
    return RealESRGANer(scale=4, model_path=f"weights/{kind}.pth", model=net, tile=0, half=True, gpu_id=gpu)

def build_blend(gpu, dni=0.5):
    """realesr-general with denoise_strength (official Real-ESRGAN --dni). Softer, more natural."""
    net = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type="prelu")
    return RealESRGANer(scale=4, model_path=["weights/general.pth", "weights/wdn.pth"], dni_weight=[dni, 1 - dni],
                        model=net, tile=0, half=True, gpu_id=gpu)

# one full set of models per GPU → true dual-GPU parallelism
UPS = [{k: build(k, g) for k in W} for g in range(max(NGPU, 1))]
for g in range(len(UPS)):
    UPS[g]["natural"] = build_blend(g, 0.5)   # 50/50 general+denoise → far less "plastic"
LOCKS = [threading.Lock() for _ in UPS]
_rr = [0]


def acquire():
    i = _rr[0] % len(UPS); _rr[0] += 1
    return i, LOCKS[i]


def infer_image(img: Image.Image, scale: int, model: str = "general"):
    g, lock = acquire()
    bgr = cv2.cvtColor(np.array(img.convert("RGB")), cv2.COLOR_RGB2BGR)
    with lock:
        out, _ = UPS[g].get(model, UPS[g]["general"]).enhance(bgr, outscale=int(scale))
    return Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))


def infer_video(path: str, scale: int, model: str = "general", natural: float = 0.5, progress=gr.Progress()):
    """Temporally-coherent upscale.
    Fixes vs naive per-frame SR:
      • duplicate detection is *relative to noise floor* and only fires on true repeats (diff < 0.05 on 32x32),
        never on slow motion — so no stutter.
      • motion-compensated temporal blend (optical flow, Farneback) of the previous *output*
        into the current one → kills shimmer without ghosting.
      • "natural" 0..1: blend the AI result with a bicubic upscale of the source in low-detail
        regions and add back a little of the source's own grain, so faces/sky don't look plastic.
    Frames are split across all GPUs, results re-ordered.
    """
    scale = int(scale); natural = float(natural)
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    w0 = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); h0 = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    w = w0 * scale; h = h0 * scale
    raw = tempfile.mktemp(suffix=".mp4"); final = tempfile.mktemp(suffix=".mp4")
    enc = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    codec = "h264_nvenc" if "h264_nvenc" in enc else "libx264"
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
                           "-c:v", codec, "-preset", "p5" if codec == "h264_nvenc" else "medium", "-b:v", f"{int(w*h*fps*0.10/1000)}k", "-pix_fmt", "yuv420p", raw], stdin=subprocess.PIPE)
    frames, dup_of, prev = [], [], None
    while True:
        ok, fr = cap.read()
        if not ok: break
        small = cv2.resize(fr, (32, 32), interpolation=cv2.INTER_AREA).astype(np.int16)
        # true duplicate only: encoders produce *identical* repeats (diff ~0.0), real motion is >= ~0.1
        dup_of.append(len(frames) - 1 if (prev is not None and np.abs(small - prev).mean() < 0.05) else -1)
        frames.append(fr); prev = small
    cap.release()
    total = len(frames); results = [None] * total
    todo = [i for i in range(total) if dup_of[i] == -1]
    done = [0]

    # model routing for natural look: at natural>=0.35 swap the sharp "general" for the 50/50 blend
    eff_model = "natural" if (model in ("general", "natural") and natural >= 0.35) else ("general" if model == "natural" else model)
    # for tiny sources (<=240p) and high natural: pre-upscale x2 bicubic, then AI does only x2 of the work
    pre2 = natural >= 0.75 and h0 <= 240

    def worker(g, idxs):
        up = UPS[g].get(eff_model, UPS[g]["general"])
        for i in idxs:
            src_i = frames[i]
            if pre2: src_i = cv2.resize(src_i, (w0 * 2, h0 * 2), interpolation=cv2.INTER_CUBIC)
            with LOCKS[g]:
                out, _ = up.enhance(src_i, outscale=(scale / 2 if pre2 else scale))
            if out.shape[1] != w or out.shape[0] != h: out = cv2.resize(out, (w, h), interpolation=cv2.INTER_AREA)
            results[i] = out
            done[0] += 1
            if done[0] % 10 == 0: progress(0.8 * done[0] / max(len(todo), 1), desc=f"AI {done[0]}/{len(todo)} on {len(UPS)} GPU(s)")

    ths = [threading.Thread(target=worker, args=(g, todo[g::len(UPS)])) for g in range(len(UPS))]
    for t in ths: t.start()
    for t in ths: t.join()

    # ---- temporal pass (sequential, CPU) ------------------------------------------------------
    prev_out = None; prev_src_small = None
    for i in range(total):
        src = frames[i]
        if results[i] is None:
            j = dup_of[i]
            while results[j] is None and dup_of[j] >= 0: j = dup_of[j]
            results[i] = results[j]
        cur = results[i].astype(np.float32)

        if natural > 0:
            # (a) natural blend: bicubic upscale of the source as the "honest" baseline
            bic = cv2.resize(src, (w, h), interpolation=cv2.INTER_CUBIC).astype(np.float32)
            # detail mask from the *source* (where the source has real edges, trust the AI; where it is
            # smooth — sky, skin, walls — the AI is hallucinating, so lean towards bicubic)
            gsrc = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY).astype(np.float32)
            gx = cv2.Sobel(gsrc, cv2.CV_32F, 1, 0); gy = cv2.Sobel(gsrc, cv2.CV_32F, 0, 1)
            edge = cv2.GaussianBlur(np.sqrt(gx * gx + gy * gy), (0, 0), 1.5)
            edge = np.clip(edge / 40.0, 0, 1)
            edge = cv2.resize(edge, (w, h), interpolation=cv2.INTER_LINEAR)[..., None]
            # ai weight: 1 on edges, (1-natural*0.45) on flat regions
            wai = 1.0 - natural * 0.45 * (1.0 - edge)
            cur = cur * wai + bic * (1.0 - wai)
            # (b) give back a whisper of the source's own fine grain so it doesn't look airbrushed
            grain = (bic - cv2.GaussianBlur(bic, (0, 0), 1.2)) * (0.35 * natural)
            cur = cur + grain

        # (c) motion-compensated temporal smoothing against previous OUTPUT
        if prev_out is not None:
            g0 = cv2.cvtColor(prev_src_small, cv2.COLOR_BGR2GRAY); g1 = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
            flow = cv2.calcOpticalFlowFarneback(g0, g1, None, 0.5, 3, 15, 3, 5, 1.2, 0)
            # upscale flow to output size
            flow_up = cv2.resize(flow, (w, h), interpolation=cv2.INTER_LINEAR) * scale
            ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
            mapx = xs - flow_up[..., 0]; mapy = ys - flow_up[..., 1]
            warped = cv2.remap(prev_out, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
            # confidence: where warped prev matches current (low error) blend strongly; where it doesn't (occlusion/cut) don't
            err = np.abs(warped - cur).mean(axis=2, keepdims=True)
            alpha = np.clip(1.0 - err / 18.0, 0.0, 1.0) * 0.55  # max 55 % history
            if err.mean() > 25: alpha[:] = 0  # scene cut
            cur = cur * (1 - alpha) + warped * alpha

        out8 = np.clip(cur, 0, 255).astype(np.uint8)
        ff.stdin.write(out8.tobytes())
        prev_out = cur; prev_src_small = src
        if i % 10 == 0: progress(0.8 + 0.2 * i / max(total, 1), desc=f"temporal {i}/{total}")
    ff.stdin.close(); ff.wait()
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-i", path, "-c:v", "copy", "-c:a", "aac", "-map", "0:v:0", "-map", "1:a:0?", "-shortest", final])
    return final if r.returncode == 0 else raw

# ── 3) Gradio server (fn 0 = image, fn 1 = video; 3 args each) ─────────────────────
MODELS = list(W) + ["natural"]
with gr.Blocks() as demo:
    gr.Markdown(f"# Video Upscaler AI — Kaggle backend ({NGPU}× {torch.cuda.get_device_name(0) if NGPU else 'CPU'})")
    with gr.Tab("Image"):
        ii = gr.Image(type="pil"); si = gr.Radio([2, 4, 8], value=4, type="value", label="scale"); mi = gr.Dropdown(MODELS, value="general", label="model"); oi = gr.Image(type="pil")
        gr.Button("Run").click(infer_image, [ii, si, mi], oi, api_name="image")
    with gr.Tab("Video"):
        iv = gr.Video(); sv = gr.Radio([2, 4], value=4, type="value", label="scale"); mv = gr.Dropdown(MODELS, value="general", label="model")
        nv = gr.Slider(0, 1, value=0.5, step=0.05, label="natural (0 = raw AI, 1 = most natural)"); ov = gr.Video()
        gr.Button("Run").click(infer_video, [iv, sv, mv, nv], ov, api_name="video")
demo.queue(max_size=40, default_concurrency_limit=len(UPS) * 2)
app, local_url, share_url = demo.launch(share=True, prevent_thread_lock=True, show_error=True)
print("PUBLIC URL:", share_url, flush=True)

# ── 4) register with the Worker ───────────────────────────────────────────────────
host = (share_url or "").rstrip("/")


def register():
    if not ADMIN_KEY or not host: print("⚠️ no ADMIN_KEY/share url — not registering", flush=True); return
    r = requests.post(WORKER_URL + "/backends", data=f"{host}|0|1|3,{HF_FALLBACK}", headers={"X-Admin-Key": ADMIN_KEY}, timeout=30)
    print("✅ REGISTERED" if r.ok else "❌ register failed", r.status_code, r.text[:200], flush=True)


def unregister():
    try: requests.post(WORKER_URL + "/backends", data=HF_FALLBACK, headers={"X-Admin-Key": ADMIN_KEY}, timeout=15); print("unregistered", flush=True)
    except Exception as e: print(e)

register(); atexit.register(unregister)

# ── 5) serve until Kaggle stops us (max session ~12h; stop at 11h40 to exit cleanly) ──
t_end = time.time() + 11 * 3600 + 40 * 60
while time.time() < t_end:
    time.sleep(300)
    try: requests.get(host + "/queue/status", timeout=10); register()
    except Exception as e: print("ping", e, flush=True)
unregister()
