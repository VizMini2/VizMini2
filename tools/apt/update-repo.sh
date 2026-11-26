#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Adds .deb files to VizMini2's apt repository and signs it. The repository is a flat one ("deb URL ./"): every file
# (the .deb files, Packages, Packages.gz, Release, InRelease, Release.gpg and the public key) is an asset of the "apt"
# GitHub release, a pre-release that is never the latest one (created the first time). Older .deb files stay, so apt
# can still install an older version. Used by .github/workflows/apt.yml when a release is published.
#
# Usage: tools/apt/update-repo.sh file.deb...
# Needs gh (with write access), apt-ftparchive (apt-utils), python3 and the signing key in gpg's keyring (APT_KEY_ID
# picks one if there are several). APT_LOCAL_DIR=dir works on that folder instead of the release (to try it with
# `python3 -m http.server -d dir`).
set -euo pipefail
REPO="${VIZMINI2_REPO:-VizMini2/VizMini2}"
TAG=apt
[ $# -ge 1 ] || { echo "usage: tools/apt/update-repo.sh file.deb..." >&2; exit 1; }
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/new"
for d in "$@"; do cp "$d" "$W/new/"; done
KEY=(); [ -n "${APT_KEY_ID:-}" ] && KEY=(--local-user "$APT_KEY_ID")

# What the repository has now: its Packages index.
if [ -n "${APT_LOCAL_DIR:-}" ]; then
  mkdir -p "$APT_LOCAL_DIR"
  [ -f "$APT_LOCAL_DIR/Packages" ] && cp "$APT_LOCAL_DIR/Packages" "$W/old" || : > "$W/old"
else
  if ! gh release view "$TAG" -R "$REPO" > /dev/null 2>&1; then
    gh release create "$TAG" -R "$REPO" --target main --prerelease --latest=false --title "apt repository" \
      --notes "VizMini2's apt repository (see https://vizmini2.github.io/getting-started/desktop.html). Not a VizMini2 release: its files are updated by every release."
  fi
  gh release download "$TAG" -R "$REPO" -p Packages -D "$W" 2> /dev/null && mv "$W/Packages" "$W/old" || : > "$W/old"
fi

# The new packages' entries, then the old ones that they do not replace (same file name).
(cd "$W/new" && apt-ftparchive packages . > "$W/new.Packages")
mkdir -p "$W/index"
python3 - "$W/old" "$W/new.Packages" "$W/index/Packages" <<'EOF'
import sys
def stanzas(path):
    text = open(path, encoding="utf-8").read().strip()
    return [s.strip() for s in text.split("\n\n") if s.strip()]
def filename(s):
    return next((l.split(":", 1)[1].strip() for l in s.splitlines() if l.startswith("Filename:")), "")
old, new = stanzas(sys.argv[1]), stanzas(sys.argv[2])
replaced = {filename(s) for s in new}
with open(sys.argv[3], "w", encoding="utf-8") as f:
    f.write("\n\n".join([s for s in old if filename(s) not in replaced] + new) + "\n")
EOF
cd "$W/index"
gzip -9 -k -n Packages
apt-ftparchive -o APT::FTPArchive::Release::Origin=VizMini2 -o APT::FTPArchive::Release::Label=VizMini2 \
  -o APT::FTPArchive::Release::Suite=stable -o APT::FTPArchive::Release::Codename=vizmini2 \
  -o APT::FTPArchive::Release::Architectures=amd64 \
  -o "APT::FTPArchive::Release::Description=VizMini2, ROS 2 visualization and control" release . > "$W/Release"
mv "$W/Release" Release  # written outside, or it would list itself
gpg --batch --yes "${KEY[@]}" --clearsign -o InRelease Release
gpg --batch --yes "${KEY[@]}" --armor --detach-sign -o Release.gpg Release
if [ -n "${APT_KEY_ID:-}" ]; then gpg --export "$APT_KEY_ID"; else gpg --export; fi > vizmini2-archive-keyring.gpg
grep -c '^Package:' Packages | xargs echo "packages in the index:"

# The .deb files first, the signed index last, so that apt never sees an index without its files.
if [ -n "${APT_LOCAL_DIR:-}" ]; then
  cp "$W"/new/*.deb "$APT_LOCAL_DIR/"
  cp Packages Packages.gz Release Release.gpg vizmini2-archive-keyring.gpg InRelease "$APT_LOCAL_DIR/"
else
  gh release upload "$TAG" -R "$REPO" "$W"/new/*.deb --clobber
  gh release upload "$TAG" -R "$REPO" Packages Packages.gz Release Release.gpg vizmini2-archive-keyring.gpg --clobber
  gh release upload "$TAG" -R "$REPO" InRelease --clobber
fi
echo "Done: $(cd "$W/new" && ls *.deb | tr '\n' ' ')"
