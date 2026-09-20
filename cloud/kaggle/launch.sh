#!/usr/bin/env bash
# One-command launcher: pushes backend.py to Kaggle as a private GPU (T4 x2) script kernel.
# The kernel registers itself with the Cloudflare Worker; the Android app picks it up automatically.
#
# Usage:
#   export KAGGLE_API_TOKEN=KGAT_...        # from kaggle.com/settings → API
#   export ADMIN_KEY=...                    # Worker admin key (cloud_admin_key.txt)
#   ./launch.sh [kaggle_username]
#
# Sessions last up to 12 h (Kaggle limit; 30 h/week free). Re-run to start a new session.
set -euo pipefail
cd "$(dirname "$0")"
: "${KAGGLE_API_TOKEN:?set KAGGLE_API_TOKEN}"
: "${ADMIN_KEY:?set ADMIN_KEY}"
USER_NAME="${1:-$(kaggle config view 2>/dev/null | grep -oP 'username: \K\S+' || true)}"
: "${USER_NAME:?pass your kaggle username as first arg}"
WORKER_URL="${WORKER_URL:-https://upscaler-cloud.cracknew37.workers.dev}"

TMP=$(mktemp -d)
sed -e "s|ADMIN_KEY = os.environ.get(\"ADMIN_KEY\", \"\")|ADMIN_KEY = os.environ.get(\"ADMIN_KEY\", \"$ADMIN_KEY\")|" \
    -e "s|\"WORKER_URL\", \"https://upscaler-cloud.cracknew37.workers.dev\"|\"WORKER_URL\", \"$WORKER_URL\"|" backend.py > "$TMP/backend.py"
cat > "$TMP/kernel-metadata.json" <<EOF
{ "id": "$USER_NAME/video-upscaler-gpu-backend", "title": "video-upscaler-gpu-backend",
  "code_file": "backend.py", "language": "python", "kernel_type": "script", "is_private": true,
  "enable_gpu": true, "enable_tpu": false, "enable_internet": true,
  "dataset_sources": [], "competition_sources": [], "kernel_sources": [], "model_sources": [] }
EOF
kaggle kernels push -p "$TMP"
echo "⏳ waiting for GPU backend to register (usually 2–4 min)…"
for i in $(seq 1 20); do
  sleep 20
  st=$(kaggle kernels status "$USER_NAME/video-upscaler-gpu-backend" 2>&1 | grep -oE 'Status\.[A-Z_]+' || true)
  gpu=$(curl -s -m 15 "$WORKER_URL/health" | grep -oE '"gpu":"[^"]*"' || true)
  echo "  [$i] $st  $gpu"
  case "$gpu" in *Kaggle*) echo "✅ Kaggle GPU is now the primary backend"; exit 0;; esac
  case "$st" in *ERROR*) echo "❌ kernel error — check: kaggle kernels output $USER_NAME/video-upscaler-gpu-backend -p /tmp/klog"; exit 1;; esac
done
echo "⚠️ still starting; check https://www.kaggle.com/code/$USER_NAME/video-upscaler-gpu-backend"
