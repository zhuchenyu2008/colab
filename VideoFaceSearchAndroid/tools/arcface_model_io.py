#!/usr/bin/env python3
"""Derive ncnn ArcFace input/output blob names from the generated .param graph."""
from pathlib import Path
import sys

src, dest = map(Path, sys.argv[1:3])
lines = [line.split() for line in src.read_text().splitlines() if line.strip()]
layers = [row for row in lines if len(row) >= 5 and row[0] != "7767517" and not row[0].isdigit()]
inputs = [row for row in layers if row[0] == "Input"]
assert len(inputs) == 1, f"expected one input, got {inputs}"
input_layer = inputs[0]
assert int(input_layer[2]) == 0 and int(input_layer[3]) >= 1
input_blob = input_layer[4]
last_layer = layers[-1]
number_in = int(last_layer[2])
number_out = int(last_layer[3])
if number_out:
    output_blob = last_layer[4 + number_in]
else:
    assert number_in >= 1
    output_blob = last_layer[4]
assert input_blob and output_blob
assert all(ch.isalnum() or ch in '._/-' for ch in input_blob+output_blob), "unexpected blob names"
dest.write_text(
    '#pragma once\n'
    f'#define ARC_INPUT_BLOB "{input_blob}"\n'
    f'#define ARC_OUTPUT_BLOB "{output_blob}"\n'
)
print(f"ArcFace ncnn blobs: input={input_blob}, output={output_blob}")
