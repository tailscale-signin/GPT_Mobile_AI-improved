"""Rebuild checked-in desktop PNGs from app vectors with a local Inkscape CLI.

This is an offline maintainer helper, not a runtime or packaging dependency.
The four reused Android vectors are authoritative. Remaining simple glyphs use
the same 24-unit rounded outline treatment and are native navigation semantics.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEST = Path(__file__).with_name('assets') / 'navigation'
ANDROID = '{http://schemas.android.com/apk/res/android}'
SOURCES = {'overview': 'ic_gateway_server.xml', 'chat': 'ic_conversation_chat_seen.xml',
           'new_prompt': 'ic_new_chat.xml', 'benchmarks': 'ic_chart.xml'}
OUTLINES = {
    'saved': 'M6 3H18V21L12 17L6 21Z',
    'jobs': 'M9 5H21M9 12H21M9 19H21M3 5L4 6L6 3M3 12L4 13L6 10M3 19L4 20L6 17',
    'settings': 'M4 7H10M14 7H20M4 17H14M18 17H20M10 4V10M14 14V20',
}


def svg(name, color):
    if name not in SOURCES:
        return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="{OUTLINES[name]}" fill="none" stroke="{color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    source = ET.parse(ROOT / 'app/src/main/res/drawable' / SOURCES[name]).getroot()
    width, height = source.get(ANDROID + 'viewportWidth'), source.get(ANDROID + 'viewportHeight')
    result = ET.Element('svg', xmlns='http://www.w3.org/2000/svg', viewBox=f'0 0 {width} {height}')
    for original in source.findall('path'):
        values = {'d': original.get(ANDROID + 'pathData')}
        for source_name, target_name in [('fillColor', 'fill'), ('strokeColor', 'stroke')]:
            value = original.get(ANDROID + source_name)
            values[target_name] = 'none' if value in (None, '#00000000', '@android:color/transparent') else color
        for source_name, target_name in [('strokeWidth', 'stroke-width'), ('strokeLineCap', 'stroke-linecap'), ('strokeLineJoin', 'stroke-linejoin')]:
            value = original.get(ANDROID + source_name)
            if value:
                values[target_name] = value
        ET.SubElement(result, 'path', values)
    return ET.tostring(result, encoding='unicode')


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for name in (*SOURCES, *OUTLINES):
        for state, color in [('active', '#00FFDE'), ('muted', '#B8DDE2')]:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / (name + '.svg')
                path.write_text(svg(name, color), encoding='utf-8')
                for size in (24, 36, 48):
                    subprocess.run(['inkscape', str(path), '--export-type=png', '--export-filename=' + str(DEST / f'{name}_{state}_{size}.png'),
                                    '--export-width=' + str(size), '--export-height=' + str(size)], check=True, capture_output=True)
    (DEST / 'sources.json').write_text(json.dumps({'androidSources': SOURCES, 'nativeOutlines': list(OUTLINES),
        'colors': {'active': '#00FFDE', 'muted': '#B8DDE2'}, 'sizes': [24, 36, 48]}, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
