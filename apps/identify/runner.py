"""One CPU-only inference worker; kill it on deadline rather than queueing work."""
import multiprocessing
import os
from pathlib import Path
from model_files import verify_model

INFERENCE_SECONDS = 15


def worker(connection, directory):
    # Local files only, no telemetry, no model-supplied executable code or pickle.
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    try:
        verify_model(directory)
        import torch
        from PIL import Image
        from transformers import ViTImageProcessor, ViTForImageClassification
        torch.set_num_threads(2)
        processor = ViTImageProcessor.from_pretrained(directory, local_files_only=True)
        model = ViTForImageClassification.from_pretrained(
            directory, local_files_only=True, use_safetensors=True).eval().to('cpu')
        if len(model.config.id2label) != 14829:
            raise ValueError('Unexpected model labels')
        connection.send(('ready', None))
        while True:
            size, pixels = connection.recv()
            image = Image.frombytes('RGB', size, pixels)
            with torch.inference_mode():
                probabilities = model(**processor(images=image, return_tensors='pt')).logits.softmax(dim=-1)[0]
                scores, indices = probabilities.topk(5)
            connection.send(('result', [
                {'scientificName': model.config.id2label[index.item()], 'confidence': float(score)}
                for score, index in zip(scores, indices)
            ]))
    except (EOFError, BrokenPipeError):
        pass
    except Exception:
        # Do not log image data, model input, or exception strings containing paths.
        try:
            connection.send(('error', None))
        except (EOFError, BrokenPipeError):
            pass
    finally:
        connection.close()


class ModelRunner:
    def __init__(self):
        self.process = None
        self.connection = None
        self._ready = False

    @property
    def ready(self):
        return self._ready and self.process is not None and self.process.is_alive()

    def start(self):
        context = multiprocessing.get_context('spawn')
        self.connection, child = context.Pipe()
        directory = str(Path(os.environ.get('IDENTIFY_MODEL_DIR', Path(__file__).with_name('model'))).resolve())
        self.process = context.Process(target=worker, args=(child, directory), daemon=True)
        self.process.start()
        child.close()
        try:
            if self.connection.poll(120):
                self._ready = self.connection.recv()[0] == 'ready'
        except (EOFError, OSError):
            self._ready = False
        if not self._ready:
            self.close()

    def predict(self, image):
        if not self.ready:
            raise RuntimeError('Model unavailable')
        try:
            self.connection.send((image.size, image.tobytes()))
            if not self.connection.poll(INFERENCE_SECONDS):
                raise TimeoutError('Inference deadline exceeded')
            kind, result = self.connection.recv()
            if kind != 'result':
                raise RuntimeError('Inference unavailable')
            return result
        except (EOFError, BrokenPipeError, OSError, TimeoutError, RuntimeError):
            self.close()
            raise

    def close(self):
        self._ready = False
        if self.process is not None:
            self.process.terminate()
            self.process.join(timeout=2)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=2)
        if self.connection is not None:
            self.connection.close()
