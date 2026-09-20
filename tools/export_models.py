#!/usr/bin/env python3
"""
Export Real-ESRGAN (SRVGGNetCompact) weights to ONNX for on-device inference.

Runs in GitHub Actions (CPU-only). Produces small (~5 MB) fp32 ONNX models with
dynamic H/W so the Android app can tile frames of any size.

Models:
  realesr-general-x4v3      -> general purpose (real footage, 144p YouTube etc.)
  realesr-general-wdn-x4v3  -> general + strong denoise (very noisy/compressed sources)
  realesr-animevideov3      -> anime / cartoon video (very fast, 16 features)
"""
import os
import sys
import urllib.request
import torch
import torch.nn as nn
import torch.nn.functional as F

OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/models"
BASE = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/"

MODELS = {
    # name: (filename, num_feat, num_conv)
    "realesr-general-x4v3": ("realesr-general-x4v3.pth", 64, 32),
    "realesr-general-wdn-x4v3": ("realesr-general-wdn-x4v3.pth", 64, 32),
    "realesr-animevideov3": ("realesr-animevideov3.pth", 16, 16),
}


class SRVGGNetCompact(nn.Module):
    """Compact VGG-style super-resolution net (from BasicSR), self-contained copy."""

    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4, act_type="prelu"):
        super().__init__()
        self.num_in_ch = num_in_ch
        self.num_out_ch = num_out_ch
        self.num_feat = num_feat
        self.num_conv = num_conv
        self.upscale = upscale
        self.act_type = act_type

        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(self._act(num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(self._act(num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def _act(self, num_feat):
        if self.act_type == "relu":
            return nn.ReLU(inplace=True)
        if self.act_type == "prelu":
            return nn.PReLU(num_parameters=num_feat)
        return nn.LeakyReLU(negative_slope=0.1, inplace=True)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode="nearest")
        return out + base


# ----------------------------------------------------------------------------------
# Full-size RRDBNet (RealESRGAN_x4plus, 16.7M params). Much slower but visibly better on
# faces / fine texture. Too big to bundle → exported to a separate dir and attached to the
# GitHub Release; the app downloads it on demand.
# ----------------------------------------------------------------------------------
class ResidualDenseBlock(nn.Module):
    def __init__(self, nf=64, gc=32):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, gc, 3, 1, 1)
        self.conv2 = nn.Conv2d(nf + gc, gc, 3, 1, 1)
        self.conv3 = nn.Conv2d(nf + 2 * gc, gc, 3, 1, 1)
        self.conv4 = nn.Conv2d(nf + 3 * gc, gc, 3, 1, 1)
        self.conv5 = nn.Conv2d(nf + 4 * gc, nf, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=True)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        x5 = self.conv5(torch.cat((x, x1, x2, x3, x4), 1))
        return x5 * 0.2 + x


class RRDB(nn.Module):
    def __init__(self, nf, gc=32):
        super().__init__()
        self.rdb1 = ResidualDenseBlock(nf, gc)
        self.rdb2 = ResidualDenseBlock(nf, gc)
        self.rdb3 = ResidualDenseBlock(nf, gc)

    def forward(self, x):
        out = self.rdb3(self.rdb2(self.rdb1(x)))
        return out * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32):
        super().__init__()
        self.conv_first = nn.Conv2d(num_in_ch, num_feat, 3, 1, 1)
        self.body = nn.Sequential(*[RRDB(num_feat, num_grow_ch) for _ in range(num_block)])
        self.conv_body = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_hr = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_last = nn.Conv2d(num_feat, num_out_ch, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=True)

    def forward(self, x):
        feat = self.conv_first(x)
        body_feat = self.conv_body(self.body(feat))
        feat = feat + body_feat
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode="nearest")))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode="nearest")))
        return self.conv_last(self.lrelu(self.conv_hr(feat)))


BIG_MODELS = {
    # name: (url, num_block)
    "realesrgan-x4plus": ("https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth", 23),
}


def export_big(name, url, num_block, out_dir):
    os.makedirs("weights", exist_ok=True)
    pth = os.path.join("weights", os.path.basename(url))
    download(url, pth)
    state = torch.load(pth, map_location="cpu", weights_only=True)
    state = state.get("params_ema", state.get("params", state))
    model = RRDBNet(num_block=num_block)
    model.load_state_dict(state, strict=True)
    model.eval()
    out_path = os.path.join(out_dir, f"{name}.onnx")
    torch.onnx.export(model, torch.rand(1, 3, 64, 64), out_path, opset_version=17,
                      input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h4", 3: "w4"}},
                      do_constant_folding=True, dynamo=False)
    import onnxruntime as ort
    import numpy as np
    sess = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])
    y = sess.run(None, {"input": np.random.rand(1, 3, 48, 64).astype(np.float32)})[0]
    assert y.shape == (1, 3, 192, 256), y.shape
    print(f"  OK {name}: {y.shape}  ({os.path.getsize(out_path) / 1e6:.1f} MB)")


def download(url, dst):
    if os.path.exists(dst):
        return
    print(f"  downloading {url}")
    urllib.request.urlretrieve(url, dst)


def export(name, fname, num_feat, num_conv):
    os.makedirs("weights", exist_ok=True)
    pth = os.path.join("weights", fname)
    download(BASE + fname, pth)

    state = torch.load(pth, map_location="cpu", weights_only=True)
    if "params" in state:
        state = state["params"]
    elif "params_ema" in state:
        state = state["params_ema"]
    # Infer architecture from the checkpoint itself (robust to upstream naming changes)
    num_feat = state["body.0.weight"].shape[0]
    body_idx = sorted({int(k.split(".")[1]) for k in state if k.startswith("body.")})
    last = max(body_idx)
    num_conv = (last - 2) // 2          # body = conv,act, (conv,act)*num_conv, conv
    print(f"  arch: num_feat={num_feat} num_conv={num_conv}")
    model = SRVGGNetCompact(num_feat=num_feat, num_conv=num_conv, upscale=4, act_type="prelu")
    model.load_state_dict(state, strict=True)
    model.eval()

    dummy = torch.rand(1, 3, 64, 64)
    out_path = os.path.join(OUT_DIR, f"{name}.onnx")
    torch.onnx.export(
        model,
        dummy,
        out_path,
        opset_version=17,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h4", 3: "w4"}},
        do_constant_folding=True,
        dynamo=False,
    )

    # Simplify/optimize graph
    try:
        import onnx
        from onnxsim import simplify
        m = onnx.load(out_path)
        m_simp, ok = simplify(m)  # keep dynamic H/W
        if ok:
            onnx.save(m_simp, out_path)
            print("  simplified OK")
    except Exception as e:  # noqa: BLE001
        print(f"  onnxsim skipped: {e}")

    # Sanity check with onnxruntime at a different resolution (dynamic shape)
    import onnxruntime as ort
    import numpy as np
    sess = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])
    x = np.random.rand(1, 3, 96, 128).astype(np.float32)
    y = sess.run(None, {"input": x})[0]
    assert y.shape == (1, 3, 384, 512), y.shape
    size_mb = os.path.getsize(out_path) / 1e6
    print(f"  OK {name}: {y.shape}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for n, (f, nf, nc) in MODELS.items():
        print(f"[export] {n}")
        export(n, f, nf, nc)
    big_dir = sys.argv[2] if len(sys.argv) > 2 else "dist/models"
    os.makedirs(big_dir, exist_ok=True)
    for n, (u, nb) in BIG_MODELS.items():
        print(f"[export-big] {n}")
        export_big(n, u, nb, big_dir)
    print("done ->", OUT_DIR, big_dir)
