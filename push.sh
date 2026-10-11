#!/bin/sh
set -e
cd "$(dirname "$0")"

echo "=== STSMN: Staging changes ==="
git add Android/ README.md tests/ docs/ 2>/dev/null || git add -A

echo "=== STSMN: Creating commit ==="
git commit -m "STSMN v1.2.1: 3D real globe upgrade, smooth 60fps spin, horizon occlusion, interactive controls" || echo "No new changes to commit"

echo "=== STSMN: Pushing to GitHub (origin main) ==="
git push origin main

echo "=== SUCCESS: Pushed to GitHub! Check GitHub Actions tab for APK build ==="
