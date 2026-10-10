#!/usr/bin/env python3
"""Writes a few short stereo WAV "songs" (different tone on the left and right channel) for emulator tests."""
import math
import os
import struct
import sys
import wave

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
RATE = 44100
songs = {
    "Midnight Drive": (220.0, 330.0, 24),
    "Neon Rain": (262.0, 392.0, 24),
    "Sunrise Boulevard": (294.0, 440.0, 24),
    "Golden Hour": (196.0, 247.0, 24),
}
for name, (left, right, seconds) in songs.items():
    path = os.path.join(out, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = bytearray()
        for i in range(RATE * seconds):
            t = i / RATE
            env = min(1.0, t * 4) * min(1.0, (seconds - t) * 4)
            # a slow tremolo makes it audible that the music moves
            trem = 0.75 + 0.25 * math.sin(2 * math.pi * 2.0 * t)
            l = int(9000 * env * trem * math.sin(2 * math.pi * left * t))
            r = int(9000 * env * trem * math.sin(2 * math.pi * right * t))
            frames += struct.pack("<hh", l, r)
        w.writeframes(bytes(frames))
    print("wrote", path, os.path.getsize(path))
