#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# Every app icon and logo from one picture, tools/icons/logo_vizmini2.jpg (2048x2048, the dark tile with rounded white
# corners). Needs ImageMagick 6 or 7 with WebP. Usage: tools/make_icons.sh
#
# In the repository:
# - app/src/main/res/mipmap-*/ic_launcher_foreground.png: the Android adaptive icon (the tile, its corners filled with
#   the background colour, inside the 72 dp that launchers show; the colour below is ic_launcher_background);
# - app/src/main/res/drawable-nodpi/logo_vizmini2.webp: the logo of the first onboarding page;
# - desktopApp/icons/vizmini2.png: the desktop app (window, .deb, applications menu); vizmini2.ico: the same on Windows
#   (the .msi, the .exe and the Start menu);
# - docs/_static/favicon.png: the manual's favicon.
# Only in build/icons/, to upload by hand: the Google Play icon (512x512) and the GitHub social preview (1280x640).
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=tools/icons/logo_vizmini2.jpg
BG="#182733"  # The tile's colour at its edges; the same as res/values/ic_launcher_background.xml.
IM=$(command -v magick || command -v convert)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p build/icons

# The tile with transparent corners: the white connected to each corner, eroded a little so no white halo is left.
"$IM" "$SRC" -fuzz 25% -fill "#FF00FF" \
  -draw "color 0,0 floodfill" -draw "color 2047,0 floodfill" -draw "color 0,2047 floodfill" -draw "color 2047,2047 floodfill" \
  -fill white +opaque "#FF00FF" -fill black -opaque "#FF00FF" -morphology Erode Disk:3 -blur 0x1.5 "$T/mask.png"
"$IM" "$SRC" "$T/mask.png" -alpha off -compose CopyOpacity -composite "$T/tile.png"
# The tile as a full square: its corners in the background colour.
"$IM" -size 2048x2048 "xc:$BG" "$T/tile.png" -compose Over -composite "$T/square.png"

"$IM" "$T/tile.png" -filter Lanczos -resize 512x512 -strip desktopApp/icons/vizmini2.png
"$IM" desktopApp/icons/vizmini2.png -define icon:auto-resize=256,128,64,48,32,24,16 desktopApp/icons/vizmini2.ico
"$IM" "$T/tile.png" -filter Lanczos -resize 512x512 -strip -define webp:alpha-quality=100 -quality 90 \
  app/src/main/res/drawable-nodpi/logo_vizmini2.webp
"$IM" "$T/tile.png" -filter Lanczos -resize 64x64 -strip docs/_static/favicon.png

# Adaptive icon: 108 dp layers, of which launchers show the central 72 dp (a circle, squircle or rounded square). The
# square tile takes 70 dp so the words fit well inside the 66 dp safe zone; its soft edges blend into the background
# when the launcher moves the layers.
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  name=${d%%:*}; k=${d##*:}
  s=$(awk "BEGIN{print int(108*$k+0.5)}"); t=$(awk "BEGIN{print int(70*$k+0.5)}"); f=$(awk "BEGIN{print int(1.5*$k+0.5)}")
  "$IM" "$T/square.png" -filter Lanczos -resize "${t}x${t}" \
    \( -size "${t}x${t}" xc:black -fill white -draw "rectangle $((2*f)),$((2*f)) $((t-1-2*f)),$((t-1-2*f))" -blur "0x$f" \) \
    -alpha off -compose CopyOpacity -composite \
    -compose Over -background none -gravity center -extent "${s}x${s}" -strip "$T/fg.png"
  mkdir -p "app/src/main/res/mipmap-$name"
  "$IM" "$T/fg.png" "PNG32:app/src/main/res/mipmap-$name/ic_launcher_foreground.png"
done

# Google Play's icon (it rounds the corners itself) and GitHub's social preview (Settings > General > Social preview).
"$IM" "$T/square.png" -filter Lanczos -resize 512x512 -strip PNG32:build/icons/play_store_512.png
"$IM" -size 1280x640 "xc:$BG" \( "$T/tile.png" -filter Lanczos -resize 440x440 \) -gravity west -geometry +100+0 -composite \
  -font DejaVu-Sans-Bold -fill white -pointsize 92 -gravity northwest -annotate +600+190 "VizMini2" \
  -font DejaVu-Sans -fill "#B8C7D6" -pointsize 34 -annotate +604+320 "ROS 2 visualization and control" \
  -annotate +604+368 "on Android and on the desktop" -strip build/icons/github_social_preview.png
ls -la desktopApp/icons/vizmini2.png desktopApp/icons/vizmini2.ico app/src/main/res/drawable-nodpi/logo_vizmini2.webp docs/_static/favicon.png \
  app/src/main/res/mipmap-*/ic_launcher_foreground.png build/icons/
