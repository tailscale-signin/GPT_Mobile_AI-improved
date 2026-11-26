#!/usr/bin/env python3
"""Build an offline GPU or SM8750 vocoder patch for the pinned standard Kokoro model."""
import argparse
import gzip
import hashlib
import mmap
import os
from pathlib import Path
import struct
import tempfile

from build_kokoro_sm8750_pack import BLOCK, sections


STANDARD_SHA = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1"
TARGET_SIZES = {
    "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52": 294750530,
    "04ee7076b0c8e5b11239ba5ed8ee7a3540984acb17936441b904196154fc0ebb": 295602498,
}
ANCHOR = 24
STRIDE = 64
CHUNK = 4096


def common_prefix(old, source, new, target, limit):
    length = 0
    while length + CHUNK <= limit and old[source + length:source + length + CHUNK] == new[target + length:target + length + CHUNK]:
        length += CHUNK
    lo, hi = 0, min(CHUNK, limit - length)
    while lo < hi:
        middle = (lo + hi + 1) // 2
        if old[source + length:source + length + middle] == new[target + length:target + length + middle]:
            lo = middle
        else:
            hi = middle - 1
    return length + lo


def regions(model):
    starts = [0] + [row[3] for row in sections(model[:BLOCK])] + [len(model)]
    return zip(starts, starts[1:])


def instructions(old, old_range, new, new_range):
    source, source_end = old_range
    target, target_end = new_range
    length = common_prefix(old, source, new, target, min(source_end - source, target_end - target))
    if length:
        yield 1, source, length
        source += length
        target += length
    if target == target_end:
        return

    # Index the changed section only. Match relocated weights even when their target
    # offsets change alignment. Repeated anchors use the first match deterministically.
    index = {}
    for offset in range(source, source_end - ANCHOR + 1, STRIDE):
        index.setdefault(old[offset:offset + ANCHOR], offset)
    literal = target
    while target + ANCHOR <= target_end:
        offset = index.get(new[target:target + ANCHOR])
        if offset is None:
            target += 1
            continue
        length = common_prefix(old, offset, new, target, min(source_end - offset, target_end - target))
        if length < STRIDE:
            target += 1
            continue
        # Extend backward over pending literals to the start of this match.
        left = 0
        while left < min(STRIDE, target - literal, offset - source) and new[target - left - 1] == old[offset - left - 1]:
            left += 1
        if target - left > literal:
            yield 2, literal, target - left - literal
        yield 1, offset - left, length + left
        target += length
        literal = target
    if target_end > literal:
        yield 2, literal, target_end - literal


def encode(source, target, destination):
    with gzip.GzipFile(filename="", fileobj=destination, mode="wb", mtime=0, compresslevel=9) as writer:
        writer.write(b"KSM1")
        for old, new in zip(regions(source), regions(target), strict=True):
            for opcode, offset, length in instructions(source, old, target, new):
                if opcode == 1:
                    writer.write(struct.pack(">BII", opcode, offset, length))
                else:
                    writer.write(struct.pack(">BI", opcode, length))
                    writer.write(target[offset:offset + length])
        writer.write(b"\x00")


def read_exact(stream, size):
    data = stream.read(size)
    if len(data) != size:
        raise ValueError("Truncated Kokoro patch")
    return data


def verify(source, patch, expected_size, expected_sha):
    """Replay exactly the records read by Android and hash the reconstructed pack."""
    digest = hashlib.sha256()
    total = 0
    with gzip.open(patch, "rb") as reader:
        if read_exact(reader, 4) != b"KSM1":
            raise ValueError("Invalid Kokoro patch signature")
        while True:
            opcode = read_exact(reader, 1)[0]
            if opcode == 0:
                if reader.read(1):
                    raise ValueError("Unexpected data after the final instruction")
                break
            if opcode == 1:
                offset, length = struct.unpack(">II", read_exact(reader, 8))
                if offset + length > len(source):
                    raise ValueError("COPY outside the source pack")
            elif opcode == 2:
                length, = struct.unpack(">I", read_exact(reader, 4))
            else:
                raise ValueError(f"Unknown Kokoro patch opcode {opcode}")
            if not 0 < length <= expected_size - total:
                raise ValueError("Invalid output length")
            total += length
            while length:
                count = min(length, 65536)
                if opcode == 1:
                    digest.update(source[offset:offset + count])
                    offset += count
                else:
                    digest.update(read_exact(reader, count))
                length -= count
    if total != expected_size or digest.hexdigest() != expected_sha:
        raise ValueError("Reconstructed model differs from the pinned target pack")


def build(standard, alternate, output):
    with standard.open("rb") as original, alternate.open("rb") as specialized:
        with mmap.mmap(original.fileno(), 0, access=mmap.ACCESS_READ) as source, mmap.mmap(specialized.fileno(), 0, access=mmap.ACCESS_READ) as target:
            if hashlib.sha256(source).hexdigest() != STANDARD_SHA:
                raise ValueError("Expected the pinned standard Kokoro pack")
            target_sha = hashlib.sha256(target).hexdigest()
            if TARGET_SIZES.get(target_sha) != len(target):
                raise ValueError("Expected a pinned GPU or SM8750 target")
            with tempfile.TemporaryDirectory(dir=output.parent) as directory:
                staged = Path(directory) / "model.ksm"
                with staged.open("wb") as destination:
                    encode(source, target, destination)
                verify(source, staged, len(target), target_sha)
                os.link(staged, output)
    print(f"{output.stat().st_size} bytes, SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("standard", type=Path, help="Publisher kokoro_int8_fp16.litertlm")
    parser.add_argument("alternate", type=Path, help="Publisher GPU pack or verified output of build_kokoro_sm8750_pack.py")
    parser.add_argument("output", type=Path, help="New patch file, e.g. app/src/main/assets/voice/kokoro_gpu.ksm")
    args = parser.parse_args()
    build(args.standard, args.alternate, args.output)
