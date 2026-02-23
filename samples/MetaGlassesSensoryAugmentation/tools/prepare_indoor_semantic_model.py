#!/usr/bin/env python3
"""
Prepare an indoor semantic segmentation model for local use.

This script downloads a Hugging Face semantic segmentation model (default:
SegFormer-B0 trained on ADE20K), stores model assets on disk, and emits a
compact class map focused on indoor classes like walls, doors, and furniture.
"""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List


DEFAULT_MODEL_ID = "nvidia/segformer-b0-finetuned-ade-512-512"
DEFAULT_OUTPUT_DIR = "app/src/main/assets/models/segformer_b0_ade20k"
DEFAULT_TFLITE_FILENAME = "model.tflite"

# ADE20K-compatible indoor classes typically useful for room decomposition.
DEFAULT_INDOOR_CLASS_NAMES = (
    "wall",
    "floor",
    "ceiling",
    "door",
    "windowpane",
    "table",
    "chair",
    "sofa",
    "bed",
    "cabinet",
    "shelf",
    "desk",
    "armchair",
    "wardrobe",
    "lamp",
    "bookcase",
    "refrigerator",
    "sink",
    "toilet",
    "bathtub",
    "tv",
)


@dataclass
class PreparedModelSummary:
    model_id: str
    output_dir: str
    tflite_path: str
    indoor_classes_found: List[str]
    indoor_class_ids: List[int]
    missing_indoor_classes: List[str]

    def to_dict(self) -> Dict[str, object]:
        return {
            "model_id": self.model_id,
            "output_dir": self.output_dir,
            "tflite_path": self.tflite_path,
            "indoor_classes_found": self.indoor_classes_found,
            "indoor_class_ids": self.indoor_class_ids,
            "missing_indoor_classes": self.missing_indoor_classes,
        }


def _normalize_label(name: str) -> str:
    return name.strip().lower().replace("-", " ").replace("_", " ")


def _prepare_indoor_class_map(
    id2label: Dict[int, str],
    requested_class_names: Iterable[str],
) -> Dict[str, Dict[str, object]]:
    by_normalized_name = {_normalize_label(label): (idx, label) for idx, label in id2label.items()}
    indoor_class_map: Dict[str, Dict[str, object]] = {}
    for raw_name in requested_class_names:
        key = _normalize_label(raw_name)
        if key not in by_normalized_name:
            continue
        idx, canonical_label = by_normalized_name[key]
        indoor_class_map[key] = {"id": int(idx), "label": canonical_label}
    return indoor_class_map


def _write_json(path: Path, payload: Dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and prepare indoor semantic segmentation model assets.")
    parser.add_argument(
        "--model-id",
        default=DEFAULT_MODEL_ID,
        help=f"Hugging Face model id (default: {DEFAULT_MODEL_ID})",
    )
    parser.add_argument(
        "--output-dir",
        default=DEFAULT_OUTPUT_DIR,
        help=f"Local output directory for model assets (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--indoor-classes",
        default=",".join(DEFAULT_INDOOR_CLASS_NAMES),
        help="Comma-separated indoor class names to keep in the class map.",
    )
    parser.add_argument(
        "--hf-token",
        default=os.environ.get("HF_TOKEN", ""),
        help="Optional Hugging Face token. Falls back to HF_TOKEN env var.",
    )
    parser.add_argument(
        "--tflite-filename",
        default=DEFAULT_TFLITE_FILENAME,
        help=f"TFLite output filename inside --output-dir (default: {DEFAULT_TFLITE_FILENAME})",
    )
    parser.add_argument(
        "--input-height",
        type=int,
        default=512,
        help="Static model input height for TFLite export (default: 512).",
    )
    parser.add_argument(
        "--input-width",
        type=int,
        default=512,
        help="Static model input width for TFLite export (default: 512).",
    )
    parser.add_argument(
        "--quantization",
        choices=("fp16", "float32"),
        default="fp16",
        help="TFLite quantization mode. fp16 is smaller and faster on many mobile GPUs.",
    )
    return parser.parse_args()


def _convert_to_tflite(
    tf_model: "tf.keras.Model",
    output_path: Path,
    quantization: str,
) -> None:
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_keras_model(tf_model)
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    if quantization == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()
    output_path.write_bytes(tflite_model)


def main() -> None:
    args = parse_args()

    import tensorflow as tf
    from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation, TFAutoModelForSemanticSegmentation
    try:
        import tensorflow as tf
        from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation, TFAutoModelForSemanticSegmentation
    except ImportError as exc:
        raise SystemExit(
            "Missing dependency: tensorflow/transformers.\n"
            "Install with: pip install tensorflow transformers torch pillow"
            f"{exc}"
        ) from exc

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    tflite_path = output_dir / args.tflite_filename

    token = args.hf_token or None
    image_processor = AutoImageProcessor.from_pretrained(args.model_id, token=token)
    model = AutoModelForSemanticSegmentation.from_pretrained(args.model_id, token=token)
    tf_model = TFAutoModelForSemanticSegmentation.from_pretrained(args.model_id, from_pt=True, token=token)

    image_processor.save_pretrained(output_dir)
    model.save_pretrained(output_dir)
    tf_model.build(input_shape=(1, args.input_height, args.input_width, 3))
    _convert_to_tflite(tf_model=tf_model, output_path=tflite_path, quantization=args.quantization)

    id2label_raw = getattr(model.config, "id2label", {})
    if not id2label_raw:
        raise SystemExit(f"Model {args.model_id} has no id2label mapping in config.")

    id2label = {int(k): v for k, v in id2label_raw.items()}
    requested = [x.strip() for x in args.indoor_classes.split(",") if x.strip()]
    indoor_map = _prepare_indoor_class_map(id2label=id2label, requested_class_names=requested)

    all_classes_payload = {"id2label": {str(k): v for k, v in sorted(id2label.items())}}
    indoor_payload = {"classes": indoor_map}
    _write_json(output_dir / "all_classes.json", all_classes_payload)
    _write_json(output_dir / "indoor_classes.json", indoor_payload)
    _write_json(
        output_dir / "tflite_export.json",
        {
            "tflite_file": args.tflite_filename,
            "input_shape_nhwc": [1, args.input_height, args.input_width, 3],
            "quantization": args.quantization,
        },
    )

    indoor_ids_sorted = sorted(v["id"] for v in indoor_map.values())
    indoor_labels_sorted = [id2label[i] for i in indoor_ids_sorted]
    missing_classes = sorted(set(_normalize_label(x) for x in requested) - set(indoor_map.keys()))

    summary = PreparedModelSummary(
        model_id=args.model_id,
        output_dir=str(output_dir),
        tflite_path=str(tflite_path),
        indoor_classes_found=indoor_labels_sorted,
        indoor_class_ids=indoor_ids_sorted,
        missing_indoor_classes=missing_classes,
    )
    print(json.dumps(summary.to_dict(), indent=2))


if __name__ == "__main__":
    main()
