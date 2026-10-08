"""Internal, memory-only plant photo classifier. Run with python app.py."""
import asyncio
from contextlib import asynccontextmanager
import io
import warnings
from PIL import Image, ImageOps, UnidentifiedImageError
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from model_files import MODEL_VERSION
from runner import ModelRunner

MAX_BYTES = 5 * 1024 * 1024
MAX_PIXELS = 20_000_000
MAX_SIDE = 8192
UPLOAD_SECONDS = 10
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def decode_image(data, content_type):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as original:
                if original.format not in ('JPEG', 'PNG') or Image.MIME[original.format] != content_type:
                    raise HTTPException(415, 'Use a JPEG or PNG with its matching content type.')
                width, height = original.size
                if width * height > MAX_PIXELS or max(width, height) > MAX_SIDE:
                    raise HTTPException(413, 'Image dimensions exceed the limit.')
                if getattr(original, 'n_frames', 1) != 1:
                    raise HTTPException(422, 'Use a single still image.')
                original.load()
                clean = ImageOps.exif_transpose(original).convert('RGB')
                clean.thumbnail((1024, 1024))
                clean.info.clear()
                return clean
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(413, 'Image dimensions exceed the limit.') from None
    except (UnidentifiedImageError, OSError, ValueError):
        raise HTTPException(422, 'Image could not be decoded.') from None


def create_app(runner=None):
    managed = runner is None
    runner = runner or ModelRunner()
    busy = asyncio.Lock()

    @asynccontextmanager
    async def lifespan(app):
        if managed:
            await asyncio.to_thread(runner.start)
        yield
        if managed:
            runner.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware('http')
    async def no_store(request, call_next):
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    @app.get('/health')
    async def health():
        return JSONResponse({'status': 'ready' if runner.ready else 'unavailable', 'modelVersion': MODEL_VERSION},
                            status_code=200 if runner.ready else 503)

    @app.post('/identify')
    async def identify(request: Request):
        content_type = request.headers.get('content-type', '').split(';')[0].strip().lower()
        if content_type not in ('image/jpeg', 'image/png'):
            raise HTTPException(415, 'Use image/jpeg or image/png, with raw image bytes.')
        if not runner.ready:
            raise HTTPException(503, 'Identification unavailable. Choose a plant manually.')
        if busy.locked():
            raise HTTPException(429, 'Identification busy. Try again shortly.', headers={'Retry-After':'2'})
        async with busy:
            data = bytearray()
            try:
                async with asyncio.timeout(UPLOAD_SECONDS):
                    async for chunk in request.stream():
                        if len(data) + len(chunk) > MAX_BYTES:
                            raise HTTPException(413, 'Image exceeds 5 MiB.')
                        data.extend(chunk)
            except TimeoutError:
                raise HTTPException(408, 'Upload deadline exceeded.') from None
            image = decode_image(data, content_type)
            data.clear()
            inference = asyncio.create_task(asyncio.to_thread(runner.predict, image))
            try:
                try:
                    candidates = await asyncio.shield(inference)
                except asyncio.CancelledError:
                    # to_thread cancellation does not stop the CPU worker. Keep its
                    # image alive and retain the single-flight slot until the bounded
                    # operation really ends, including after repeated cancellation.
                    while not inference.done():
                        try:
                            await asyncio.shield(inference)
                        except asyncio.CancelledError:
                            continue
                        except Exception:
                            break
                    if not inference.cancelled():
                        inference.exception()  # Consume any abandoned worker error.
                    raise
            except (TimeoutError, RuntimeError, EOFError, OSError):
                raise HTTPException(503, 'Identification unavailable. Choose a plant manually.') from None
            finally:
                image.close()
            # This is a conservative UX heuristic, not calibrated open-set detection.
            top = candidates[0]['confidence'] if candidates else 0
            second = candidates[1]['confidence'] if len(candidates) > 1 else 0
            return {'candidates': candidates[:5], 'modelVersion': MODEL_VERSION,
                    'status': 'suggestions' if top >= .5 and top - second >= .1 else 'unknown',
                    'manualFallback': True}

    return app


app = create_app()

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=8765, workers=1, access_log=False,
                limit_concurrency=8, timeout_keep_alive=5)
