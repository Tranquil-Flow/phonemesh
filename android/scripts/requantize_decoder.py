"""Store Whisper's tied FP32 token embedding as INT8 + per-row scale (deterministic, offline)."""

import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper

NAME = "textDecoder.token_embedding.weight"


def requantize(src: Path, dst: Path) -> dict:
    model = onnx.load(src)
    graph = model.graph
    index = next((i for i, t in enumerate(graph.initializer) if t.name == NAME), None)
    if index is None:
        raise ValueError(f"{NAME} (token_embedding) not found")
    opset = next(o.version for o in model.opset_import if o.domain in ("", "ai.onnx"))
    if opset < 13:
        raise ValueError(f"per-axis DequantizeLinear needs opset >= 13, model has {opset}")
    weight = numpy_helper.to_array(graph.initializer[index]).astype(np.float32)
    scale = np.abs(weight).max(axis=1) / 127.0
    scale[scale == 0] = 1.0
    quant = np.clip(np.rint(weight / scale[:, None]), -127, 127).astype(np.int8)
    error = float(np.abs(quant.astype(np.float32) * scale[:, None] - weight).max())
    del graph.initializer[index]
    graph.initializer.extend(
        [
            numpy_helper.from_array(quant, NAME + "_quantized"),
            numpy_helper.from_array(scale.astype(np.float32), NAME + "_scale"),
            numpy_helper.from_array(np.zeros(weight.shape[0], dtype=np.int8), NAME + "_zero"),
        ]
    )
    node = helper.make_node(
        "DequantizeLinear",
        [NAME + "_quantized", NAME + "_scale", NAME + "_zero"],
        [NAME],
        axis=0,
        name=NAME + "_dequantize",
    )
    graph.node.insert(0, node)
    onnx.checker.check_model(model)
    onnx.save(model, dst)
    return {
        "input_bytes": src.stat().st_size,
        "output_bytes": dst.stat().st_size,
        "max_abs_error": error,
    }


if __name__ == "__main__":
    print(json.dumps(requantize(Path(sys.argv[1]), Path(sys.argv[2]))))
