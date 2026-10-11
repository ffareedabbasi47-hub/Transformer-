#!/bin/sh
set -e
cd "$(dirname "$0")"
git add -A
git commit -m "STSMN: 100% Native Compose 3D Earth Globe Engine" || true
git push
echo "=== SUCCESS: Pushed to GitHub! Check GitHub Actions tab for APK build ==="
