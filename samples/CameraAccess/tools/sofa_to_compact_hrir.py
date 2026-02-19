#!/usr/bin/env python3
"""
Convert a SOFA HRIR/BRIR file into a compact runtime-friendly binary.

Format (little-endian):
  magic[8]      = b'HRIRBIN1'
  version u32   = 1
  sample_rate i32
  tap_count i32
  entry_count i32
  repeated entry_count times:
    azimuth_deg f32
    elevation_deg f32
    left[tap_count]  i16
    right[tap_count] i16
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
from typing import Dict, Tuple

import h5py
import numpy as np


MAGIC = b"HRIRBIN1"
VERSION = 1


def _resample_1d(x: np.ndarray, src_sr: int, dst_sr: int) -> np.ndarray:
    if src_sr == dst_sr:
        return x.astype(np.float32, copy=False)
    if x.size == 0:
        return x.astype(np.float32, copy=False)
    ratio = float(dst_sr) / float(src_sr)
    out_len = max(1, int(round(x.size * ratio)))
    src_idx = np.linspace(0.0, x.size - 1.0, num=out_len, dtype=np.float64)
    xp = np.arange(x.size, dtype=np.float64)
    return np.interp(src_idx, xp, x.astype(np.float64)).astype(np.float32)


def _normalize_pair(left: np.ndarray, right: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    peak = float(max(np.max(np.abs(left)), np.max(np.abs(right)), 1e-9))
    if peak > 1.0:
        scale = 1.0 / peak
        left = left * scale
        right = right * scale
    return left, right


def _quantize_i16(x: np.ndarray) -> np.ndarray:
    x = np.clip(x, -1.0, 1.0)
    return np.round(x * 32767.0).astype(np.int16)


def _bin_key(az: float, el: float, az_step: float, el_step: float) -> Tuple[int, int]:
    az_wrapped = ((az + 180.0) % 360.0) - 180.0
    return (int(round(az_wrapped / az_step)), int(round(el / el_step)))


def convert(
    sofa_path: str,
    out_path: str,
    target_sr: int,
    taps: int,
    az_step: float,
    el_step: float,
) -> Dict[str, object]:
    with h5py.File(sofa_path, "r") as f:
        source_pos = np.asarray(f["/SourcePosition"], dtype=np.float32)  # [M, C]
        ir = np.asarray(f["/Data.IR"], dtype=np.float32)  # [M, R, N]
        sr = int(np.asarray(f["/Data.SamplingRate"]).reshape(-1)[0])

    if source_pos.ndim != 2 or source_pos.shape[1] < 2:
        raise ValueError(f"Unexpected SourcePosition shape: {source_pos.shape}")
    if ir.ndim != 3 or ir.shape[1] < 2:
        raise ValueError(f"Unexpected Data.IR shape: {ir.shape}")

    m, r, n = ir.shape
    entries_by_bin: Dict[Tuple[int, int], Tuple[float, float, np.ndarray, np.ndarray]] = {}

    for i in range(m):
        az = float(source_pos[i, 0])
        el = float(source_pos[i, 1])
        key = _bin_key(az, el, az_step=az_step, el_step=el_step)
        if key in entries_by_bin:
            continue
        left = ir[i, 0, :]
        right = ir[i, 1, :]
        entries_by_bin[key] = (az, el, left, right)

    compact_entries = []
    for _, (az, el, left, right) in sorted(entries_by_bin.items()):
        l = _resample_1d(left, src_sr=sr, dst_sr=target_sr)
        rr = _resample_1d(right, src_sr=sr, dst_sr=target_sr)
        if l.size >= taps:
            l = l[:taps]
            rr = rr[:taps]
        else:
            pad = taps - l.size
            l = np.pad(l, (0, pad), mode="constant")
            rr = np.pad(rr, (0, pad), mode="constant")
        l, rr = _normalize_pair(l, rr)
        compact_entries.append((az, el, _quantize_i16(l), _quantize_i16(rr)))

    out_dir = os.path.dirname(out_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(out_path, "wb") as wf:
        wf.write(MAGIC)
        wf.write(struct.pack("<Iiii", VERSION, int(target_sr), int(taps), int(len(compact_entries))))
        for az, el, l_i16, r_i16 in compact_entries:
            wf.write(struct.pack("<ff", float(az), float(el)))
            wf.write(l_i16.tobytes(order="C"))
            wf.write(r_i16.tobytes(order="C"))

    return {
        "input": sofa_path,
        "output": out_path,
        "source_sample_rate_hz": sr,
        "target_sample_rate_hz": target_sr,
        "source_shape_data_ir": [int(m), int(r), int(n)],
        "tap_count": taps,
        "entry_count": len(compact_entries),
        "az_step_deg": az_step,
        "el_step_deg": el_step,
        "output_bytes": os.path.getsize(out_path),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert SOFA to compact HRIR binary.")
    parser.add_argument("--sofa", required=True, help="Input SOFA path")
    parser.add_argument("--out", required=True, help="Output .bin path")
    parser.add_argument("--target-sr", type=int, default=24000, help="Target sample rate")
    parser.add_argument("--taps", type=int, default=192, help="FIR taps per ear")
    parser.add_argument("--az-step", type=float, default=3.0, help="Azimuth bin step")
    parser.add_argument("--el-step", type=float, default=3.0, help="Elevation bin step")
    parser.add_argument("--meta-out", default="", help="Optional JSON metadata output path")
    args = parser.parse_args()

    meta = convert(
        sofa_path=args.sofa,
        out_path=args.out,
        target_sr=args.target_sr,
        taps=args.taps,
        az_step=args.az_step,
        el_step=args.el_step,
    )

    if args.meta_out:
        meta_dir = os.path.dirname(args.meta_out)
        if meta_dir:
            os.makedirs(meta_dir, exist_ok=True)
        with open(args.meta_out, "w", encoding="utf-8") as f:
            json.dump(meta, f, indent=2)

    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
