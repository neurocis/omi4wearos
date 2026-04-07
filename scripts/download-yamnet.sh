#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/wear/src/main/assets"
MODEL_FILE="$ASSETS_DIR/yamnet.tflite"
MODEL_URL="https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1?lite-format=tflite"
EXPECTED_SIZE_MIN=3000000  # ~3MB minimum

echo "========================================"
echo "  YAMNet TFLite Model Downloader"
echo "  for omi4wOS"
echo "========================================"
echo ""

mkdir -p "$ASSETS_DIR"

if [ -f "$MODEL_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$MODEL_FILE" 2>/dev/null || stat --printf="%s" "$MODEL_FILE" 2>/dev/null)
    if [ "$FILE_SIZE" -gt "$EXPECTED_SIZE_MIN" ]; then
        echo "✓ YAMNet model already exists ($(du -h "$MODEL_FILE" | cut -f1))"
        echo "  Use --force to re-download"
        if [ "$1" != "--force" ]; then
            exit 0
        fi
    fi
fi

echo "Downloading YAMNet TFLite model..."
echo "Source: TensorFlow Hub"
echo "Destination: $MODEL_FILE"
echo ""

curl -L -o "$MODEL_FILE" "$MODEL_URL"

FILE_SIZE=$(stat -f%z "$MODEL_FILE" 2>/dev/null || stat --printf="%s" "$MODEL_FILE" 2>/dev/null)
echo ""
echo "✓ Download complete!"
echo "  File: $MODEL_FILE"
echo "  Size: $(du -h "$MODEL_FILE" | cut -f1)"

if [ "$FILE_SIZE" -lt "$EXPECTED_SIZE_MIN" ]; then
    echo ""
    echo "⚠ WARNING: File seems too small (expected ~3.7MB)."
    echo "  The download may have failed. Check the file manually."
    exit 1
fi

echo ""
echo "✓ Model verified! Ready to build."
