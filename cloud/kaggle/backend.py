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

# one full set of models per GPU → true dual-GPU parallelism
UPS = [{k: build(k, g) for k in W} for g in range(max(NGPU, 1))]
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


def infer_video(path: str, scale: int, model: str = "general", progress=gr.Progress()):
    """Frames are split across all GPUs and re-ordered before encoding. Duplicate frames reused."""
    scale = int(scale)
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)) * scale; h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) * scale
    raw = tempfile.mktemp(suffix=".mp4"); final = tempfile.mktemp(suffix=".mp4")
    enc = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    codec = "h264_nvenc" if "h264_nvenc" in enc else "libx264"
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
                           "-c:v", codec, "-preset", "p4" if codec == "h264_nvenc" else "fast", "-b:v", f"{int(w*h*fps*0.09/1000)}k", "-pix_fmt", "yuv420p", raw], stdin=subprocess.PIPE)
    frames, dup_of, prev = [], [], None
    while True:
        ok, fr = cap.read()
        if not ok: break
        small = cv2.resize(fr, (32, 32), interpolation=cv2.INTER_AREA).astype(np.int16)
        dup_of.append(len(frames) - 1 if (prev is not None and np.abs(small - prev).mean() < 1.2) else -1)
        frames.append(fr); prev = small
    cap.release()
    total = len(frames); results = [None] * total
    todo = [i for i in range(total) if dup_of[i] == -1]
    done = [0]

    def worker(g, idxs):
        up = UPS[g].get(model, UPS[g]["general"])
        for i in idxs:
            with LOCKS[g]:
                results[i], _ = up.enhance(frames[i], outscale=scale)
            done[0] += 1
            if done[0] % 10 == 0: progress(done[0] / max(len(todo), 1), desc=f"{done[0]}/{len(todo)} on {len(UPS)} GPU(s)")

    ths = [threading.Thread(target=worker, args=(g, todo[g::len(UPS)])) for g in range(len(UPS))]
    for t in ths: t.start()
    for t in ths: t.join()
    for i in range(total):
        if results[i] is None:
            j = dup_of[i]
            while results[j] is None and dup_of[j] >= 0: j = dup_of[j]
            results[i] = results[j]
        ff.stdin.write(results[i].tobytes())
    ff.stdin.close(); ff.wait()
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-i", path, "-c:v", "copy", "-c:a", "aac", "-map", "0:v:0", "-map", "1:a:0?", "-shortest", final])
    return final if r.returncode == 0 else raw

# ── 3) Gradio server (fn 0 = image, fn 1 = video; 3 args each) ─────────────────────
MODELS = list(W)
with gr.Blocks() as demo:
    gr.Markdown(f"# Video Upscaler AI — Kaggle backend ({NGPU}× {torch.cuda.get_device_name(0) if NGPU else 'CPU'})")
    with gr.Tab("Image"):
        ii = gr.Image(type="pil"); si = gr.Radio([2, 4, 8], value=4, type="value", label="scale"); mi = gr.Dropdown(MODELS, value="general", label="model"); oi = gr.Image(type="pil")
        gr.Button("Run").click(infer_image, [ii, si, mi], oi, api_name="image")
    with gr.Tab("Video"):
        iv = gr.Video(); sv = gr.Radio([2, 4], value=4, type="value", label="scale"); mv = gr.Dropdown(MODELS, value="general", label="model"); ov = gr.Video()
        gr.Button("Run").click(infer_video, [iv, sv, mv], ov, api_name="video")
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
