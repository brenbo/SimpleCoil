#!/bin/sh
# Rebuilds the game's recorded sounds (docs/SOUNDS.md) from the free packs they came from.
#
#   tools/sounds/build-sounds.sh <unzipped packs dir> [java]
#
# <unzipped packs dir> holds the three zips from itch.io, unzipped:
#   snake1/  Snake's Authentic Gun Sounds        https://f8studios.itch.io/snakes-authentic-gun-sounds
#   snake2/  Snake's SECOND Authentic Gun Sounds https://f8studios.itch.io/snakes-second-authentic-gun-sounds-pack
#   void1/   VOiD1 Gaming Military Voice Pack     https://void1gaming.itch.io/military-voice-pack
# The gun sounds are free for any use (the author calls them public domain) and go in
# app/src/main/res/raw. The voice pack's license lets the app use them but not share the files,
# so they go in app/src/main/res-licensed/raw, which git ignores: a build without them falls back
# to the effects. Needs a JDK 11+ (Android Studio's jbr works).
set -e
packs="$1"; java="${2:-java}"
here="$(cd "$(dirname "$0")" && pwd)"; root="$here/../.."
out="$root/app/src/main/res/raw"; voice="$root/app/src/main/res-licensed/raw"
mkdir -p "$voice"
tmp="$(mktemp -d)"; javac="$(dirname "$java")/javac"; [ -x "$javac" ] || javac="javac"
"$javac" -d "$tmp" "$here/Snd.java"
snd() { "$java" -cp "$tmp" Snd "$@"; }

A="$packs/snake1/Snake's Authentic Gun Sounds"
B="$packs/snake2/Snake's SECOND Authentic Gun Sounds"
R="$A/Reloads, Cycling & More/WAV"
V="$packs/void1/Military Voice Pack"

# Effects from the gun packs. (The shots, reload and explosion are other recordings, added by hand:
# shootingshort, reload, sfx_bomb_exploded; this script leaves them alone.)
snd fx "$B/& More/Pistol/WAV/9mm Pistol Dry Fire.wav" "$out/empty.wav" 0.3
snd fx "$R/AR Bolt Release WAV.wav" "$out/spawn.wav" 0.5
snd fx "$R/AK Rack WAV.wav" "$out/spawn_2.wav" 0.8
snd fx "$R/AR Charging Handle WAV.wav" "$out/sfx_connected.wav" 0.8
snd fx "$B/& More/Mag Pack/WAV/AR PolyMag Pack.wav" "$out/sfx_last_mag.wav" 0.6
snd fx "$B/& More/Mag Pack/WAV/AR PolyMag Unpack.wav" "$out/sfx_power_up.wav" 0.5

# Radio callouts: the pack's closest line to each one (docs/SOUNDS.md)
while IFS='|' read -r name line; do
  [ -n "$name" ] && snd voice "$V/$line.wav" "$voice/$name.wav"
done <<'EOF'
voice_leaving_area|Fall Back
voice_back_in_area|Affirmative
voice_outside_zone|All in, now
voice_zone_closing|Stay Alert
voice_spawn_moving|Alright lets move out
voice_drop_inbound|Airdrop In-board
voice_power_up|Locked and Loaded
voice_killstreak|Enemy Down
voice_uav_online|Enemy Spotted
voice_last_mag|I need Ammo
voice_capturing|Hold this Position
voice_secured|Protect the Spot
voice_lost|Negative
voice_hostiles_capturing|Need Backup
voice_point_moved|Get in position
voice_flag_taken|Ambushed
voice_flag_captured|Lets go
voice_round_start|Go go go
voice_charge_set|Fire In The hole
voice_charge_defused|Roger That
voice_infected|I'm Hit
voice_loot_banked|Reached
voice_victory|Good Job
voice_defeat|Retreat
voice_longest_shot|Good Shot
EOF
rm -rf "$tmp"
