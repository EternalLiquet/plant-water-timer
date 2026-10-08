import io
import unittest
from PIL import Image
from fastapi.testclient import TestClient
from app import create_app, MAX_BYTES


def photo(fmt='JPEG', size=(40, 30)):
    output = io.BytesIO()
    Image.new('RGB', size, 'green').save(output, format=fmt)
    return output.getvalue()


class FakeRunner:
    ready = True
    def predict(self, image):
        assert image.mode == 'RGB' and not image.info
        return [{'scientificName': 'Monstera deliciosa', 'confidence': .72},
                {'scientificName': 'Monstera adansonii', 'confidence': .12}]


class IdentificationTest(unittest.TestCase):
    def setUp(self):
        self.runner = FakeRunner()
        self.client = TestClient(create_app(self.runner))

    def test_ranked_uncertain_candidates_and_model_version(self):
        r = self.client.post('/identify', content=photo(), headers={'Content-Type': 'image/jpeg'})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()['candidates'][0]['scientificName'], 'Monstera deliciosa')
        self.assertEqual(r.json()['status'], 'suggestions')
        self.assertTrue(r.json()['manualFallback'])
        self.assertIn('30794da', r.json()['modelVersion'])

    def test_low_confidence_keeps_manual_fallback(self):
        self.runner.predict = lambda image: [{'scientificName': 'Aloe vera', 'confidence': .08}]
        self.assertEqual(self.client.post('/identify', content=photo(), headers={'Content-Type':'image/jpeg'}).json()['status'], 'unknown')

    def test_rejects_invalid_mislabeled_and_oversized_images(self):
        for body, mime, status in [(b'not an image','image/jpeg',422), (photo('PNG'),'image/jpeg',415),
                                   (photo(),'application/json',415), (b'a'*(MAX_BYTES+1),'image/jpeg',413),
                                   (photo(size=(8193,1)),'image/jpeg',413)]:
            with self.subTest(status=status, mime=mime):
                self.assertEqual(self.client.post('/identify',content=body,headers={'Content-Type':mime}).status_code,status)

    def test_no_arbitrary_url_fetch_endpoint(self):
        self.assertEqual(self.client.post('/identify',json={'url':'http://127.0.0.1/secret'}).status_code,415)

    def test_unavailable_model_is_explicit(self):
        self.runner.ready = False
        self.assertEqual(self.client.get('/health').status_code,503)
        self.assertEqual(self.client.post('/identify',content=photo(),headers={'Content-Type':'image/jpeg'}).status_code,503)

    def test_inference_timeout_is_bounded_and_not_a_false_identification(self):
        def timeout(image):
            raise TimeoutError()
        self.runner.predict = timeout
        self.assertEqual(self.client.post('/identify',content=photo(),headers={'Content-Type':'image/jpeg'}).status_code,503)


class ImagePrivacyTest(unittest.TestCase):
    def test_exif_removed_and_pixels_oriented(self):
        from app import decode_image
        output = io.BytesIO()
        exif = Image.Exif()
        exif[274] = 6
        exif[270] = 'private photo description'
        Image.new('RGB', (40, 30)).save(output, format='JPEG', exif=exif)
        result = decode_image(output.getvalue(), 'image/jpeg')
        self.assertEqual(result.size, (30, 40))
        self.assertEqual(result.info, {})
        self.assertEqual(dict(result.getexif()), {})

    def test_animated_png_rejected(self):
        from app import decode_image
        from fastapi import HTTPException
        output = io.BytesIO()
        Image.new('RGB', (20, 20), 'red').save(output, format='PNG', save_all=True,
            append_images=[Image.new('RGB', (20, 20), 'blue')], duration=100)
        with self.assertRaises(HTTPException) as error:
            decode_image(output.getvalue(), 'image/png')
        self.assertEqual(error.exception.status_code, 422)


class BoundedInferenceTest(unittest.TestCase):
    def test_hard_timeout_terminates_worker_and_marks_unavailable(self):
        import multiprocessing
        from unittest.mock import patch
        import time
        from runner import ModelRunner
        context = multiprocessing.get_context('spawn')
        runner = ModelRunner()
        runner.connection, child = context.Pipe()
        runner.process = context.Process(target=time.sleep, args=(60,))
        runner.process.start()
        runner._ready = True
        try:
            with patch('runner.INFERENCE_SECONDS', .05):
                with self.assertRaises(TimeoutError):
                    runner.predict(Image.new('RGB', (2, 2)))
            self.assertFalse(runner.process.is_alive())
            self.assertFalse(runner.ready)
        finally:
            runner.close()
            child.close()

    def test_model_checksum_mismatch_is_rejected(self):
        import tempfile
        from pathlib import Path
        from model_files import verify_file
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.safetensors'
            path.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                verify_file(path, {'size':7, 'sha256':'0'*64})

class ConcurrencyTest(unittest.TestCase):
    def test_second_request_is_rejected_without_queueing_inference(self):
        import concurrent.futures
        import threading
        started, release = threading.Event(), threading.Event()
        runner = FakeRunner()
        predict = runner.predict
        def wait(image):
            started.set()
            release.wait(timeout=5)
            return predict(image)
        runner.predict = wait
        with TestClient(create_app(runner)) as client, concurrent.futures.ThreadPoolExecutor() as pool:
            first = pool.submit(client.post, '/identify', content=photo(), headers={'Content-Type':'image/jpeg'})
            try:
                self.assertTrue(started.wait(timeout=5))
                second = client.post('/identify', content=photo(), headers={'Content-Type':'image/jpeg'})
                self.assertEqual(second.status_code,429)
                self.assertEqual(second.headers['Retry-After'],'2')
            finally:
                release.set()
            self.assertEqual(first.result(timeout=5).status_code,200)


class CancellationTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancelled_request_retains_slot_until_worker_finishes_then_retry_is_unmixed(self):
        import asyncio
        import threading
        from starlette.requests import Request
        from fastapi import HTTPException
        started, release = threading.Event(), threading.Event()
        observed = []
        class PixelRunner:
            ready = True
            def predict(self, image):
                started.set()
                release.wait(timeout=5)
                color = image.getpixel((0, 0))
                observed.append(color)
                return [{'scientificName': str(color), 'confidence': .9}]
        app = create_app(PixelRunner())
        identify = next(route.endpoint for route in app.routes if route.path == '/identify')
        def request(color):
            output = io.BytesIO()
            Image.new('RGB', (2, 2), color).save(output, format='PNG')
            async def receive():
                return {'type':'http.request', 'body':output.getvalue(), 'more_body':False}
            return Request({'type':'http', 'method':'POST', 'path':'/identify',
                'headers':[(b'content-type',b'image/png')]}, receive)
        first = asyncio.create_task(identify(request('red')))
        try:
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            first.cancel()
            await asyncio.sleep(.02)
            # Even repeated cancellation must not release the slot or close worker input.
            first.cancel()
            await asyncio.sleep(.02)
            with self.assertRaises(HTTPException) as error:
                await identify(request('blue'))
            self.assertEqual(error.exception.status_code, 429)
        finally:
            release.set()
            with self.assertRaises(asyncio.CancelledError):
                await first
        result = await identify(request('blue'))
        self.assertEqual(result['candidates'][0]['scientificName'], '(0, 0, 255)')
        self.assertEqual(observed, [(255, 0, 0), (0, 0, 255)])

if __name__ == '__main__':
    unittest.main()
