#!/usr/bin/env python3
"""Shrink a PNG screenshot to a compact JPEG: to_jpeg.py in.png out.jpg"""
import sys
from PIL import Image

img = Image.open(sys.argv[1]).convert("RGB")
img.thumbnail((540, 1200))
img.save(sys.argv[2], "JPEG", quality=62, optimize=True)
