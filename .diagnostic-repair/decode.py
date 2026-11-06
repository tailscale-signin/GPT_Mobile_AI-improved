import base64
import hashlib
from pathlib import Path
import zlib

text = Path('.diagnostic-repair/reconciled.patch.zlib.b64').read_text().strip()
Path('diagnostic-payload.b64').write_text(text)
if hashlib.sha256(text.encode()).hexdigest() != 'efd73a533ff6980b1b35ebe64564888913f676d7938d2b1c7e03c6548e42019e':
    raise SystemExit('Transport digest mismatch: no source was applied')
patch = zlib.decompress(base64.b64decode(text, validate=True))
if hashlib.sha256(patch).hexdigest() != '1afe513cd0d0daa0af85a40f89cfa8f6c3967ff1d941c84c2b07811a65b53e47':
    raise SystemExit('Reviewed patch digest mismatch: no source was applied')
Path('diagnostic-reconciled.patch').write_bytes(patch)
