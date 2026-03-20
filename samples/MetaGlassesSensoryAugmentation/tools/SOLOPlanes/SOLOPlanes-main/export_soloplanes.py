#!/usr/bin/env python3
"""
Export SOLOPlanes from a PyTorch Lightning checkpoint to a mobile-friendly artifact.

The export cut point is the raw model heads:
  - plane_params: dense 3-channel per-pixel plane parameters
  - plane_feat: intermediate plane feature map
  - mask_feat: mask feature map
  - cate_l{0,1,2}: category logits for SOLO levels
  - kernel_l{0,1,2}: dynamic mask kernels for SOLO levels

This avoids Python-only postprocessing such as Matrix NMS and mask assembly.
Those steps can be reimplemented on Android.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import types
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import torch
import torch.nn as nn


ROOT = Path(__file__).resolve().parent
DEFAULT_CHECKPOINT = ROOT / "checkpoints" / "solopmv.ckpt"
DEFAULT_BACKBONE = ROOT / "pretrained" / "resnet50.pth"
DEFAULT_OUTPUT = ROOT / "checkpoints" / "solopmv_mobile.onnx"
DEFAULT_METADATA = ROOT / "checkpoints" / "solopmv_mobile.json"


def _make_package(name: str) -> types.ModuleType:
    module = types.ModuleType(name)
    module.__path__ = []
    sys.modules[name] = module
    return module


def _make_module(name: str) -> types.ModuleType:
    module = types.ModuleType(name)
    sys.modules[name] = module
    return module


def install_import_shims() -> None:
    """Provide lightweight fallbacks so the repo can be imported for inference/export."""
    if "config.solopmv_cfg" not in sys.modules:
        cfg_pkg = _make_package("config")
        cfg_mod = _make_module("config.solopmv_cfg")
        cfg_mod.config = SimpleNamespace(dataset=SimpleNamespace(MAX_DEPTH=10, depth_error_margin=0.10))
        cfg_pkg.solopmv_cfg = cfg_mod

    if "pytorch_lightning" not in sys.modules:
        pl_mod = _make_module("pytorch_lightning")

        class LightningModule(nn.Module):
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                super().__init__()

            @property
            def device(self) -> torch.device:
                return torch.device("cpu")

        pl_mod.LightningModule = LightningModule

    if "kornia.augmentation" not in sys.modules:
        kornia_pkg = _make_package("kornia")
        aug_mod = _make_module("kornia.augmentation")

        class Normalize(nn.Module):
            def __init__(self, mean: torch.Tensor, std: torch.Tensor) -> None:
                super().__init__()
                self.register_buffer("mean", mean.view(1, -1, 1, 1).float())
                self.register_buffer("std", std.view(1, -1, 1, 1).float())

            def forward(self, x: torch.Tensor) -> torch.Tensor:
                return (x - self.mean) / self.std

        class IdentityAug(nn.Module):
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                super().__init__()

            def forward(self, x: torch.Tensor) -> torch.Tensor:
                return x

        aug_mod.Normalize = Normalize
        for name in [
            "RandomMotionBlur",
            "RandomHorizontalFlip",
            "RandomPlanckianJitter",
            "ColorJitter",
            "RandomChannelShuffle",
            "RandomGaussianNoise",
        ]:
            setattr(aug_mod, name, IdentityAug)
        kornia_pkg.augmentation = aug_mod

    if "cv2" not in sys.modules:
        _make_module("cv2")

    if "scipy.ndimage" not in sys.modules:
        scipy_pkg = _make_package("scipy")
        ndimage_mod = _make_module("scipy.ndimage")

        def _unsupported_center_of_mass(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("scipy.ndimage.center_of_mass shim was called during export")

        ndimage_mod.center_of_mass = _unsupported_center_of_mass
        scipy_pkg.ndimage = ndimage_mod

    if "skimage.transform" not in sys.modules:
        skimage_pkg = _make_package("skimage")
        transform_mod = _make_module("skimage.transform")

        def _unsupported_rescale(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("skimage.transform.rescale shim was called during export")

        transform_mod.rescale = _unsupported_rescale
        skimage_pkg.transform = transform_mod

    if "six.moves" not in sys.modules:
        import builtins

        six_pkg = _make_package("six")
        moves_mod = _make_module("six.moves")
        moves_mod.map = builtins.map
        moves_mod.zip = builtins.zip
        six_pkg.moves = moves_mod

    if "matplotlib.pyplot" not in sys.modules:
        matplotlib_pkg = _make_package("matplotlib")
        pyplot_mod = _make_module("matplotlib.pyplot")
        transforms_mod = _make_module("matplotlib.transforms")
        matplotlib_pkg.pyplot = pyplot_mod
        matplotlib_pkg.transforms = transforms_mod

    if "cupy" not in sys.modules:
        _make_module("cupy")

    # SOLOPlanes imports torchvision.ops.sigmoid_focal_loss in the training head,
    # but the export path never calls it. Shimming this avoids requiring a working
    # torchvision/Pillow native stack just to load the checkpoint for inference.
    if "torchvision.ops" not in sys.modules:
        torchvision_pkg = _make_package("torchvision")
        ops_mod = _make_module("torchvision.ops")

        def _unused_sigmoid_focal_loss(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("torchvision.ops.sigmoid_focal_loss should not be called during export")

        ops_mod.sigmoid_focal_loss = _unused_sigmoid_focal_loss
        torchvision_pkg.ops = ops_mod


def build_cfg(backbone_path: Path) -> SimpleNamespace:
    return SimpleNamespace(
        neck=SimpleNamespace(out_channels=256),
        model=SimpleNamespace(
            mask_feat_channels=128,
            plane_feat_channels=64,
            use_same_feat_lvls=True,
            num_classes=41,
            dice_loss_weight=3,
            plane_head_kernel=3,
            use_plane_feat_head=True,
            planefeat_startlvl=0,
            planefeat_endlvl=1,
            plane_feat_xycoord=False,
            consistency_loss=False,
            cate_loss_weight=1,
        ),
        backbone=SimpleNamespace(
            name="resnet50",
            use_pretrained=False,
            path=str(backbone_path.resolve()),
        ),
        lr=1e-4,
        start_lr=1e-8,
        lr_warmup_steps=0,
        optimizer=torch.optim.AdamW,
        test_cfg=dict(
            nms_pre=500,
            score_thr=0.1,
            mask_thr=0.5,
            update_thr=0.05,
            kernel="gaussian",
            sigma=2.0,
            max_per_img=40,
        ),
        batch_size=1,
        dataset=SimpleNamespace(
            img_mean=[0.485, 0.456, 0.406],
            img_std=[0.229, 0.224, 0.225],
            augment=False,
            input_size=(480, 640),
            original_size=(480, 640),
            MAX_DEPTH=10,
            depth_error_margin=0.10,
        ),
    )


class SOLOPlanesExportWrapper(nn.Module):
    def __init__(self, base_model: nn.Module) -> None:
        super().__init__()
        self.base_model = base_model

    def forward(self, image: torch.Tensor) -> tuple[torch.Tensor, ...]:
        plane_params, plane_feat, mask_feat, cate_pred, kernel_pred = self.base_model.forward(image)
        return (
            plane_params,
            plane_feat,
            mask_feat,
            cate_pred[0],
            cate_pred[1],
            cate_pred[2],
            kernel_pred[0],
            kernel_pred[1],
            kernel_pred[2],
        )


def load_model(checkpoint_path: Path, backbone_path: Path) -> tuple[nn.Module, dict[str, Any], list[str]]:
    install_import_shims()
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))

    from modules.solopmv import SOLOP

    cfg = build_cfg(backbone_path=backbone_path)
    checkpoint = torch.load(checkpoint_path, map_location="cpu")
    state_dict = checkpoint["state_dict"] if "state_dict" in checkpoint else checkpoint

    model = SOLOP(cfg, mode="val")
    missing, unexpected = model.load_state_dict(state_dict, strict=False)
    model.eval()
    return model, checkpoint, list(missing) + list(unexpected)


def inspect_output_contract(model: nn.Module, input_height: int, input_width: int) -> dict[str, Any]:
    wrapper = SOLOPlanesExportWrapper(model).eval()
    dummy = torch.randn(1, 3, input_height, input_width)
    with torch.no_grad():
        outputs = wrapper(dummy)

    output_names = [
        "plane_params",
        "plane_feat",
        "mask_feat",
        "cate_l0",
        "cate_l1",
        "cate_l2",
        "kernel_l0",
        "kernel_l1",
        "kernel_l2",
    ]
    shapes = {name: list(t.shape) for name, t in zip(output_names, outputs)}
    return {
        "input_shape_nchw": [1, 3, input_height, input_width],
        "output_names": output_names,
        "output_shapes": shapes,
    }


def export_torchscript(model: nn.Module, output_path: Path, input_height: int, input_width: int) -> None:
    wrapper = SOLOPlanesExportWrapper(model).eval()
    dummy = torch.randn(1, 3, input_height, input_width)
    with torch.no_grad():
        traced = torch.jit.trace(wrapper, dummy, strict=False)
        traced.save(str(output_path))


def export_onnx(model: nn.Module, output_path: Path, input_height: int, input_width: int, dynamic: bool) -> None:
    try:
        import onnx  # noqa: F401
    except ImportError as exc:
        raise SystemExit(
            "ONNX export requires the `onnx` package. Install it with: pip install onnx"
        ) from exc

    wrapper = SOLOPlanesExportWrapper(model).eval()
    dummy = torch.randn(1, 3, input_height, input_width)
    output_names = [
        "plane_params",
        "plane_feat",
        "mask_feat",
        "cate_l0",
        "cate_l1",
        "cate_l2",
        "kernel_l0",
        "kernel_l1",
        "kernel_l2",
    ]
    dynamic_axes = None
    if dynamic:
        dynamic_axes = {
            "image": {0: "batch", 2: "height", 3: "width"},
            "plane_params": {0: "batch", 2: "plane_h", 3: "plane_w"},
            "plane_feat": {0: "batch", 2: "plane_h", 3: "plane_w"},
            "mask_feat": {0: "batch", 2: "mask_h", 3: "mask_w"},
            "cate_l0": {0: "batch"},
            "cate_l1": {0: "batch"},
            "cate_l2": {0: "batch"},
            "kernel_l0": {0: "batch"},
            "kernel_l1": {0: "batch"},
            "kernel_l2": {0: "batch"},
        }

    torch.onnx.export(
        wrapper,
        dummy,
        str(output_path),
        input_names=["image"],
        output_names=output_names,
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
        dynamic_axes=dynamic_axes,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export SOLOPlanes to ONNX or TorchScript.")
    parser.add_argument("--checkpoint", default=str(DEFAULT_CHECKPOINT), help="Path to solopmv.ckpt")
    parser.add_argument("--backbone", default=str(DEFAULT_BACKBONE), help="Path to pretrained resnet50.pth")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="Output file (.onnx or .pt)")
    parser.add_argument("--metadata", default=str(DEFAULT_METADATA), help="Metadata JSON output path")
    parser.add_argument("--input-height", type=int, default=480, help="Model input height")
    parser.add_argument("--input-width", type=int, default=640, help="Model input width")
    parser.add_argument("--format", choices=("onnx", "torchscript"), default="onnx", help="Export format")
    parser.add_argument("--dynamic", action="store_true", help="Export dynamic ONNX spatial axes")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    checkpoint_path = Path(args.checkpoint)
    backbone_path = Path(args.backbone)
    output_path = Path(args.output)
    metadata_path = Path(args.metadata)

    if not checkpoint_path.exists():
        raise SystemExit(f"Checkpoint not found: {checkpoint_path}")
    if not backbone_path.exists():
        raise SystemExit(f"Backbone weights not found: {backbone_path}")

    model, checkpoint, state_load_notes = load_model(
        checkpoint_path=checkpoint_path,
        backbone_path=backbone_path,
    )
    contract = inspect_output_contract(
        model=model,
        input_height=args.input_height,
        input_width=args.input_width,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)

    if args.format == "onnx":
        export_onnx(
            model=model,
            output_path=output_path,
            input_height=args.input_height,
            input_width=args.input_width,
            dynamic=args.dynamic,
        )
    else:
        export_torchscript(
            model=model,
            output_path=output_path,
            input_height=args.input_height,
            input_width=args.input_width,
        )

    metadata = {
        "source_checkpoint": str(checkpoint_path.resolve()),
        "source_backbone": str(backbone_path.resolve()),
        "export_format": args.format,
        "export_path": str(output_path.resolve()),
        "checkpoint_epoch": checkpoint.get("epoch"),
        "checkpoint_global_step": checkpoint.get("global_step"),
        "state_load_notes": state_load_notes,
        "head_summary": {
            "plane_head_channels": 3,
            "mask_feat_channels": 128,
            "plane_feat_channels": 64,
            "solo_num_classes": 41,
            "solo_levels": [36, 24, 16],
            "kernel_channels": 128,
        },
        "contract": contract,
    }
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
