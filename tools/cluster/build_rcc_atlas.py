#!/usr/bin/env python3
"""Private offline atlas of factory Qt RCC resources: each image beside the QML/JS code naming it.

Decodes binary RCC format 1-3 (zlib/zstd) and Qt resource trees compiled into native ELF libraries,
extracts every resource, renders thumbnails and writes Markdown (inline HTML tables) plus a print
HTML and, optionally, a PDF through Edge/Chrome headless. QML is read as text and never executed.
The output holds proprietary OEM code and artwork: keep it outside public repositories.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import io
import json
import posixpath
import re
import shutil
import struct
import subprocess
import tempfile
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

RASTER = {'.png', '.jpg', '.jpeg', '.bmp', '.gif', '.webp', '.tga'}
VECTOR = {'.svg'}
TEXTURE = {'.ktx', '.astc', '.pkm'}
IMAGES = RASTER | VECTOR | TEXTURE
MESH = {'.mesh'}
FONT = {'.ttf', '.otf'}
VISUAL = IMAGES | MESH | FONT
VISUAL_KINDS = ('image', 'mesh', 'font')
SERIES_LINKS = ('motif', 'préfixe', 'motif approché', 'dossier cité', 'chemin natif', 'variable résolue')
CODE = {'.qml', '.js', '.mjs'}
THUMB = (240, 120)
MAX_SNIPPET = 16
MAX_REFS = 6
SEQUENCE_MIN = 5
MAX_RESOURCE = 512 * 1024 * 1024
MAX_PIXELS = 64 * 1024 * 1024
WILD = '\x00'
STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"|\'((?:[^\'\\\n]|\\.)*)\'|`((?:[^`\\\n]|\\.)*)`')
COMMENT_TOKEN = re.compile(r'//|/\*|\*/')
VISUAL_HINT = re.compile(r'\.(?:png|jpe?g|bmp|gif|webp|tga|svg|ktx|astc|pkm|mesh|ttf|otf)\b', re.I)
NAME_TOKEN = re.compile(r'(?<![\w\-.%])[\w\-.]+\.(?:png|jpe?g|bmp|gif|webp|tga|svg|ktx|astc|pkm|mesh|ttf|otf)(?![\w\-])', re.I)
NATIVE_PATH = re.compile(r'(?:qrc:|(?<![A-Za-z0-9]):)(/[^/\s"\'<>][^\s"\'<>]*)')
ASSIGN = re.compile(r'([A-Za-z_]\w*)\s*(?::|(?<![=!<>+\-*/])=(?![=>]))')
VARIABLE_BEFORE = re.compile(r'([A-Za-z_][\w.]*)\s*\+\s*$')
ELEMENT = re.compile(r'([A-Za-z_][\w.]*)\s*(?:on\s+[\w.]+\s*)?\{\s*$')
SEQUENCE = re.compile(r'^(?P<pre>.*?)(?P<num>\d+)(?P<ext>\.[^.]+)$')
UNSAFE_NAME = re.compile(r'[\x00-\x1f<>:"|?*\\]|^(?:con|prn|aux|nul|com\d|lpt\d)(?:\..*)?$|[. ]$', re.I)
REGEX_SYNTAX = re.compile(r'[()*?\[\]\\^$|+{}]')
KTX1 = b'\xabKTX 11\xbb\r\n\x1a\n'
ASTC_BLOCKS = [(4, 4), (5, 4), (5, 5), (6, 5), (6, 6), (8, 5), (8, 6), (8, 8), (10, 5), (10, 6), (10, 8), (10, 10),
               (12, 10), (12, 12)]


@dataclass
class Resource:
    rcc: str
    path: str
    stored: int
    size: int
    flags: int
    sha: str
    kind: str
    dims: str = ''
    thumb: str = ''
    note: str = ''
    family: str = ''
    unused: str = ''
    text: str = ''
    lines: list = field(default_factory=list)
    masked: list = field(default_factory=list)
    refs: list = field(default_factory=list)
    comments: list = field(default_factory=list)


def ext_of(path: str) -> str:
    return posixpath.splitext(path)[1].lower()


def qt_hash(name: str) -> int:
    value = 0
    for char in name:
        value = (value << 4) + ord(char)
        value ^= (value & 0xF0000000) >> 23
        value &= 0x0FFFFFFF
    return value


def decode_rcc(blob: bytes):
    """Yield (path, flags, country, language, stored_len, data) for every file node."""
    if blob[:4] != b'qres':
        raise ValueError('not a binary RCC')
    version, tree, data, names = struct.unpack_from('>4I', blob, 4)
    if version not in (1, 2, 3):
        raise ValueError(f'unsupported RCC version {version}')
    yield from walk_tree(blob, tree, data, names, 14 if version == 1 else 22)


def decode_embedded(blob: bytes, region=None):
    """Find the Qt resource tree compiled into a native binary (rcc C++ output) and walk it."""
    entries = {}
    for run in re.finditer(rb'(?:\x00[\x20-\x7e]){1,255}', blob):
        start = run.start() - 6
        if start < 0:
            continue
        length, stored = struct.unpack_from('>HI', blob, start)
        if 0 < length <= (run.end() - run.start()) // 2:
            name = blob[start + 6:start + 6 + 2 * length].decode('utf-16be')
            if qt_hash(name) == stored:
                entries[start] = 6 + 2 * length
    candidates, ends = [], {start + size for start, size in entries.items()}
    for first in sorted(start for start in entries if start not in ends):
        cursor, offsets = first, set()
        while cursor in entries:
            offsets.add(cursor - first)
            cursor += entries[cursor]
        candidates.append((len(offsets), first, cursor, offsets))
    for _, names, names_end, offsets in sorted(candidates, reverse=True):
        for node in (22, 14):
            for root in re.finditer(rb'\x00{4}\x00\x02\x00{3}[\x01-\xff]\x00{3}\x01', blob):
                tree = root.start()
                if not tree_names_valid(blob, tree, node, offsets):
                    continue
                for data in data_bases(blob, tree, node, names, names_end):
                    try:
                        rows = list(walk_tree(blob, tree, data, names, node))
                    except (ValueError, struct.error, zlib.error):
                        continue
                    if rows and all(row[5][:8] == b'\x89PNG\r\n\x1a\n' for row in rows if ext_of(row[0]) == '.png'):
                        if region is not None:
                            region[:] = [min(tree, names), max(names_end, data + sum(4 + row[4] for row in rows))]
                        yield from rows
                        return
    raise ValueError('no compiled Qt resource tree found')


def tree_names_valid(blob: bytes, tree: int, node: int, offsets) -> bool:
    seen, stack = set(), [0]
    while stack:
        index = stack.pop()
        if index in seen or tree + node * (index + 1) > len(blob):
            return False
        seen.add(index)
        name, flags = struct.unpack_from('>IH', blob, tree + node * index)
        if index and name not in offsets or flags not in (0, 1, 2, 4):
            return False
        if flags & 2:
            count, first = struct.unpack_from('>II', blob, tree + node * index + 6)
            stack.extend(range(first, first + count))
    return len(seen) > 1


def data_bases(blob: bytes, tree: int, node: int, names: int, names_end: int):
    """Candidate starts of the payload array: right after the names, then PNG-anchored votes."""
    yield names_end
    files, stack = [], [0]
    while stack and len(files) < 64:
        index = stack.pop()
        flags = struct.unpack_from('>H', blob, tree + node * index + 4)[0]
        if flags & 2:
            count, first = struct.unpack_from('>II', blob, tree + node * index + 6)
            stack.extend(range(first, first + count))
        elif not flags & 1:
            files.append(struct.unpack_from('>I', blob, tree + node * index + 10)[0])
    pngs = [m.start() for m in re.finditer(re.escape(b'\x89PNG\r\n\x1a\n'), blob)]
    votes = defaultdict(int)
    for offset in files:
        for png in pngs:
            votes[png - 4 - offset] += 1
    yield from (base for base, _ in sorted(votes.items(), key=lambda item: -item[1])[:5] if base != names_end)


def walk_tree(blob: bytes, tree: int, data: int, names: int, node: int):
    zstd = None

    def name_at(offset):
        length = struct.unpack_from('>H', blob, names + offset)[0]
        end = names + offset + 6 + 2 * length
        if end > len(blob):
            raise ValueError('RCC name outside the file')
        return blob[names + offset + 6:end].decode('utf-16be')

    stack, visited = [(0, '')], set()
    while stack:
        index, prefix = stack.pop()
        if index in visited:
            raise ValueError(f'RCC tree node {index} reached twice')
        visited.add(index)
        base = tree + node * index
        name_offset, flags = struct.unpack_from('>IH', blob, base)
        path = prefix + '/' + name_at(name_offset) if index else ''
        if flags & 2:
            count, first = struct.unpack_from('>II', blob, base + 6)
            if count and tree + node * (first + count) > len(blob):
                raise ValueError(f'RCC directory outside the file: {path}')
            stack.extend((child, path) for child in range(first + count - 1, first - 1, -1))
            continue
        country, language, offset = struct.unpack_from('>HHI', blob, base + 6)
        length = struct.unpack_from('>I', blob, data + offset)[0]
        start = data + offset + 4
        if start + length > len(blob):
            raise ValueError(f'RCC payload outside the file: {path}')
        raw = blob[start:start + length]
        if flags & 1:
            expected = struct.unpack_from('>I', raw)[0]
            if expected > MAX_RESOURCE:
                raise ValueError(f'RCC resource too large: {path}')
            value = zlib.decompressobj().decompress(raw[4:], expected + 1)
            if len(value) != expected:
                raise ValueError(f'RCC zlib size mismatch: {path}')
        elif flags & 4:
            if zstd is None:
                import zstandard
                zstd = zstandard.ZstdDecompressor()
            value = zstd.stream_reader(io.BytesIO(raw)).read(MAX_RESOURCE + 1)
            if len(value) > MAX_RESOURCE:
                raise ValueError(f'RCC resource too large: {path}')
        else:
            value = raw
        yield path or '/', flags, country, language, len(raw), value


def svg_size(data: bytes) -> str:
    head = data[:4096].decode('utf-8', 'replace')
    width = re.search(r'\bwidth="([\d.]+)', head)
    height = re.search(r'\bheight="([\d.]+)', head)
    if width and height:
        return f'{float(width.group(1)):g}×{float(height.group(1)):g}'
    box = re.search(r'viewBox="[\d.\-]+[ ,]+[\d.\-]+[ ,]+([\d.]+)[ ,]+([\d.]+)', head)
    return f'{float(box.group(1)):g}×{float(box.group(2)):g} (viewBox)' if box else 'SVG'


def decode_texture(data: bytes, ext: str):
    """Decode ETC1/ETC2/EAC/ASTC textures after validating dimensions and payload length."""
    import texture2ddecoder
    from PIL import Image
    t2d = texture2ddecoder
    if ext == '.astc':
        if data[:4] != b'\x13\xab\xa1\x5c':
            raise ValueError('ASTC magic')
        bx, by = data[4], data[5]
        width, height = int.from_bytes(data[7:10], 'little'), int.from_bytes(data[10:13], 'little')
        payload, decoder, block = data[16:], (lambda p, w, h: t2d.decode_astc(p, w, h, bx, by)), (bx, by, 16)
    elif ext == '.pkm':
        if data[:4] != b'PKM ':
            raise ValueError('PKM magic')
        kind, width, height = struct.unpack_from('>H4xHH', data, 6)
        decoder, size = {0: (t2d.decode_etc1, 8), 1: (t2d.decode_etc2, 8), 3: (t2d.decode_etc2a8, 16),
                         4: (t2d.decode_etc2a1, 8)}.get(kind, (None, 0))
        if decoder is None:
            raise ValueError(f'PKM type {kind}')
        payload, block = data[16:], (4, 4, size)
    else:
        if data[:12] != KTX1:
            raise ValueError('KTX2 or unknown container')
        fields = struct.unpack_from('<13I', data, 12)
        if fields[0] != 0x04030201:
            raise ValueError('big-endian KTX')
        internal, width, height, kv = fields[4], fields[6], fields[7], fields[12]
        size = struct.unpack_from('<I', data, 64 + kv)[0]
        payload = data[68 + kv:68 + kv + size]
        etc = {0x8D64: (t2d.decode_etc1, 8), 0x9274: (t2d.decode_etc2, 8), 0x9275: (t2d.decode_etc2, 8),
               0x9276: (t2d.decode_etc2a1, 8), 0x9277: (t2d.decode_etc2a1, 8),
               0x9278: (t2d.decode_etc2a8, 16), 0x9279: (t2d.decode_etc2a8, 16)}
        if internal in etc:
            decoder, block = etc[internal][0], (4, 4, etc[internal][1])
        elif 0x93B0 <= internal <= 0x93BD or 0x93D0 <= internal <= 0x93DD:
            bx, by = ASTC_BLOCKS[(internal - 0x93B0) % 0x20]
            decoder, block = (lambda p, w, h: t2d.decode_astc(p, w, h, bx, by)), (bx, by, 16)
        else:
            raise ValueError(f'KTX format 0x{internal:04x}')
    bx, by, per_block = block
    if not (bx and by and 0 < width and 0 < height and width * height <= MAX_PIXELS):
        raise ValueError(f'texture geometry {width}x{height}')
    if len(payload) < -(-width // bx) * -(-height // by) * per_block:
        raise ValueError('truncated texture payload')
    return Image.frombytes('RGBA', (width, height), decoder(payload, width, height), 'raw', 'BGRA')


def mesh_geometry(data: bytes):
    """QtQuick3D mesh v3: 12-byte header, 14-field mesh record, attribute table, names, vertices, indices."""
    if struct.unpack_from('<IH', data, 0) != (0xC8A07F4D, 3):
        raise ValueError('not a QtQuick3D mesh v3')
    fields = struct.unpack_from('<14I', data, 12)
    count, stride, vertex_size, index_type, index_size = fields[1], fields[2], fields[4], fields[5], fields[7]
    if not (0 < count <= 16 and stride and vertex_size % stride == 0 and index_type in (3, 5)):
        raise ValueError('unexpected mesh record')
    entries = [struct.unpack_from('<4I', data, 68 + 16 * i) for i in range(count)]
    cursor, names = 68 + 16 * count + 4, []
    for _ in range(count):
        length = struct.unpack_from('<I', data, cursor)[0]
        names.append(data[cursor + 4:cursor + 4 + length].rstrip(b'\0').decode('ascii', 'replace'))
        cursor += 4 + (length // 4 + 1) * 4  # Qt pads to the next multiple of 4 even when already aligned.
    position = next((e for name, e in zip(names, entries, strict=True) if name == 'attr_pos'), None)
    if position is None or position[1] != 10 or position[2] != 3 or cursor + vertex_size + index_size > len(data):
        raise ValueError('mesh positions not found')
    vertices = vertex_size // stride
    points = [struct.unpack_from('<3f', data, cursor + i * stride + position[3]) for i in range(vertices)]
    width = 4 if index_type == 5 else 2
    flat = struct.unpack_from(f'<{index_size // width}{"I" if width == 4 else "H"}', data, cursor + vertex_size)
    if flat and max(flat) >= vertices:
        raise ValueError('mesh index outside vertices')
    return points, [flat[i:i + 3] for i in range(0, len(flat) - 2, 3)]


def render_mesh(res: Resource, data: bytes):
    """Flat-shaded three-quarter view, painter-sorted; enough to recognise the model."""
    import math
    from PIL import Image, ImageDraw
    points, triangles = mesh_geometry(data)
    yaw, pitch = math.radians(35), math.radians(-25)
    view = []
    for x, y, z in points:
        x, z = x * math.cos(yaw) + z * math.sin(yaw), -x * math.sin(yaw) + z * math.cos(yaw)
        y, z = y * math.cos(pitch) - z * math.sin(pitch), y * math.sin(pitch) + z * math.cos(pitch)
        view.append((x, y, z))
    xs, ys = [p[0] for p in view], [p[1] for p in view]
    size, margin = (960, 480), 24
    scale = min((size[0] - 2 * margin) / ((max(xs) - min(xs)) or 1), (size[1] - 2 * margin) / ((max(ys) - min(ys)) or 1))
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    canvas = Image.new('RGBA', size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    light = (0.35, 0.55, 0.76)
    faces = []
    for a, b, c in triangles:
        (ax, ay, az), (bx, by, bz), (qx, qy, qz) = view[a], view[b], view[c]
        ux, uy, uz, vx, vy, vz = bx - ax, by - ay, bz - az, qx - ax, qy - ay, qz - az
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        norm = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
        shade = int(70 + 170 * abs(nx * light[0] + ny * light[1] + nz * light[2]) / norm)
        faces.append(((az + bz + qz) / 3, [(size[0] / 2 + (px - cx) * scale, size[1] / 2 - (py - cy) * scale)
                                          for px, py, _ in (view[a], view[b], view[c])], shade))
    for _, polygon, shade in sorted(faces, key=lambda face: face[0]):
        draw.polygon(polygon, fill=(int(shade * 0.55), int(shade * 0.75), shade, 255))
    res.dims = f'{len(points)} sommets · {len(triangles)} triangles'
    return canvas


def render_font(res: Resource, data: bytes):
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype(io.BytesIO(data), 46)
    family, style = font.getname()
    samples = [('Arab', 'العربية ٠١٢'), ('Naskh', 'العربية ٠١٢'), ('Hebrew', 'עברית 0123'),
               ('Thai', 'ภาษาไทย 0123'), ('SC', '中文字体 0123'), ('Hans', '中文字体 0123')]
    text = next((sample for key, sample in samples if key.lower() in family.lower()), 'BYD 0123456789 km/h Aa')
    canvas = Image.new('RGBA', (960, 120), (255, 255, 255, 255))
    ImageDraw.Draw(canvas).text((16, 30), text, font=font, fill=(20, 20, 20, 255))
    res.family = family
    res.dims = f'{family} {style}'
    return canvas


def render_image(res: Resource, data: bytes, thumbs: Path) -> None:
    from PIL import Image
    ext = ext_of(res.path)
    try:
        if ext in MESH:
            image = render_mesh(res, data)
        elif ext in FONT:
            image = render_font(res, data)
        elif ext in VECTOR:
            res.dims = svg_size(data)
            name = res.sha[:20] + '.svg'
            (thumbs / name).write_bytes(data)
            res.thumb = name
            return
        elif ext in TEXTURE:
            image = decode_texture(data, ext)
        else:
            image = Image.open(io.BytesIO(data))
            image.load()
        frames = getattr(image, 'n_frames', 1)
        if ext not in MESH | FONT:
            res.dims = f'{image.width}×{image.height}' + (f' · {frames} images' if frames > 1 else '')
        name = res.sha[:20] + '.png'
        target = thumbs / name
        if not target.exists():
            preview = image.convert('RGBA')
            preview.thumbnail(THUMB, Image.LANCZOS)
            preview.save(target, optimize=True)
        res.thumb = name
    except Exception as error:  # Keep the row: an undecodable asset is still a resource to map.
        res.note = f'aperçu indisponible ({type(error).__name__}: {error})'[:160]


def load(paths, out: Path, native_only=()):
    thumbs = out / 'thumbs'
    extracted = out / 'extracted'
    thumbs.mkdir(parents=True, exist_ok=True)
    resources, sources, natives = [], [], []
    for binary in native_only:
        natives.append((binary.name, native_strings(binary.read_bytes())))
    for rcc in paths:
        blob = rcc.read_bytes()
        digest = hashlib.sha256(blob).hexdigest()
        seen, written = defaultdict(int), set()
        count = 0
        embedded = blob[:4] == b'\x7fELF'
        region = []
        for path, flags, country, language, stored, data in (decode_embedded(blob, region) if embedded else decode_rcc(blob)):
            seen[path] += 1
            shown = path if seen[path] == 1 else f'{path}@{language}_{country}'
            parts = [part for part in shown.split('/') if part]
            if not parts or any(part in ('.', '..') or UNSAFE_NAME.search(part) for part in parts):
                raise ValueError(f'unsafe resource name in {rcc.name}: {shown!r}')
            target = extracted.joinpath(rcc.stem, *parts)
            if str(target).casefold() in written:  # NTFS is case-insensitive: never overwrite a sibling.
                target = target.with_name(f'{target.stem}~{len(written)}{target.suffix}')
            written.add(str(target).casefold())
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            ext = ext_of(path)
            kind = ('image' if ext in IMAGES else 'mesh' if ext in MESH else 'font' if ext in FONT
                    else 'code' if ext in CODE else 'other')
            res = Resource(rcc.stem, shown, stored, len(data), flags, hashlib.sha256(data).hexdigest(), kind)
            if kind in VISUAL_KINDS:
                render_image(res, data, thumbs)
            elif kind == 'code':
                res.text = data.decode('utf-8', 'replace')
                res.lines = res.text.splitlines()
                res.comments = comment_spans(res.lines)
                res.masked = [blank_code(line, spans) for line, spans in zip(res.lines, res.comments, strict=True)]
            resources.append(res)
            count += 1
        sources.append({'file': rcc.name, 'bytes': len(blob), 'sha256': digest, 'resources': count,
                        'kind': 'ressources Qt compilées dans le binaire' if embedded else 'RCC'})
        if embedded:
            natives.append((rcc.name, native_strings(blob, region)))
    return resources, sources, natives


def blank_strings(line: str) -> str:
    """Mask string literals and drop a trailing // comment, keeping column positions."""
    masked = STRING.sub(lambda m: ' ' * (m.end() - m.start()), line)
    cut = masked.find('//')
    return masked if cut < 0 else masked[:cut]


def comment_spans(lines):
    """Per line, the (start, end) columns of // and /* */ comments; markers inside string literals are ignored."""
    spans, block = [], False
    for line in lines:
        masked = STRING.sub(lambda m: ' ' * (m.end() - m.start()), line)
        cuts, start = [], 0
        for token in COMMENT_TOKEN.finditer(masked):
            if block:
                if token.group() == '*/':
                    cuts.append((start, token.end()))
                    block = False
            elif token.group() == '//':
                cuts.append((token.start(), len(line)))
                break
            elif token.group() == '/*':
                block, start = True, token.start()
        if block:
            cuts.append((start, len(line)))
        spans.append(cuts)
    return spans


def blank_code(line: str, spans) -> str:
    """Mask string literals and comments, keeping column positions."""
    masked = STRING.sub(lambda m: ' ' * (m.end() - m.start()), line)
    for start, end in spans:
        masked = masked[:start] + ' ' * (end - start) + masked[end:]
    return masked


def enclosing(masked, index: int, column: int):
    """Return the line index opening the innermost block that contains (index, column)."""
    depth = 0
    for i in range(index, -1, -1):
        text = masked[i][:column] if i == index else masked[i]
        for char in reversed(text):
            if char == '}':
                depth += 1
            elif char == '{':
                if depth == 0:
                    return i
                depth -= 1
    return None


def block_lines(masked, start: int):
    """Direct property lines of the block opened at start, with nested blocks folded."""
    if masked[start].count('{') <= masked[start].count('}'):
        return []
    rows, depth = [], 0
    for i in range(start + 1, len(masked)):
        before = depth
        depth += masked[i].count('{') - masked[i].count('}')
        if before == 0:
            rows.append(i)
        if depth < 0:
            break
    return rows


def describe(lines, masked, opener: int):
    text = masked[opener]
    match = ELEMENT.search(text.rstrip()) or re.match(r'\s*([A-Za-z_][\w.]*)\s*(?:on\s+[\w.]+\s*)?\{', text)
    kind = match.group(1) if match else lines[opener].strip()[:40]
    inline = re.search(r'\{\s*id\s*:\s*([A-Za-z_]\w*)', lines[opener])
    ident = inline.group(1) if inline else ''
    for i in [] if ident else block_lines(masked, opener):
        found = re.match(r'\s*id\s*:\s*([A-Za-z_]\w*)', lines[i])
        if found:
            ident = found.group(1)
            break
    return kind + (f'#{ident}' if ident else '')


_SNIPPETS = {}


def snippet(res: Resource, line_no: int, column: int):
    key = (id(res), line_no, column)
    if key not in _SNIPPETS:
        _SNIPPETS[key] = _snippet(res.lines, res.masked, line_no, column)
    return _SNIPPETS[key]


def _snippet(lines, masked, line_no: int, column: int):
    opener = enclosing(masked, line_no, column)
    if opener is None:
        start = max(0, line_no - 3)
        picked = list(range(start, min(len(lines), line_no + 4)))
        chain = ''
    else:
        chain_parts, cursor = [describe(lines, masked, opener)], opener
        while len(chain_parts) < 5:
            parent = enclosing(masked, cursor, len(lines[cursor]) - len(lines[cursor].lstrip()))
            if parent is None:
                break
            chain_parts.append(describe(lines, masked, parent))
            cursor = parent
        chain = ' › '.join(reversed(chain_parts))
        body = block_lines(masked, opener)
        near = [i for i in (line_no - 1, line_no, line_no + 1) if i == line_no or i in body]
        picked = sorted(set([opener] + [i for i in body if i not in near][:MAX_SNIPPET - 4] + near))
    rendered, last = [], None
    for i in picked:
        if last is not None and i != last + 1:
            rendered.append('      ⋯')
        text = html.escape(lines[i].rstrip().replace('\t', '    '))
        number = f'{i + 1:>5} '
        rendered.append(f'<mark>{number}{text}</mark>' if i == line_no else number + text)
        last = i
    return chain, '\n'.join(rendered)


def literal_groups(line: str):
    """Yield (column, display, pattern) for literals joined by '+'; WILD marks parts known only at runtime."""
    found = []
    for match in STRING.finditer(line):
        value = next(group for group in match.groups() if group is not None)
        if match.group(0).startswith('`'):
            value = re.sub(r'\$\{[^}]*\}', WILD, value)
        found.append((match.start(), match.end(), re.sub(r'%\d+', WILD, value)))
    groups = []
    for start, end, value in found:
        gap = line[groups[-1][1]:start] if groups else ''
        if groups and '+' in gap and not re.search(r'[,;:?]', gap):
            groups[-1][1] = end
            groups[-1][2] += ('' if re.fullmatch(r'\s*\+\s*', gap) else WILD) + value
        else:
            groups.append([start, end, value])
    for start, end, pattern in groups:
        if line[max(0, start - 80):start].rstrip().endswith('+'):
            pattern = WILD + pattern
        if line[end:end + 80].lstrip().startswith('+'):
            pattern += WILD
        yield start, pattern.replace(WILD, '*'), pattern


def resolve(pattern: str, code_path: str):
    if pattern.startswith(('http:', 'https:', 'file:', 'image:', 'data:')):
        return None
    if pattern.startswith('qrc:'):
        pattern = '/' + pattern[4:].lstrip('/')
    elif pattern.startswith(':/'):
        pattern = pattern[1:]
    elif not pattern.startswith(('/', WILD)):
        pattern = posixpath.join(posixpath.dirname(code_path), pattern)
    if pattern.startswith('/'):
        trailing = pattern.endswith('/') and len(pattern) > 1
        pattern = posixpath.normpath(pattern) + ('/' if trailing else '')
    return pattern


def meaningful(pattern: str) -> bool:
    core = re.sub(r'\.(png|jpe?g|bmp|gif|webp|svg|ktx|astc|pkm|tga)$', '', pattern.replace(WILD, ''), flags=re.I)
    if len(re.sub(r'[^A-Za-z0-9]', '', core)) < 3:
        return False
    return len(posixpath.basename(core.rstrip('/'))) >= 3 or core.endswith('/') and len(core) > 3


def wildcard_regex(pattern: str):
    """A leading unknown part may span folders; inner unknown parts stay within one path segment."""
    parts = pattern.split(WILD)
    body = re.escape(parts[0])
    for index, part in enumerate(parts[1:], 1):
        body += ('.*' if index == 1 and not parts[0] else '[^/]*') + re.escape(part)
    return re.compile(body)


RANK = {'exact': 1, 'nom de fichier': 1, 'motif': 1, 'préfixe': 1, 'chemin natif': 1, 'variable résolue': 1,
        'nom sans extension': 2, 'chemin corrigé': 2, 'famille de police': 2, 'dossier cité': 3, 'motif approché': 3,
        'nom de fichier (plusieurs dossiers)': 3, 'chaîne native': 4}


def assigned_paths(code) -> dict:
    """Path-like literals assigned to each identifier of a code file (plain literal or ternary right-hand side)."""
    found = defaultdict(set)
    for index, (line, masked) in enumerate(zip(code.lines, code.masked, strict=True)):
        match = ASSIGN.search(masked)
        if not match or '+' in masked[match.end():]:
            continue
        for literal in STRING.finditer(line, match.end()):
            value = next(group for group in literal.groups() if group is not None)
            if '/' in value and not any(start <= literal.start() < end for start, end in code.comments[index]):
                found[match.group(1)].add(value)
    return found


def link_references(resources, shared=(), natives=()) -> list:
    """Give every visual resource its best-ranked code references; record a proof of absence otherwise.

    Rank 1 (exact path, unique name, wildcard, prefix) always wins; lower ranks only fill visuals that
    would otherwise have no reference, so a weaker heuristic never dilutes a precise link. Literals inside
    comments never count as a use. Returns the active citations of visual paths that match no resource.
    """
    visuals = defaultdict(list)
    by_path, by_name, by_dir, stems, families = {}, defaultdict(list), defaultdict(list), defaultdict(list), defaultdict(list)
    for item in (r for r in resources if r.kind in VISUAL_KINDS):
        name = posixpath.basename(item.path)
        visuals[item.rcc].append(item)
        by_path[(item.rcc, item.path)] = item
        by_name[(item.rcc, name)].append(item)
        by_dir[(item.rcc, posixpath.dirname(item.path))].append(item)
        stems[(item.rcc, posixpath.splitext(name)[0])].append(item)
        if item.family:
            families[item.family.lower()].append(item)
    everyone = list(visuals)
    scopes = {rcc: [rcc] + [s for s in (everyone if rcc in shared else shared) if s != rcc] for rcc in everyone}
    candidates = defaultdict(list)
    cache = {}
    missing = []
    variables = {}
    codes = [r for r in resources if r.kind == 'code']

    def add(targets, ref):
        for target in targets:
            candidates[id(target)].append(ref)

    def within(scope, test):
        return next((hits for hits in ([i for i in visuals[s] if test(i)] for s in scope) if hits), [])

    def links(code, pattern, scope):
        key = (code.rcc, pattern)
        if key not in cache:
            cache[key] = classify(pattern, scope, within, by_path, by_name, by_dir)
        return cache[key]

    def resolve_variable(code, index, column, raw, scope) -> bool:
        """`folder + "name.png"`: compose with the path literals assigned to `folder` in the same file."""
        before = VARIABLE_BEFORE.search(code.lines[index][:column])
        if not before:
            return False
        if id(code) not in variables:
            variables[id(code)] = assigned_paths(code)
        values = sorted(variables[id(code)].get(before.group(1).rsplit('.', 1)[-1], ()))
        composed = [pattern for pattern in (resolve(value + raw[1:], code.path) for value in values) if pattern]
        if not composed:
            return False
        for pattern in composed:
            shown = pattern.replace(WILD, '*')
            found = [targets for how, targets in links(code, pattern, scope) if how in ('exact', 'motif')]
            for targets in found:
                add(targets, (code, index, column, shown, 'variable résolue'))
            if not found and ext_of(pattern.replace(WILD, '')) in VISUAL:
                missing.append((code, index, column, shown))
        return True

    for code in codes:
        scope = scopes.get(code.rcc, [code.rcc] + list(shared))
        for index, line in enumerate(code.lines):
            if line.lstrip().startswith('import '):
                continue
            for column, display, raw in literal_groups(line):
                if any(start <= column < end for start, end in code.comments[index]):
                    continue
                site = (code, index, column, display)
                if not raw.strip(WILD):
                    continue
                if raw.startswith(WILD) and resolve_variable(code, index, column, raw, scope):
                    continue
                if WILD not in raw and '/' not in raw and not ext_of(raw) in VISUAL:
                    if raw.lower() in families:
                        add(families[raw.lower()], site + ('famille de police',))
                    found = next((stems[(s, raw)] for s in scope if (s, raw) in stems), []) if len(raw) >= 2 else []
                    add(found, site + ('nom sans extension',))
                    continue
                pattern = resolve(raw, code.path)
                if not pattern:
                    continue
                result = links(code, pattern, scope)
                if not result and ext_of(pattern.replace(WILD, '')) in VISUAL:
                    missing.append(site)
                for how, targets in result:
                    add(targets, site + (how,))
    native_hits, native_missing = native_references(natives, visuals)
    missing += native_missing
    for target, ref in native_hits:
        candidates[id(target)].append(ref)
    irregular = {posixpath.basename(item.path) for items in visuals.values() for item in items
                 if not NAME_TOKEN.fullmatch(posixpath.basename(item.path))}
    mentions = name_mentions(codes, natives, irregular)
    for items in visuals.values():
        for item in items:
            found = candidates.get(id(item), [])
            if found:
                best = min(RANK[ref[4]] for ref in found)
                item.refs = [ref for ref in found if RANK[ref[4]] == best]
            else:
                item.unused = absence_proof(item, len(codes), natives, by_name, everyone, candidates, mentions)
    return missing


def classify(pattern: str, scope, within, by_path, by_name, by_dir):
    """Return [(link kind, targets)] for one resolved literal pattern."""
    links = []
    ext = ext_of(pattern.replace(WILD, ''))
    if WILD in pattern:
        if meaningful(pattern):
            regex = wildcard_regex(pattern)
            hits = within(scope, lambda i: regex.fullmatch(i.path))
            if hits:
                # An unknown leading part followed by a bare name suffix (no folder) only matches by file-name ending.
                loose = pattern.startswith(WILD) and '/' not in pattern.split(WILD)[1]
                return [('motif approché' if loose else 'motif', hits)]
        literal_dirs = [part for part in pattern.split('/')[:-1] if part and WILD not in part]
        last = pattern.rsplit('/', 1)[-1]
        if literal_dirs and last.replace(WILD, ''):
            leaf = wildcard_regex(last)
            hits = within(scope, lambda i: leaf.fullmatch(posixpath.basename(i.path)) and
                          all(part in i.path.split('/') for part in literal_dirs))
            if hits:
                links.append(('motif approché', hits))
        return links
    if ext in VISUAL:
        exact = next((by_path[(s, pattern)] for s in scope if (s, pattern) in by_path), None)
        if exact:
            return [('exact', [exact])]
        name = posixpath.basename(pattern)
        named = next((by_name[(s, name)] for s in scope if (s, name) in by_name), [])
        if len(named) == 1:
            return [('nom de fichier', named)]
        if named:
            wanted = pattern.split('/')[:-1]
            score = lambda i: sum(1 for a, b in zip(reversed(i.path.split('/')[:-1]), reversed(wanted), strict=False) if a == b)
            best = max(score(i) for i in named)
            tied = [i for i in named if score(i) == best]
            return [('chemin corrigé', tied)] if len(tied) == 1 and best else [('nom de fichier (plusieurs dossiers)', named)]
        return []
    folder = pattern.rstrip('/')
    deep = len(folder.strip('/').split('/')) >= 2
    inside = within(scope, lambda i: i.path.startswith(folder + '/') and (deep or posixpath.dirname(i.path) == folder))
    if inside:
        return [('dossier cité', inside)]
    if not pattern.endswith('/') and deep:
        hits = within(scope, lambda i: i.path.startswith(pattern))
        if hits:
            return [('préfixe', hits)]
    return []


def native_strings(blob: bytes, region=None):
    """ASCII and UTF-16LE strings of a native binary, excluding its compiled resource tables."""
    if region:
        blob = blob[:region[0]] + b'\0' * (region[1] - region[0]) + blob[region[1]:]
    found = {m.group().decode() for m in re.finditer(rb'[\x20-\x7e]{4,400}', blob)}
    found |= {m.group().decode('utf-16le') for m in re.finditer(rb'(?:[\x20-\x7e]\x00){4,400}', blob)}
    return found


def native_references(natives, visuals):
    """Visuals cited by native code strings: Qt resource paths (':/', 'qrc:/', %N or %s placeholders) or bare
    file names, as pseudo code resources so they render like QML hits. Also returns cited paths with no resource."""
    everything = [item for items in visuals.values() for item in items]
    paths, names = defaultdict(list), defaultdict(list)
    for item in everything:
        paths[item.path].append(item)
        names[posixpath.basename(item.path)].append(item)
    hits, missing = [], []
    for source, strings in natives:
        lines = sorted(s for s in strings if VISUAL_HINT.search(s))
        if not lines:
            continue
        pseudo = Resource(source, '(chaînes du code natif)', 0, 0, 0, '', 'code', lines=lines,
                          masked=[blank_strings(line) for line in lines])
        for index, line in enumerate(lines):
            cited = False
            for match in NATIVE_PATH.finditer(line):
                raw = re.sub(r'%(?:\d+|[sd])', WILD, match.group(1))
                if ext_of(raw.replace(WILD, '')) not in VISUAL:
                    continue
                cited = True
                regex = wildcard_regex(raw) if WILD in raw else None
                targets = [i for i in everything if regex.fullmatch(i.path)] if regex else paths.get(raw, [])
                for target in targets:
                    hits.append((target, (pseudo, index, match.start(), match.group(), 'chemin natif')))
                if not targets:
                    missing.append((pseudo, index, match.start(), match.group()))
            if not cited:
                for match in NAME_TOKEN.finditer(line):
                    for target in names.get(match.group(), []):
                        hits.append((target, (pseudo, index, match.start(), match.group(), 'chaîne native')))
    return hits, missing


def name_mentions(codes, natives, irregular=()):
    """Every bounded occurrence of a visual file name: (location, enclosing literal, inside a comment)."""
    mentions = defaultdict(list)
    odd = [(name, re.compile(r'(?<![\w\-.%])' + re.escape(name) + r'(?![\w\-])')) for name in sorted(irregular)]
    for code in codes:
        for index, line in enumerate(code.lines):
            found = [(m.group(), m.start()) for m in NAME_TOKEN.finditer(line)]
            found += [(name, m.start()) for name, regex in odd if name in line for m in regex.finditer(line)]
            for name, start in found:
                commented = any(begin <= start < end for begin, end in code.comments[index])
                literal = next((next(g for g in m.groups() if g is not None) for m in STRING.finditer(line)
                                if m.start() < start < m.end()), name)
                mentions[name].append((f'{code.rcc}:{code.path}:{index + 1}', literal, commented))
    for source, strings in natives:
        for text in strings:
            if VISUAL_HINT.search(text):
                names = [m.group() for m in NAME_TOKEN.finditer(text)]
                names += [name for name, regex in odd if name in text and regex.search(text)]
                for name in names:
                    mentions[name].append((f'{source} (chaîne native)', text[:160], False))
    return mentions


def absence_proof(item, code_count, natives, by_name, everyone, candidates, mentions) -> str:
    name = posixpath.basename(item.path)
    scanned = ', '.join(source for source, _ in natives) or 'aucun'
    text = (f'Non utilisé : aucun code actif ne désigne ce fichier (chemin exact, nom, motif, préfixe, dossier ou nom '
            f'sans extension) dans les {code_count} fichiers QML/JS ni dans les chaînes du code natif ({scanned}).')
    sites = mentions.get(name, [])
    active = [site for site in sites if not site[2]]
    commented = [site for site in sites if site[2]]
    if active:
        text += (' Son nom ne figure que dans d’autres chemins : ' +
                 ' ; '.join(f'« {literal} » ({where})' for where, literal, _ in active[:3]) +
                 (f' (+{len(active) - 3})' if len(active) > 3 else '') + '.')
    if commented:
        text += (' Cité seulement dans du code commenté : ' + ', '.join(where for where, _, _ in commented[:4]) +
                 (f' (+{len(commented) - 4})' if len(commented) > 4 else '') + '.')
    if not sites:
        text += f' Le nom {name} n’apparaît nulle part, même en commentaire.'
    twins = [other for source in everyone for other in by_name.get((source, name), [])
             if other is not item and candidates.get(id(other))]
    return text + (f' Homonyme utilisé : qrc:{twins[0].path} ({twins[0].rcc}).' if twins else '')


def group_images(images):
    """Fold numbered series (>= SEQUENCE_MIN) only when no member is cited on its own; members stay visible."""
    buckets = defaultdict(list)
    for image in images:
        match = SEQUENCE.match(posixpath.basename(image.path))
        if match:
            key = (image.rcc, posixpath.dirname(image.path), match['pre'], match['ext'].lower())
            buckets[key].append((int(match['num']), match['num'], image))
    folded, rows = set(), []
    for key, members in buckets.items():
        individual = any(ref[4] not in SERIES_LINKS for item in members for ref in item[2].refs)
        idle = any(not item[2].refs for item in members)  # Unused members keep their own row and proof.
        if len(members) >= SEQUENCE_MIN and not individual and not idle:
            members.sort(key=lambda item: item[0])
            folded.update(id(item[2]) for item in members)
            rows.append(('sequence', key, members))
    rows.extend(('single', None, image) for image in images if id(image) not in folded)
    return sorted(rows, key=lambda row: row[2][0][2].path if row[0] == 'sequence' else row[2].path)


def human(size: int) -> str:
    return f'{size} o' if size < 1024 else f'{size / 1024:.1f} Ko' if size < 1048576 else f'{size / 1048576:.1f} Mo'


def thumb_html(image: Resource, prefix: str, css: str = 't') -> str:
    if image.thumb:
        return f'<img class="{css}" src="{prefix}thumbs/{image.thumb}" alt="{html.escape(posixpath.basename(image.path))}">'
    return f'<div class="na">{html.escape(image.note or "aperçu indisponible")}</div>'


def refs_html(refs, unused='') -> str:
    if not refs:
        return f'<p class="unused">{html.escape(unused or "Non utilisé.")}</p>'
    unique, keys, parts = [], set(), []
    for ref in refs:
        key = (ref[0].rcc, ref[0].path, ref[1])
        if key not in keys:
            keys.add(key)
            unique.append(ref)
    for code, index, column, raw, how in unique[:MAX_REFS]:
        chain, body = snippet(code, index, column)
        crumb = f'<div class="chain">{html.escape(chain)}</div>' if chain else ''
        tag = '' if how == 'exact' else f' <span class="how">{html.escape(how)} « {html.escape(raw)} »</span>'
        parts.append(f'<div class="ref"><code class="loc">{html.escape(code.rcc)}:{html.escape(code.path)}:{index + 1}</code>'
                     f'{tag}{crumb}<pre>{body}</pre></div>')
    if len(unique) > MAX_REFS:
        rest = ', '.join(f'<code>{html.escape(c.path)}:{i + 1}</code>' for c, i, *_ in unique[MAX_REFS:MAX_REFS + 20])
        parts.append(f'<p class="weak">+{len(unique) - MAX_REFS} autres références : {rest}</p>')
    return ''.join(parts)


def row_html(row, prefix: str) -> str:
    kind, key, payload = row
    if kind == 'single':
        image = payload
        meta = f'{html.escape(image.dims or "?")} · {human(image.size)}'
        head = f'<code class="qrc">qrc:{html.escape(image.path)}</code>'
        left = thumb_html(image, prefix) + f'<div class="meta">{meta}</div>'
        return f'<tr><td class="img">{left}</td><td>{head}{refs_html(image.refs, image.unused)}</td></tr>'
    _, folder, pre, ext = key
    first, last = payload[0], payload[-1]
    contiguous = [item[0] for item in payload] == list(range(first[0], first[0] + len(payload)))
    label = f'{folder}/{pre}{{{first[1]}…{last[1]}}}{ext}'
    sizes = sum(item[2].size for item in payload)
    dims = {item[2].dims for item in payload}
    meta = f'{len(payload)} images · {html.escape(" / ".join(sorted(dims))[:60])} · {human(sizes)}'
    refs = [ref for item in payload for ref in item[2].refs]
    idle = [item[2] for item in payload if not item[2].refs]
    numbers = 'numéros continus' if contiguous else 'numéros : ' + ', '.join(item[1] for item in payload)
    head = (f'<code class="qrc">qrc:{html.escape(label)}</code><div class="seq">Série de {len(payload)} images appelées '
            f'par un même chemin dynamique ({html.escape(numbers)}). Toutes sont dans la mosaïque ci-dessous'
            f'{f", dont {len(idle)} non utilisées (marquées en rouge)" if idle and refs else ""}.</div>')
    mosaic = ''.join(f'<figure class="f{" u" if not item[2].refs else ""}">{thumb_html(item[2], prefix, "m")}<figcaption>'
                     f'{html.escape(posixpath.basename(item[2].path))}</figcaption></figure>' for item in payload)
    return (f'<tr><td class="img">{thumb_html(first[2], prefix)}<div class="meta">{meta}</div></td>'
            f'<td>{head}{refs_html(refs, idle[0].unused if idle else "")}</td></tr>'
            f'<tr class="mosaic"><td colspan="2"><div class="mosaic">{mosaic}</div></td></tr>')


CSS = """
@page { size: A4 landscape; margin: 9mm; }
body { font-family: 'Segoe UI', Arial, sans-serif; font-size: 9pt; color: #111; }
h1 { font-size: 17pt; } h2 { font-size: 13pt; border-bottom: 2px solid #333; margin-top: 18px; }
h3 { font-size: 10.5pt; background: #eef2f6; padding: 3px 6px; margin: 14px 0 4px; break-after: avoid; }
table { border-collapse: collapse; width: 100%; table-layout: fixed; }
td, th { border: 1px solid #c8c8c8; vertical-align: top; padding: 4px; }
tr { break-inside: avoid; }
td.img { width: 262px; text-align: center; }
img.t { max-width: 240px; max-height: 120px; margin: 2px; image-rendering: auto;
  background-color: #fff; background-image: linear-gradient(45deg,#ddd 25%,transparent 25%),
  linear-gradient(-45deg,#ddd 25%,transparent 25%), linear-gradient(45deg,transparent 75%,#ddd 75%),
  linear-gradient(-45deg,transparent 75%,#ddd 75%); background-size: 12px 12px;
  background-position: 0 0, 0 6px, 6px -6px, -6px 0; }
.meta { font-size: 7.5pt; color: #555; } .na { font-size: 7.5pt; color: #a33; padding: 8px; }
code.qrc { font-size: 8.5pt; font-weight: 600; word-break: break-all; }
code.loc { font-size: 7.5pt; color: #155; word-break: break-all; }
.chain { font-size: 7.5pt; color: #444; margin: 1px 0; } .how { font-size: 7pt; color: #885; }
.seq { font-size: 7.5pt; color: #555; } .weak { font-size: 7.5pt; color: #777; margin: 3px 0; }
pre { font-family: Consolas, 'Courier New', monospace; font-size: 7.2pt; background: #f7f7f7;
  border-left: 3px solid #9ab; margin: 3px 0 6px; padding: 3px 5px; white-space: pre-wrap; word-break: break-all; }
mark { background: #ffe58a; display: inline-block; width: 100%; }
td code { word-break: break-all; }
tr.mosaic { break-inside: auto; }
div.mosaic { display: flex; flex-wrap: wrap; gap: 3px; }
figure.f { margin: 0; width: 104px; text-align: center; break-inside: avoid; }
img.m { max-width: 100px; max-height: 60px; background: #eee; }
figure.u figcaption { color: #b00; font-weight: 600; }
p.unused { font-size: 7.5pt; color: #8a1c1c; background: #fff3f3; border-left: 3px solid #c55; padding: 3px 5px; }
figcaption { font-size: 6pt; color: #333; word-break: break-all; }
.toc { columns: 2; font-size: 8.5pt; } .small { font-size: 8pt; color: #444; }
"""


def md_code(text: str) -> str:
    return '<code>' + html.escape(text).replace('|', '&#124;') + '</code>'


def build_documents(resources, sources, out: Path, title: str, missing=(), cited_rcc=(), remark='', listing_note=''):
    by_rcc = defaultdict(list)
    for res in resources:
        by_rcc[res.rcc].append(res)
    md, body, toc = [], [], []
    intro = (f'Généré hors ligne depuis {len(sources)} sources Qt d’usine. Chaque élément visuel (image, texture, modèle 3D, '
             'police) est montré à côté du code QML/JS qui le cite (ligne surlignée, élément englobant et sa hiérarchie), ou '
             'avec la preuve qu’aucun code ne le cite. Chemins = espace de ressources Qt (<code>qrc:/…</code>). Types de lien, du '
             'plus sûr au moins sûr : exact, nom de fichier, motif (concaténation, <code>.arg()</code>, gabarit), variable '
             'résolue (dossier affecté à une variable du même fichier + nom littéral), préfixe, nom '
             'sans extension, chemin corrigé (chemin BYD erroné, même nom de fichier), famille de police, dossier cité, motif '
             'approché, nom de fichier (plusieurs dossiers), chaîne native ; un chemin Qt cité par le code natif '
             '(« chemin natif ») vaut un lien exact. Un lien moins sûr n’est utilisé que si aucun lien plus sûr n’existe. Le '
             'code commenté ne compte jamais comme une utilisation. Document privé : code et visuels propriétaires BYD.'
             + (f' {html.escape(remark)}' if remark else ''))
    md += [f'# {html.escape(title)}', '', intro, '', '| Source | Type | Octets | SHA-256 | Ressources |',
           '| --- | --- | ---: | --- | ---: |']
    md += [f'| {md_code(s["file"])} | {s["kind"]} | {s["bytes"]} | {md_code(s["sha256"])} | {s["resources"]} |' for s in sources]
    body += [f'<h1>{html.escape(title)}</h1><p>{intro}</p><table><tr><th>Source</th><th>Type</th><th>Octets</th>'
             '<th>SHA-256</th><th>Ressources</th></tr>']
    body += [f'<tr><td><code>{html.escape(s["file"])}</code></td><td>{s["kind"]}</td><td>{s["bytes"]}</td>'
             f'<td><code>{s["sha256"]}</code></td><td>{s["resources"]}</td></tr>' for s in sources]
    body.append('</table>')
    stats = []
    for rcc, items in by_rcc.items():
        counts = defaultdict(int)
        for item in items:
            counts[item.kind] += 1
        visual = [item for item in items if item.kind in VISUAL_KINDS]
        stats.append((rcc, counts, sum(1 for item in visual if item.refs), sum(1 for item in visual if not item.refs)))
    head = ('Source', 'Images', 'Modèles 3D', 'Polices', 'Cités par du code', 'Non utilisés (prouvé)', 'Code QML/JS', 'Autres')
    md += ['', '| ' + ' | '.join(head) + ' |', '| --- |' + ' ---: |' * (len(head) - 1)]
    md += [f'| {md_code(r)} | {c["image"]} | {c["mesh"]} | {c["font"]} | {l} | {u} | {c["code"]} | {c["other"]} |'
           for r, c, l, u in stats]
    body.append('<table><tr>' + ''.join(f'<th>{h}</th>' for h in head) + '</tr>')
    body += [f'<tr><td><code>{html.escape(r)}</code></td><td>{c["image"]}</td><td>{c["mesh"]}</td><td>{c["font"]}</td>'
             f'<td>{l}</td><td>{u}</td><td>{c["code"]}</td><td>{c["other"]}</td></tr>' for r, c, l, u in stats]
    body.append('</table>')
    if cited_rcc:
        note = ('« fourni » : fichier analysé par cet atlas. ' +
                (f'Présence sur le véhicule : {listing_note}' if listing_note else
                 'Présence sur le véhicule non vérifiée (aucun relevé fourni).'))
        md += ['', '## Fichiers RCC cités par le code natif', '', note, '', '| Chaîne native | État |', '| --- | --- |']
        md += [f'| {md_code(text)} | {status} |' for text, status in cited_rcc]
        body.append(f'<h2>Fichiers RCC cités par le code natif</h2><p>{html.escape(note)}</p><table><tr><th>Chaîne native</th>'
                    '<th style="width:260px">État</th></tr>' +
                    ''.join(f'<tr><td><code>{html.escape(text)}</code></td><td>'
                            f'{status if status == "fourni" else "<b>" + html.escape(status) + "</b>"}</td></tr>'
                            for text, status in cited_rcc) + '</table>')
    if missing:
        rows = sorted({(f'{code.rcc}:{code.path}:{index + 1}', raw) for code, index, _, raw in missing})
        note = ('Chemins d’images cités par du code actif (non commenté) qui ne désignent aucune ressource des sources '
                'fournies : fichier absent, chemin BYD erroné ou partie dynamique non déductible du texte.')
        md += ['', f'## Citations sans ressource ({len(rows)})', '', note, '', '| Code | Chemin cité |', '| --- | --- |']
        md += [f'| {md_code(where)} | {md_code(raw)} |' for where, raw in rows]
        body.append(f'<h2>Citations sans ressource ({len(rows)})</h2><p>{note}</p><table><tr><th>Code</th>'
                    '<th>Chemin cité</th></tr>' + ''.join(f'<tr><td><code>{html.escape(where)}</code></td><td><code>'
                                                          f'{html.escape(raw)}</code></td></tr>' for where, raw in rows)
                    + '</table>')
    sections_md, sections_html = [], []
    for rcc, items in by_rcc.items():
        images = [i for i in items if i.kind in VISUAL_KINDS]
        folders = defaultdict(list)
        for row in group_images(images):
            sample = row[2][0][2] if row[0] == 'sequence' else row[2]
            folders[posixpath.dirname(sample.path)].append(row)
        sections_md += ['', f'## {md_code(rcc)}', '']
        sections_html.append(f'<h2 id="{html.escape(rcc)}">{html.escape(rcc)}</h2>')
        toc.append(f'<li><b>{html.escape(rcc)}</b></li>')
        for folder in sorted(folders):
            anchor = re.sub(r'[^A-Za-z0-9]+', '-', f'{rcc}{folder}').strip('-').lower()
            rows = [row_html(row, '') for row in folders[folder]]
            toc.append(f'<li><a href="#{anchor}">{html.escape(folder)}</a> ({len(rows)})</li>')
            sections_md += [f'### {md_code(folder)} ({len(rows)})', '', '<table>',
                            '<tr><th>Image</th><th>Ressource Qt et code qui l’utilise</th></tr>']
            sections_md += rows
            sections_md += ['</table>', '']
            sections_html.append(f'<h3 id="{anchor}">{html.escape(folder)} <span class="small">({len(rows)})</span></h3>'
                                 '<table><tr><th style="width:262px">Image</th><th>Ressource Qt et code qui l’utilise</th></tr>'
                                 + ''.join(rows) + '</table>')
        code_rows = sorted((i for i in items if i.kind == 'code'), key=lambda r: r.path)
        if code_rows:
            counts = defaultdict(int)
            for image in images:
                for code, *_ in image.refs:
                    counts[(code.rcc, code.path)] += 1
            sections_md += [f'### Fichiers QML/JS de {md_code(rcc)} ({len(code_rows)})', '',
                            '| Fichier | Lignes | Références d’images |', '| --- | ---: | ---: |']
            sections_md += [f'| {md_code(c.path)} | {len(c.lines)} | {counts[(c.rcc, c.path)]} |' for c in code_rows]
            sections_html.append(f'<h3>Fichiers QML/JS de {html.escape(rcc)} ({len(code_rows)})</h3><table>'
                                 '<tr><th>Fichier</th><th>Lignes</th><th>Références d’images</th></tr>' +
                                 ''.join(f'<tr><td><code>{html.escape(c.path)}</code></td><td>{len(c.lines)}</td>'
                                         f'<td>{counts[(c.rcc, c.path)]}</td></tr>' for c in code_rows) + '</table>')
        others = sorted((i for i in items if i.kind == 'other'), key=lambda r: r.path)
        if others:
            sections_md += [f'### Autres ressources de {md_code(rcc)} ({len(others)})', '', '| Ressource | Taille |',
                            '| --- | ---: |']
            sections_md += [f'| {md_code("qrc:" + o.path)} | {human(o.size)} |' for o in others]
            sections_html.append(f'<h3>Autres ressources de {html.escape(rcc)} ({len(others)})</h3><table>'
                                 '<tr><th>Ressource</th><th>Taille</th></tr>' +
                                 ''.join(f'<tr><td><code>qrc:{html.escape(o.path)}</code></td><td>{human(o.size)}</td></tr>'
                                         for o in others) + '</table>')
    md += ['', '## Sommaire', ''] + [f'- {re.sub("<[^>]+>", "", line)}' for line in toc] + sections_md
    page = (f'<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>{html.escape(title)}</title>'
            f'<style>{CSS}</style></head><body>' + ''.join(body) + '<h2>Sommaire</h2><ul class="toc">' + ''.join(toc) +
            '</ul>' + ''.join(sections_html) + '</body></html>')
    (out / 'atlas.md').write_text('\n'.join(md) + '\n', encoding='utf-8')
    (out / 'atlas.html').write_text(page, encoding='utf-8')


def print_pdf(out: Path, browser: Path) -> Path:
    pdf = out / 'atlas.pdf'
    pdf.unlink(missing_ok=True)
    profile = Path(tempfile.mkdtemp(prefix='rcc-atlas-browser-'))
    command = [str(browser), '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
               '--disable-background-networking', '--disable-component-update', '--disable-sync', '--disable-extensions',
               f'--user-data-dir={profile}', '--no-pdf-header-footer', '--run-all-compositor-stages-before-draw',
               '--virtual-time-budget=120000', f'--print-to-pdf={pdf}', (out / 'atlas.html').resolve().as_uri()]
    try:
        # Browser logs can carry enterprise sign-in material: never capture or print them.
        process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            process.wait(timeout=3600)
        except subprocess.TimeoutExpired:
            subprocess.run(['taskkill', '/T', '/F', '/PID', str(process.pid)], capture_output=True)
            raise
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    if not pdf.is_file() or pdf.stat().st_size == 0:
        raise RuntimeError(f'PDF not produced (exit {process.returncode})')
    return pdf


def read_listing(path: Path | None):
    """Absolute paths from a read-only vehicle listing (`ls -1` or `ls -l` output); `#` lines describe its provenance."""
    if path is None:
        return None, ''
    paths, notes = set(), []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line.startswith('#'):
            notes.append(line.lstrip('#').strip())
        elif line and line.split()[-1].startswith('/'):
            paths.add(line.split()[-1])
    return paths, ' '.join(notes) or path.name


def rcc_status(text: str, given: set[str], listing: set[str] | None) -> str:
    if posixpath.basename(text) in given:
        return 'fourni'
    if REGEX_SYNTAX.search(text):
        return 'motif de nom, pas un fichier'
    folder = posixpath.dirname(text)
    # A listing only proves absence for folders it actually covered.
    if listing and text.startswith('/') and any(posixpath.dirname(p) == folder for p in listing):
        return 'présent sur le véhicule, non fourni' if text in listing else 'absent du véhicule'
    return 'non fourni, présence sur le véhicule non vérifiée'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rcc', nargs='+', type=Path, help='Factory RCC files, in display order')
    parser.add_argument('--out', type=Path, required=True, help='Private output directory (created)')
    parser.add_argument('--title', default='Atlas des ressources RCC BYD d’usine')
    parser.add_argument('--pdf', type=Path, help='Edge/Chrome executable used for headless PDF printing')
    parser.add_argument('--native', type=Path, nargs='*', default=[],
                        help='Extra native binaries whose ASCII/UTF-16 strings count as code references')
    parser.add_argument('--remark', default='', help='Sentence appended to the introduction (e.g. a verified limit)')
    parser.add_argument('--vehicle-listing', type=Path,
                        help='Read-only listing of the vehicle .rcc files (e.g. `adb shell ls -l /system/lib64/*.rcc`)')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    resources, sources, natives = load(args.rcc, args.out, args.native)
    shared = [Path(s['file']).stem for s in sources if s['kind'] != 'RCC']
    missing = link_references(resources, shared, natives)
    given = {s['file'] for s in sources}
    listing, listing_note = read_listing(args.vehicle_listing)
    cited_rcc = sorted({(text, rcc_status(text, given, listing)) for _, strings in natives for text in strings
                        if re.search(r'\.rcc\b', text) and len(text) < 200})
    build_documents(resources, sources, args.out, args.title, missing, cited_rcc, args.remark, listing_note)
    visuals = [r for r in resources if r.kind in VISUAL_KINDS]
    summary = {'sources': sources, 'native_strings_scanned': [name for name, _ in natives],
               'resources': len(resources), 'visual_elements': len(visuals),
               'by_kind': {kind: sum(1 for r in visuals if r.kind == kind) for kind in VISUAL_KINDS},
               'with_code_reference': sum(1 for r in visuals if r.refs),
               'proven_unused': sum(1 for r in visuals if not r.refs and r.unused),
               'without_status': sum(1 for r in visuals if not r.refs and not r.unused),
               'without_preview': sum(1 for r in visuals if not r.thumb),
               'links_by_kind': dict(Counter(r.refs[0][4] for r in visuals if r.refs)),
               'code_files': sum(1 for r in resources if r.kind == 'code'),
               'active_citations_without_resource': len({(c.rcc, c.path, i, raw) for c, i, _, raw in missing}),
               'rcc_cited_by_native_not_provided': [text for text, status in cited_rcc if status != 'fourni'],
               'rcc_cited_by_native_status': dict(cited_rcc),
               'vehicle_listing': listing_note or None}
    (args.out / 'manifest.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding='utf-8')
    if args.pdf:
        summary['pdf'] = str(print_pdf(args.out, args.pdf))
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
