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


def download(url, dst):
    if os.path.exists(dst):
        return
    print(f"  downloading {url}")
    urllib.request.urlretrieve(url, dst)


def export(name, fname, num_feat, num_conv):
    os.makedirs("weights", exist_ok=True)
    pth = os.path.join("weights", fname)
    download(BASE + fname, pth)

    model = SRVGGNetCompact(num_feat=num_feat, num_conv=num_conv, upscale=4, act_type="prelu")
    state = torch.load(pth, map_location="cpu", weights_only=True)
    if "params" in state:
        state = state["params"]
    elif "params_ema" in state:
        state = state["params_ema"]
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
        m_simp, ok = simplify(m, overwrite_input_shapes={"input": [1, 3, 64, 64]}, dynamic_input_shape=True)
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
    print("done ->", OUT_DIR)
