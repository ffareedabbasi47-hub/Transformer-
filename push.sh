#!/bin/sh
set -e
cd "$(dirname "$0")"

echo "=== STSMN: Staging 3D Globe & UI changes ==="
git add -A

echo "=== STSMN: Committing changes ==="
git commit -m "STSMN v1.2.3: 100% offline 3D real globe with synchronized coordinates and compact UI" || echo "Working tree clean"

echo "=== STSMN: Pushing to GitHub (origin main with upstream) ==="
git push -u origin main

echo "=== SUCCESS: Pushed to GitHub! Check GitHub Actions tab for APK build ==="
