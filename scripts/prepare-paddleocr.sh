#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-.}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
WORK="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/PhotoBarcode-PaddleOCR-source"
rm -rf "$WORK"
mkdir -p "$(dirname "$WORK")"

echo "[1/3] Downloading official PaddleOCR Android SDK source..."
git clone --depth 1 --filter=blob:none --sparse https://github.com/PaddlePaddle/PaddleOCR.git "$WORK"
git -C "$WORK" sparse-checkout set deploy/ppocr-android/ppocr-sdk
SDK_SOURCE="$WORK/deploy/ppocr-android/ppocr-sdk"
if [[ ! -d "$SDK_SOURCE/src/main" ]]; then
  echo "Official SDK source path not found: $SDK_SOURCE/src/main" >&2
  exit 1
fi
TARGET="$PROJECT_ROOT/ppocr-sdk"
rm -rf "$TARGET"
mkdir -p "$TARGET"
cp -R "$SDK_SOURCE/src" "$TARGET/"

# Keep this project independent of the official repository's version-catalog aliases.
cat > "$TARGET/build.gradle.kts" <<'GRADLE'
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("com.quickbirdstudios:opencv:4.5.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
GRADLE

# Query Hugging Face model repositories and download the named model assets wherever the
# repository stores them. This avoids assuming that files are always at repository root.
echo "[2/3] Downloading PP-OCRv5 Mobile ONNX model assets..."
python3 - "$TARGET/src/main/assets/models" <<'PY'
import json, os, pathlib, sys, urllib.request
base = pathlib.Path(sys.argv[1])
models = [
    ("PaddlePaddle/PP-OCRv5_mobile_det_onnx", "det", {"inference.onnx"}),
    ("PaddlePaddle/PP-OCRv5_mobile_rec_onnx", "rec", {"inference.onnx", "inference.yml"}),
]
for repo, subdir, wanted in models:
    api = f"https://huggingface.co/api/models/{repo}/tree/main?recursive=true"
    req = urllib.request.Request(api, headers={"User-Agent": "PhotoBarcodeMVP-setup/1.0"})
    with urllib.request.urlopen(req, timeout=60) as response:
        items = json.load(response)
    selected = [x for x in items if x.get("type") == "file" and pathlib.PurePosixPath(x.get("path", "")).name in wanted]
    for name in wanted:
        matches = [x for x in selected if pathlib.PurePosixPath(x["path"]).name == name]
        if not matches:
            raise SystemExit(f"ERROR: {repo} does not expose {name}; inspect the Hugging Face Files page.")
        # Prefer the shallowest matching path if a repository has duplicate exports.
        match = sorted(matches, key=lambda x: (x["path"].count("/"), x["path"]))[0]
        url = f"https://huggingface.co/{repo}/resolve/main/{match['path']}?download=true"
        dest = base / subdir / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {repo}/{match['path']} -> {dest}")
        request = urllib.request.Request(url, headers={"User-Agent": "PhotoBarcodeMVP-setup/1.0"})
        with urllib.request.urlopen(request, timeout=300) as source, open(dest, "wb") as target:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk: break
                target.write(chunk)
        if dest.stat().st_size < 1024:
            raise SystemExit(f"ERROR: downloaded file appears too small: {dest}")
PY

echo "[3/3] Verifying model assets..."
for file in \
  "$TARGET/src/main/assets/models/det/inference.onnx" \
  "$TARGET/src/main/assets/models/rec/inference.onnx" \
  "$TARGET/src/main/assets/models/rec/inference.yml"; do
  test -s "$file" || { echo "Missing model asset: $file" >&2; exit 1; }
  ls -lh "$file"
done

echo "PaddleOCR SDK and model assets prepared successfully."
