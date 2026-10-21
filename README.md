# Blend

Smart DJ player, set maker and playlist organiser for the Samsung Galaxy Z Fold 8.

- **Plays your own files** (AIFF, WAV, FLAC, MP3, M4A/ALAC) from a folder on the phone.
- **Analyses every track** on the phone: BPM and beat grid, key (Camelot), energy 1–10,
  waveform, and two cue points — *mix in* (first downbeat) and *mix out* (start of the outro).
  You can correct anything (BPM ×2/÷2, key, energy, genre, drag the cues); your edits always win.
- **Mixes like a DJ**: when a track reaches its mix-out, the next one starts on its mix-in,
  tempo-matched, with its bass cut. Both play together, then the basses swap on a bar line
  and the old track fades. Bass swap, filter fade or echo out; 4, 8 or 16 bars.
- **Smart sets** order tracks as a story (warm-up → build → peak → cool-down), chaining
  compatible keys and letting the tempo drift gradually. Reshuffle for a new story; lock tracks in place.
- **Organise on the phone**: playlists, drag to reorder, comments, transition score between every pair.
- **Export to Rekordbox (XML) and Traktor (NML)** with order, comments, BPM, key, beat grid and
  the two cues as hot cues A/B. Set your computer's music folder in Settings so the paths match.
- **Folded**: one screen at a time. **Unfolded**: library on the left, playlist / mix on the right.

## Install

Every push builds an APK on GitHub: open the **Actions** tab → latest *Build APK* run →
download **blend-apk** → unzip → open the `.apk` on the phone (allow installing from this source).

Or open the project in Android Studio and run it on the Pixel Fold emulator / your phone.

## First run

1. Copy your music to a folder on the phone (USB, Google Drive, card…).
2. Open Blend → **Choose folder** → pick that folder → allow access.
3. Tracks appear right away; analysis runs in the background (a few seconds per track).
4. Tap **+** to create a playlist or a smart set, add tracks, press the orange play button.

## Code map

| Folder | What it does |
|---|---|
| `analysis/` | BPM, beat grid, key, energy, waveform and cue detection (pure Kotlin, unit-tested) |
| `audio/` | Decoding (own AIFF/WAV reader + MediaCodec), PCM cache, two-deck mixer |
| `setmaker/` | Transition scoring and story ordering |
| `export/` | Rekordbox XML and Traktor NML writers |
| `library/` | Folder scan, tags/artwork (ID3 incl. AIFF), JSON storage |
| `ui/` | Compose screens for folded and unfolded layouts |

Fonts: Inter and Inter Tight (SIL Open Font License, see `licenses/`).
