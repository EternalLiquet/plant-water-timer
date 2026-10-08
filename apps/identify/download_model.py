"""Explicit one-time setup; runtime inference never connects to Hugging Face."""
import argparse
from pathlib import Path
import urllib.request
from model_files import MANIFEST, verify_file, verify_model


def download(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, metadata in MANIFEST['files'].items():
        destination = directory / name
        if destination.exists():
            verify_file(destination, metadata)
            continue
        url = f"https://huggingface.co/{MANIFEST['repository']}/resolve/{MANIFEST['revision']}/{name}"
        temporary = directory / (name + '.partial')
        try:
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as out:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > metadata['size']:
                        raise ValueError('Unexpected model download size')
                    out.write(chunk)
            verify_file(temporary, metadata)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    verify_model(directory)
    print('Verified pinned model files in', directory)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path(__file__).with_name('model'))
    download(parser.parse_args().directory)
