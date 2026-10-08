"""Only immutable, allowlisted model data. Never download repository Python code."""
import hashlib
import json
from pathlib import Path

MANIFEST = json.loads(Path(__file__).with_name('model.json').read_text())
MODEL_VERSION = MANIFEST['repository'] + '@' + MANIFEST['revision']


def verify_file(path, metadata):
    if path.stat().st_size != metadata['size']:
        raise ValueError('Model file size mismatch: ' + path.name)
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != metadata['sha256']:
        raise ValueError('Model checksum mismatch: ' + path.name)


def verify_model(directory):
    for name, metadata in MANIFEST['files'].items():
        verify_file(Path(directory) / name, metadata)
