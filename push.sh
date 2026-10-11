#!/bin/sh
set -e
cd "$(dirname "$0")"
git add .
git commit -m "feat: embed real 2d map inside 3d earth engine with floating orbit globe" || git commit -m "update" || true
git push
echo "=== SUCCESS: Pushed to GitHub! Check GitHub Actions tab for APK build ==="
