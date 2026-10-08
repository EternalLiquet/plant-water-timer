"""Opt-in real-model gate. Synthetic input checks execution, never accuracy."""
import argparse
import json
import time
from pathlib import Path
from PIL import Image
from app import decode_image
from model_files import MODEL_VERSION
from runner import ModelRunner


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', type=Path, help='Optional licensed, local JPEG/PNG fixture; never a URL.')
    args = parser.parse_args()
    runner = ModelRunner()
    runner.start()
    try:
        if not runner.ready:
            raise SystemExit('BLOCKED: verified local model files missing or model failed to load.')
        started = time.monotonic()
        candidates = runner.predict(Image.new('RGB', (224, 224), 'green'))
        assert len(candidates) == 5
        assert all(0 <= item['confidence'] <= 1 for item in candidates)
        assert all(candidates[i]['confidence'] >= candidates[i+1]['confidence'] for i in range(4))
        print(json.dumps({'gate':'synthetic_execution_only', 'modelVersion':MODEL_VERSION,
                          'seconds':round(time.monotonic()-started,3), 'passed':True}))
        if args.fixture:
            with args.fixture.open('rb') as stream:
                from app import MAX_BYTES
                data = stream.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES:
                raise SystemExit('Fixture exceeds 5 MiB.')
            with Image.open(args.fixture) as original:
                mime = Image.MIME.get(original.format, '')
            image = decode_image(data, mime)
            try:
                print(json.dumps({'gate':'single_fixture_qualitative_only', 'candidates':runner.predict(image)}))
            finally:
                image.close()
    finally:
        runner.close()


if __name__ == '__main__':
    main()
