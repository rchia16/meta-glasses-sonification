# Compact HRIR Conversion

Use this to convert large SOFA files into a lightweight runtime binary for Android.

## Script

`tools/sofa_to_compact_hrir.py`

## Requirements (desktop)

- Python 3.9+
- `numpy`
- `h5py`

Install:

```bash
pip install numpy h5py
```

## Example

Run from repo root:

```bash
python tools/sofa_to_compact_hrir.py \
  --sofa app/src/main/assets/sofa/BRIR_HATS_3degree_for_glasses.sofa \
  --out app/src/main/assets/hrir/hrir_compact_v1.bin \
  --meta-out app/src/main/assets/hrir/hrir_compact_v1.json \
  --target-sr 24000 \
  --taps 192 \
  --az-step 3.0 \
  --el-step 3.0
```

## Runtime wiring

Android now tries to load compact HRIR first:

- `assets/hrir/hrir_compact_v1.bin`

If unavailable/invalid, it falls back to SOFA parse (or stereo fallback if that also fails).
