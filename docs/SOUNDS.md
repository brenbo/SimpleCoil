# Simple Coil sounds

Every game sound plays through `SoundCues` (app/src/main/java/com/simplecoil/simplecoil/hardware/audio/SoundCues.kt). Each event has its own effect file and, for the important ones, a radio callout. **To add a sound, put the file in `app/src/main/res/raw/` under the name below** (OGG or WAV); the app picks it up on the next build. Until a file is there, the event plays one of the shipped GameBurp effects at a different pitch (and sometimes twice), so it still sounds distinct.

## Variety

Effects never play quite the same twice: each play moves the pitch up to 6% either way and the volume a little. For real variety, add more takes of a sound with `_2`, `_3`, ... up to `_6` after its name (for example `shootingshort_2.ogg`, `shootingshort_3.ogg`): one is picked at random, never the same twice running. The end-of-game whistle (`game_end`) is never varied.

## Quality rules (map design: Audio)

- Recorded voice, not text-to-speech: one calm military radio operator, clipped lines, NATO alphabet, a narrow radio treatment with a soft squelch click at each end.
- Effects from the GameBurp library (or one that sounds like it), mixed for phone speakers (mids and tops, no deep bass), all normalised to the same loudness.
- Effects under about 1 s, voice lines under about 2 s.

## Effects

| File | When |
|---|---|
| `shootingshort` | a shot (add takes) |
| `hit`, `hit_confirm`, `hit_eliminated`, `hit_shielded` | being hit; your hit landing, eliminating, on a shield |
| `eliminated`, `spawn`, `reload`, `empty`, `beep`, `score` | as today |
| `game_end` | the whistle at the end of a game (not varied) |
| `sfx_out_of_bounds`, `sfx_back_in_bounds` | leaving the field; back on it |
| `sfx_damage_tick` | health lost outside the border or the zone |
| `sfx_zone_warning`, `sfx_zone_closing` | outside the Battle Royale zone; the zone starting to close |
| `sfx_spawn_moving` | spawns moving |
| `sfx_drop_incoming`, `sfx_power_up` | a drop announced; a drop or power-up claimed |
| `sfx_killstreak`, `sfx_uav` | a killstreak reward; a UAV coming online |
| `sfx_last_mag` | limited ammo, the last magazine in |
| `sfx_capturing`, `sfx_secured`, `sfx_lost`, `sfx_enemy_capturing`, `sfx_point_moved` | capture points |
| `sfx_flag_taken`, `sfx_flag_captured` | Capture the Flag |
| `sfx_round_start`, `sfx_bomb_planted`, `sfx_bomb_defused` | Search & Destroy |
| `sfx_infected` | Infection: you were infected |
| `sfx_loot`, `sfx_loot_banked` | Extraction |
| `sfx_victory`, `sfx_defeat` | an objective game won or lost |
| `sfx_longest_shot` | the Longest Shot award after a game |

## Radio callouts

Played once the file exists, one at a time by priority (higher first), the same line no more than every 8 seconds, with effects ducked under them. Players can turn callouts off, or to essential only (marked ✓), in Sound settings.

| File | Line | Priority | Essential |
|---|---|---|---|
| `voice_leaving_area` | "You are leaving the area. Turn back." | 9 | ✓ |
| `voice_back_in_area` | "Back in the area." | 3 | |
| `voice_outside_zone` | "Outside the zone. Move in." | 9 | ✓ |
| `voice_zone_closing` | "Zone closing." | 8 | ✓ |
| `voice_spawn_moving` | "Spawn relocating." | 5 | |
| `voice_drop_inbound` | "Supply drop inbound." | 6 | |
| `voice_power_up` | "Package secured." | 4 | |
| `voice_killstreak` | "Killstreak." | 4 | |
| `voice_uav_online` | "UAV online." | 5 | |
| `voice_last_mag` | "Last mag." | 3 | |
| `voice_capturing` | "Capturing the point." | 4 | |
| `voice_secured` | "Point secured." | 7 | ✓ |
| `voice_lost` | "Point lost." | 7 | ✓ |
| `voice_hostiles_capturing` | "Hostiles taking our point." | 8 | ✓ |
| `voice_point_moved` | "Hardpoint moving." | 6 | ✓ |
| `voice_flag_taken` | "Our flag is taken." | 8 | ✓ |
| `voice_flag_captured` | "Flag captured." | 7 | ✓ |
| `voice_round_start` | "Round start." | 6 | ✓ |
| `voice_charge_set` | "Charge is set." | 9 | ✓ |
| `voice_charge_defused` | "Charge defused." | 9 | ✓ |
| `voice_infected` | "You are infected." | 8 | ✓ |
| `voice_loot_banked` | "Loot extracted." | 5 | |
| `voice_victory` | "Mission accomplished." | 10 | ✓ |
| `voice_defeat` | "Mission failed." | 10 | ✓ |
| `voice_longest_shot` | "Longest shot on record." | 2 | |
