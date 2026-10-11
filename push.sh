#!/bin/sh
set -e
cd "$(dirname "$0")"
git add .
git commit -m "STSMN: 3D Earth Globe + 5ft Street Map + 360 Street View" || git commit -m "update" || true
git push
echo "=== SUCCESS: Pushed to GitHub! Check GitHub Actions tab for APK build ==="
