#!/bin/bash
set -e

REMOTE_HOST="root@ai.1o.nu"
REMOTE_PATH="/root/www/nova/scamkill-android/"
LOCAL_BUILD_DIR="$HOME/scamkill-android-build"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "=== ScamKill Android: Build & Deploy ==="
echo ""

# Step 1: Sync from remote
echo "[1/3] Syncing from $REMOTE_HOST..."
rsync -az --delete \
    --exclude='.gradle/' \
    --exclude='build/' \
    --exclude='app/build/' \
    "$REMOTE_HOST:$REMOTE_PATH" "$LOCAL_BUILD_DIR/"
echo "      Synced to $LOCAL_BUILD_DIR"

# Step 2: Build
echo "[2/3] Building debug APK..."
cd "$LOCAL_BUILD_DIR"
chmod +x gradlew
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

echo "      APK built: $APK_PATH"

# Step 3: Install to phone
echo "[3/3] Installing to device..."
adb install -r "$APK_PATH"

echo ""
echo "=== Done! ScamKill installed on device ==="
