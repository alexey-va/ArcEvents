#!/usr/bin/env python3
"""Render V2 exported source volume slices using the existing Java 1.21.11 texture renderer."""
import argparse
import struct
from pathlib import Path
import numpy as np
from PIL import Image, ImageChops, ImageOps
from render import DEFAULT_CLIENT, palette, render

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--client", type=Path, default=DEFAULT_CLIENT)
args = parser.parse_args()
_, colors = palette(args.directory, args.client)
for scene in ("starter", "laboratory", "reactor", "city"):
    data = (args.directory / (scene + ".vol")).read_bytes()
    x, y, z = struct.unpack(">iii", data[:12])
    blocks = np.frombuffer(data[12:], dtype=">u2").reshape(y, z, x)
    scale = 8 if scene == "starter" else 6
    picture = render(blocks, colors, scale, scale)
    background = picture.getpixel((0, 0))
    bounds = ImageChops.difference(picture, Image.new("RGB", picture.size, background)).getbbox()
    if bounds:
        picture = ImageOps.expand(picture.crop(bounds), border=16, fill=background)
    picture.save(args.directory / (scene + ".png"))
