#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}/ppocr-sdk/src/main/assets/models"
for f in "$ROOT/det/inference.onnx" "$ROOT/rec/inference.onnx" "$ROOT/rec/inference.yml"; do
  if [[ ! -s "$f" ]]; then echo "Missing or empty model asset: $f" >&2; exit 1; fi
done
echo "All required PaddleOCR model assets are present."
