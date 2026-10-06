import importlib.util
from pathlib import Path

import numpy as np
import pytest

onnx = pytest.importorskip("onnx")
from onnx import TensorProto, helper, numpy_helper  # noqa: E402

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/requantize_decoder.py"
spec = importlib.util.spec_from_file_location("requantize_decoder", SCRIPT)
rq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rq)

NAME = "textDecoder.token_embedding.weight"


def tied_model(weight: np.ndarray) -> onnx.ModelProto:
    tokens = helper.make_tensor_value_info("tokens", TensorProto.INT64, [None])
    emb = helper.make_tensor_value_info("emb", TensorProto.FLOAT, [None, weight.shape[1]])
    logits = helper.make_tensor_value_info("logits", TensorProto.FLOAT, [None, weight.shape[0]])
    nodes = [
        helper.make_node("Gather", [NAME, "tokens"], ["emb"]),
        helper.make_node("Transpose", [NAME], ["wt"], perm=[1, 0]),
        helper.make_node("MatMul", ["emb", "wt"], ["logits"]),
    ]
    graph = helper.make_graph(
        nodes, "g", [tokens], [emb, logits], [numpy_helper.from_array(weight, NAME)]
    )
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])


def test_tied_weight_becomes_int8_for_both_uses(tmp_path):
    rng = np.random.default_rng(0)
    weight = rng.standard_normal((64, 8)).astype(np.float32)
    src, dst = tmp_path / "in.onnx", tmp_path / "out.onnx"
    onnx.save(tied_model(weight), src)
    report = rq.requantize(src, dst)
    out = onnx.load(dst)
    inits = {i.name: i for i in out.graph.initializer}
    assert inits[NAME + "_quantized"].data_type == TensorProto.INT8
    assert NAME not in inits
    consumers = [n for n in out.graph.node if NAME in n.input]
    assert {n.op_type for n in consumers} == {"Gather", "Transpose"}
    dq = [n for n in out.graph.node if n.op_type == "DequantizeLinear"]
    assert len(dq) == 1 and dq[0].output == [NAME]
    assert report["max_abs_error"] <= float(np.abs(weight).max()) / 127 + 1e-6
    onnx.checker.check_model(out)


def test_output_is_deterministic(tmp_path):
    weight = np.arange(64 * 8, dtype=np.float32).reshape(64, 8) / 100
    src = tmp_path / "in.onnx"
    onnx.save(tied_model(weight), src)
    rq.requantize(src, tmp_path / "a.onnx")
    rq.requantize(src, tmp_path / "b.onnx")
    assert (tmp_path / "a.onnx").read_bytes() == (tmp_path / "b.onnx").read_bytes()


def test_missing_tensor_is_an_error(tmp_path):
    weight = np.ones((4, 2), dtype=np.float32)
    model = tied_model(weight)
    model.graph.initializer[0].name = "other"
    src = tmp_path / "in.onnx"
    onnx.save(model, src)
    with pytest.raises(ValueError, match="token_embedding"):
        rq.requantize(src, tmp_path / "out.onnx")
