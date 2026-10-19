#!/bin/bash
# Downloads the speech models used by stt.py into ./models (≈1 GB). They are NOT part of this repository;
# each model has its own license — read NOTICE before redistributing.
set -euo pipefail
cd "$(dirname "$0")"; mkdir -p models; cd models
R=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
for m in sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 sherpa-onnx-streaming-paraformer-bilingual-zh-en; do
  [ -d "$m" ] && { echo "have $m"; continue; }
  echo "downloading $m"; curl -fL --retry 3 -o "$m.tar.bz2" "$R/$m.tar.bz2"; tar xjf "$m.tar.bz2"; rm -f "$m.tar.bz2"
done
[ -f vad.onnx ] || curl -fL --retry 3 -o vad.onnx "$R/silero_vad.onnx"
echo "models ready in $(pwd)"
