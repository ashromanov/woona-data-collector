import asyncio
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

from fastapi import Request
from fastapi import HTTPException
from server import app


class DownloadHeadersTest(unittest.TestCase):
    def test_oversized_range_numbers_are_rejected_without_server_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / 'content.bin').write_bytes(b'abc')
            row = {'storage_status': 'available', 'server_relative_path': 'content.bin',
                   'expected_size_bytes': 3, 'sha256': 'a' * 64, 'mime_type': 'video/mp4',
                   'file_name': 'content.mp4'}
            with patch.object(app, 'STORAGE_ROOT', root), patch.object(app.engine, 'connect', return_value=nullcontext(None)), patch.object(app, 'artifact_row', return_value=row):
                for value in ['bytes=' + '9' * 5000 + '-', 'bytes=0-' + '9' * 5000]:
                    request = Request({'type': 'http', 'headers': [(b'range', value.encode())]})
                    with self.assertRaises(HTTPException) as caught:
                        app.download_artifact('audit', request, 'audit')
                    self.assertEqual(caught.exception.status_code, 416)
                    self.assertEqual(caught.exception.detail['code'], 'invalid_range')

    def test_full_and_range_downloads_escape_names_and_preserve_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / 'content.bin').write_bytes(b'abc')
            row = {'storage_status': 'available', 'server_relative_path': 'content.bin',
                   'expected_size_bytes': 3, 'sha256': 'a' * 64, 'mime_type': 'video/mp4'}
            with patch.object(app, 'STORAGE_ROOT', root), patch.object(app.engine, 'connect', return_value=nullcontext(None)), patch.object(app, 'artifact_row', return_value=row):
                for name in ['ascii.mp4', 'фэйт 1.mp4', 'собака "тест".mp4', 'line\nbreak.mp4']:
                    row['file_name'] = name
                    for ranged in [False, True]:
                        with self.subTest(name=name, ranged=ranged):
                            request = Request({'type': 'http', 'headers': [(b'range', b'bytes=0-0')] if ranged else []})
                            response = app.download_artifact('audit', request, 'audit')
                            header = response.headers['Content-Disposition']
                            header.encode('ascii')
                            self.assertNotIn('\n', header)
                            self.assertEqual(response.status_code, 206 if ranged else 200)
                            self.assertEqual(response.headers['ETag'], '"' + row['sha256'] + '"')
                            if ranged:
                                async def consume():
                                    return b''.join([chunk async for chunk in response.body_iterator])
                                self.assertEqual(asyncio.run(consume()), b'a')
                                self.assertEqual(response.headers['Content-Range'], 'bytes 0-0/3')


if __name__ == '__main__':
    unittest.main()
