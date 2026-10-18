# Simple Coil sounds

Every game sound plays through `SoundCues` (app/src/main/java/com/simplecoil/simplecoil/hardware/audio/SoundCues.kt). Each event has its own effect file and, for the important ones, a radio callout. **To add a sound, put the file in `app/src/main/res/raw/` under the name below** (OGG or WAV); the app picks it up on the next build. Until a file is there, the event plays one of the shipped GameBurp effects at a different pitch (and sometimes twice), so it still sounds distinct.

## Where the sounds come from

`tools/sounds/build-sounds.sh` makes them from three free packs on itch.io (the script lists the links and which file becomes which sound). It trims each one, makes it mono, cuts the deep bass phone speakers can't play, and evens out the loudness; callouts also get the narrow radio treatment and squelch.

- **Real gun recordings**, in `res/raw`: *Snake's Authentic Gun Sounds* and its sequel by SnakeF8 (free for commercial use, no credit needed; the author calls them public domain). Empty is a dry fire, spawn a bolt release or an AK rack, connecting the blaster an AR charging handle, the last mag and a power-up are magazine sounds.
- **Radio callouts**, in `res-licensed/raw`: the *VOiD1 Gaming Military Voice Pack*. Its license allows them in the app (no credit needed) but not sharing the files, so that folder is git-ignored and the public repo doesn't carry them. A build without it plays the effects alone. The pack's lines don't match the original script, so each callout uses the closest line it has (the table below shows what is said).
- **Shot, reload and the bomb going off** (`shootingshort.ogg`, played for every fire mode; `reload`; `sfx_bomb_exploded`), in `res/raw`: CC0 recordings the project owner supplied; the reload is cut to its mag-in and charging part, the explosion to its first 3.5 s.
- **Event sounds** (the `sfx_` cues that aren't gun sounds), in `res/raw`: made for this app with Claude. The countdown beep, `sfx_damage_tick` and `sfx_loot` are from an earlier batch.
- **Hit, kill and death sounds** (`hit`, `hit_confirm`, `hit_shielded`, `hit_eliminated`, `eliminated`, `score`) and the end-of-game whistle are the original GameBurp effects.

## Variety

Effects never play quite the same twice: each play moves the pitch up to 6% either way and the volume a little. For real variety, add more takes of a sound with `_2`, `_3`, ... up to `_6` after its name (for example `shootingshort_2.ogg`, `shootingshort_3.ogg`): one is picked at random, never the same twice running. The end-of-game whistle (`game_end`) is never varied.

## Quality rules (map design: Audio)

- Recorded voice, not text-to-speech: one calm military radio operator, clipped lines, NATO alphabet, a narrow radio treatment with a soft squelch click at each end.
- Effects from the GameBurp library (or one that sounds like it), mixed for phone speakers (mids and tops, no deep bass), all normalised to the same loudness.
- Effects under about 1 s, voice lines under about 2 s.

## Effects

| File | When |
|---|---|
| `shootingshort`, `shootingauto` | a shot in single fire; in burst or full auto (until `shootingauto` exists, burst and auto play `shootingshort`) |
| `hit`, `hit_confirm`, `hit_eliminated`, `hit_shielded` | being hit; your hit landing, eliminating, on a shield |
| `eliminated`, `spawn`, `reload`, `empty`, `beep`, `score` | as today |
| `sfx_connected` | the blaster connecting, outside a game |
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
| `sfx_round_start`, `sfx_bomb_planted`, `sfx_bomb_defused`, `sfx_bomb_exploded` | Search & Destroy (the last: the bomb going off) |
| `sfx_infected` | Infection: you were infected |
| `sfx_loot`, `sfx_loot_banked` | Extraction |
| `sfx_victory`, `sfx_defeat` | an objective game won or lost |

## Radio callouts

Played once the file exists, after its effect (the supply drop plays only its callout), the same line no more than every 8 seconds. The blaster's own sounds (shots, hits, reload, beeps) play at once and duck under a callout; every other sound takes its turn: one at a time in the order things happened, kills and deaths ahead of the rest, and the end of a game cuts off whatever plays and clears the line. Something that waited too long (2.5 s for a kill, 6 s, or 12 s if essential) is skipped rather than played late. Players can turn callouts off, or to essential only (marked ✓), in Sound settings.

| File | Line | Essential |
|---|---|---|
| `voice_leaving_area` | "Fall back." | ✓ |
| `voice_back_in_area` | "Affirmative." | |
| `voice_outside_zone` | "All in, now." | ✓ |
| `voice_zone_closing` | "Stay alert." | ✓ |
| `voice_spawn_moving` | "Alright, let's move out." | |
| `voice_drop_inbound` | "Airdrop inbound." | |
| `voice_power_up` | "Locked and loaded." | |
| `voice_killstreak` | "Enemy down." | |
| `voice_uav_online` | "Enemy spotted." | |
| `voice_last_mag` | "I need ammo." | |
| `voice_capturing` | "Hold this position." | |
| `voice_secured` | "Protect the spot." | ✓ |
| `voice_lost` | "Negative." | ✓ |
| `voice_hostiles_capturing` | "Need backup." | ✓ |
| `voice_point_moved` | "Get in position." | ✓ |
| `voice_flag_taken` | "Ambushed." | ✓ |
| `voice_flag_captured` | "Let's go." | ✓ |
| `voice_round_start` | "Go, go, go." | ✓ |
| `voice_charge_set` | "Fire in the hole." | ✓ |
| `voice_charge_defused` | "Roger that." | ✓ |
| `voice_infected` | "I'm hit." | ✓ |
| `voice_loot_banked` | "Reached." | |
| `voice_victory` | "Good job." | ✓ |
| `voice_defeat` | "Retreat." | ✓ |
