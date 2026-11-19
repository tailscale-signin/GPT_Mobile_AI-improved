"""Write editable OpenDocument files and SVG plots from a bounded JSON analysis result."""
import io
import json
import re
import xml.etree.ElementTree as ET
import zipfile

ODF = 'urn:oasis:names:tc:opendocument:xmlns:'
NS = {key: ODF + name + ':1.0' for key, name in (
    ('office', 'office'), ('manifest', 'manifest'), ('text', 'text'), ('table', 'table'),
    ('draw', 'drawing'), ('style', 'style'), ('presentation', 'presentation'),
    ('svg', 'svg-compatible'), ('fo', 'xsl-fo-compatible'))}
for prefix, namespace in NS.items():
    ET.register_namespace(prefix, namespace)

KINDS = {'.odt': 'text', '.ods': 'spreadsheet', '.odp': 'presentation'}
FILE_TYPES = [('JSON', '*.json'), ('Document', '*.odt'), ('Spreadsheet', '*.ods'),
              ('Presentation', '*.odp'), ('Bar chart', '*.svg')]


def q(name):
    prefix, local = name.split(':')
    return '{' + NS[prefix] + '}' + local


def element(tag, parent=None, **attrs):
    values = {q(key): str(value) for key, value in attrs.items()}
    return ET.Element(q(tag), values) if parent is None else ET.SubElement(parent, q(tag), values)


def xml(node):
    return ET.tostring(node, encoding='utf-8', xml_declaration=True)


def as_text(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, allow_nan=False)


def cell_rows(data):
    if not isinstance(data, list) or not 1 <= len(data) <= 4096:
        raise ValueError('Use a JSON array of rows or objects for a spreadsheet.')
    if all(isinstance(row, dict) for row in data):
        keys = list(dict.fromkeys(key for row in data for key in row))
        if not 1 <= len(keys) <= 128 or len(keys) * (len(data) + 1) > 16000:
            raise ValueError('Use at most 128 columns and 16000 cells.')
        data = [keys] + [[row.get(key) for key in keys] for row in data]
    if not all(isinstance(row, list) and 1 <= len(row) <= 128 for row in data) or sum(map(len, data)) > 16000:
        raise ValueError('Use up to 16000 cells in rows of 1–128 columns.')
    return data


def spreadsheet(parent, data):
    sheet = element('table:table', parent, **{'table:name': 'Analysis'})
    rows = cell_rows(data)
    element('table:table-column', sheet, **{'table:number-columns-repeated': max(map(len, rows))})
    for values in rows:
        row = element('table:table-row', sheet)
        for value in values:
            attrs = {}
            if isinstance(value, bool):
                attrs = {'office:value-type': 'boolean', 'office:boolean-value': str(value).lower()}
            elif type(value) in (int, float):
                attrs = {'office:value-type': 'float', 'office:value': str(value)}
            elif value is not None:
                attrs = {'office:value-type': 'string'}
            cell = element('table:table-cell', row, **attrs)
            if value is not None:
                element('text:p', cell).text = as_text(value)


def presentation(parent, data, styles):
    slides = data.get('slides') if isinstance(data, dict) else data
    if not isinstance(slides, list) or not 1 <= len(slides) <= 64:
        raise ValueError('Provide 1–64 slides as objects with a title and a bullets array.')
    layout = element('style:page-layout', element('office:automatic-styles', styles), **{'style:name': 'Wide'})
    element('style:page-layout-properties', layout, **{'fo:page-width': '28cm', 'fo:page-height': '15.75cm', 'style:print-orientation': 'landscape'})
    master = element('office:master-styles', styles)
    element('style:master-page', master, **{'style:name': 'Default', 'style:page-layout-name': 'Wide'})
    for number, slide in enumerate(slides, 1):
        if not isinstance(slide, dict) or not isinstance(slide.get('title'), str) or not 1 <= len(slide['title']) <= 180:
            raise ValueError('Each slide needs a title of 1–180 characters.')
        lines = slide.get('bullets', [])
        if not isinstance(lines, list) or len(lines) > 12 or not all(isinstance(line, str) and len(line) <= 400 for line in lines):
            raise ValueError('Use up to 12 short text bullets per slide.')
        page = element('draw:page', parent, **{'draw:name': 'Slide ' + str(number), 'draw:master-page-name': 'Default'})
        for y, height, items in [('1cm', '2.6cm', [slide['title']]), ('4cm', '10.5cm', ['• ' + line for line in lines])]:
            frame = element('draw:frame', page, **{'svg:x': '1.6cm', 'svg:y': y, 'svg:width': '25cm', 'svg:height': height})
            box = element('draw:text-box', frame)
            for line in items:
                element('text:p', box).text = line


def office_file(kind, data):
    content = element('office:document-content', **{'office:version': '1.2'})
    section = element('office:' + kind, element('office:body', content))
    styles = element('office:document-styles', **{'office:version': '1.2'})
    if kind == 'spreadsheet':
        spreadsheet(section, data)
    elif kind == 'presentation':
        presentation(section, data, styles)
    else:
        for line in (data if isinstance(data, str) else json.dumps(data, indent=2, ensure_ascii=False)).splitlines() or ['']:
            element('text:p', section).text = line
    mime = 'application/vnd.oasis.opendocument.' + kind
    manifest = element('manifest:manifest', **{'manifest:version': '1.2'})
    for name, media in [('/', mime), ('content.xml', 'text/xml'), ('styles.xml', 'text/xml')]:
        element('manifest:file-entry', manifest, **{'manifest:full-path': name, 'manifest:media-type': media})
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('mimetype', mime, compress_type=zipfile.ZIP_STORED)
        archive.writestr('content.xml', xml(content))
        archive.writestr('styles.xml', xml(styles))
        archive.writestr('META-INF/manifest.xml', xml(manifest))
    return buffer.getvalue()


def plot(data):
    rows = cell_rows(data)
    if len(rows[0]) == 2 and all(isinstance(v, str) for v in rows[0]):
        rows = rows[1:]
    if not 1 <= len(rows) <= 64 or not all(len(row) == 2 and isinstance(row[0], str) and len(row[0]) <= 36
            and type(row[1]) in (float, int) and abs(row[1]) <= 1e100 for row in rows):
        raise ValueError('A chart takes 1–64 label and number pairs, with an optional header row.')
    extent = max((abs(value) for _, value in rows), default=0) or 1
    lo, hi = min(0, min(value for _, value in rows)) / extent, max(0, max(value for _, value in rows)) / extent
    scale = 360 / (hi - lo or 1)
    zero = 355 - lo * scale
    root = ET.Element('svg', xmlns='http://www.w3.org/2000/svg', width='860', height=str(30 + 29 * len(rows)), role='img')
    ET.SubElement(root, 'title').text = 'Analysis results'
    for i, (label, value) in enumerate(rows):
        y = 12 + 29 * i
        end = zero + value / extent * scale
        ET.SubElement(root, 'text', x='8', y=str(y + 13)).text = label
        ET.SubElement(root, 'rect', x=str(min(zero, end)), y=str(y), width=str(abs(end - zero)), height='17', fill='#247587')
        ET.SubElement(root, 'text', x='730', y=str(y + 13)).text = str(value)
    return xml(root)


def export_result(raw, suffix):
    if len(raw.encode('utf-8')) > 1024 * 1024:
        raise ValueError('Narrow the result to 1 MiB before exporting.')
    data = json.loads(raw)
    json.dumps(data, ensure_ascii=False, allow_nan=False)
    if suffix == '.json':
        return raw.encode('utf-8')
    # Validate decoded strings before constructing XML. Escaped controls in JSON
    # are valid input data but cannot be represented in XML 1.0.
    def check_strings(value):
        if isinstance(value, str) and re.search(r'[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD\U00010000-\U0010FFFF]', value):
            raise ValueError('The result contains characters unsupported by XML.')
        if isinstance(value, (list, dict)):
            for item in value:
                check_strings(item)
                if isinstance(value, dict):
                    check_strings(value[item])
    check_strings(data)
    if suffix == '.svg':
        return plot(data)
    if suffix in KINDS:
        return office_file(KINDS[suffix], data)
    raise ValueError('Choose one of the offered export formats.')
