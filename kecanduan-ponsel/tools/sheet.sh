#!/bin/bash
# tools/sheet.sh out.png img1 img2 ... -> horizontal contact sheet (each 360px wide)
out=$1; shift; args=(); f=""; i=0
for x in "$@"; do args+=(-i "$x"); f+="[$i]scale=360:-1[v$i];"; i=$((i+1)); done
s=""; for ((k=0;k<i;k++)); do s+="[v$k]"; done
ffmpeg -v error -y "${args[@]}" -filter_complex "${f}${s}hstack=$i" "$out"
