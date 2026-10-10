"""Bound metadata before parsing while preserving binary upload streams."""
import hashlib
import http.client
import json
import unittest
import uuid
from urllib.parse import urlsplit

from server.app import MAX_JSON_BYTES, MetadataBodyLimitMiddleware
from server.tests.test_api import BASE, TOKEN
from server.tests.test_heart import heart_manifest, request


class BodyLimitTest(unittest.IsolatedAsyncioTestCase):
    async def dispatch(self, body, path="/v1/accounts/test", method="PUT", disconnect=False):
        incoming = iter([
            {"type": "http.request", "body": body[:100], "more_body": True},
            {"type": "http.disconnect"} if disconnect else
            {"type": "http.request", "body": body[100:], "more_body": False},
            {"type": "http.disconnect"},
        ])
        received, sent = [], []

        async def receive():
            return next(incoming)

        async def downstream(scope, receive, send):
            while True:
                message = await receive()
                received.append(message)
                if message["type"] == "http.disconnect":
                    break

        async def send(message):
            sent.append(message)

        await MetadataBodyLimitMiddleware(downstream)(
            {"type": "http", "method": method, "path": path, "headers": [],
             "state": {"request_id": "body-limit-test"}}, receive, send)
        return received, sent

    async def test_actual_streamed_bytes_reject_before_downstream(self):
        received, sent = await self.dispatch(b"x" * (MAX_JSON_BYTES + 1))
        self.assertEqual(received, [])
        self.assertEqual(sent[0]["status"], 413)
        self.assertEqual(json.loads(sent[1]["body"])["code"], "json_body_too_large")
        self.assertEqual(json.loads(sent[1]["body"])["requestId"], "body-limit-test")

    async def test_limit_boundary_replay_and_disconnect_are_preserved(self):
        body = b'{"valid":true}' + b" " * (MAX_JSON_BYTES - len(b'{"valid":true}'))
        received, sent = await self.dispatch(body)
        self.assertEqual(sent, [])
        self.assertEqual(b"".join(m.get("body", b"") for m in received), body)
        self.assertEqual(json.loads(body), {"valid": True})
        self.assertEqual(received[-1], {"type": "http.disconnect"})
        received, sent = await self.dispatch(body, disconnect=True)
        self.assertEqual(sent, [])
        self.assertEqual(received, [
            {"type": "http.request", "body": body[:100], "more_body": True},
            {"type": "http.disconnect"},
        ])

    async def test_only_binary_patch_routes_keep_larger_streams(self):
        body = b"x" * (MAX_JSON_BYTES + 1)
        for prefix in ("v1", "dashboard"):
            path = f"/{prefix}/artifacts/{uuid.uuid4()}/content"
            received, sent = await self.dispatch(body, path, "PATCH")
            self.assertEqual(sent, [])
            self.assertEqual(b"".join(m.get("body", b"") for m in received), body)
            received, sent = await self.dispatch(body, path, "POST")
            self.assertEqual(received, [])
            self.assertEqual(sent[0]["status"], 413)


class BodyLimitApiTest(unittest.TestCase):
    def test_oversized_metadata_with_case_chunking_or_wrong_type_is_413(self):
        body = json.dumps({"oversized": "x" * MAX_JSON_BYTES}).encode()
        base = urlsplit(BASE)
        for media_type, chunked, method in (
            ("application/json", False, "PUT"), ("Application/JSON", False, "PUT"),
            ("application/json", True, "PUT"), ("application/vnd.woona+json", True, "PUT"),
            (None, True, "PUT"), ("text/plain", True, "PUT"), ("text/plain", True, "GET"),
        ):
            with self.subTest(media_type=media_type, chunked=chunked, method=method):
                connection = http.client.HTTPConnection(base.hostname, base.port, timeout=10)
                try:
                    headers = {"Authorization": "Bearer " + TOKEN, "X-Request-ID": "body-limit-http"}
                    if media_type:
                        headers["Content-Type"] = media_type
                    data = (body[i:i+65536] for i in range(0, len(body), 65536)) if chunked else body
                    connection.request(method, f"/v1/recordings/{uuid.uuid4()}", data, headers, encode_chunked=chunked)
                    response = connection.getresponse()
                    value = json.loads(response.read())
                    self.assertEqual(response.status, 413)
                    self.assertEqual(value["code"], "json_body_too_large")
                    self.assertEqual(value["requestId"], "body-limit-http")
                    self.assertEqual(response.getheader("X-Request-ID"), "body-limit-http")
                finally:
                    connection.close()
        manifest, _ = heart_manifest()
        valid = json.dumps(manifest).encode()
        valid += b" " * (MAX_JSON_BYTES - len(valid))
        connection = http.client.HTTPConnection(base.hostname, base.port, timeout=10)
        try:
            connection.request("PUT", f"/v1/recordings/{uuid.uuid4()}",
                (valid[i:i+65536] for i in range(0, len(valid), 65536)),
                {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}, encode_chunked=True)
            response = connection.getresponse()
            response.read()
            self.assertEqual(response.status, 200)
        finally:
            connection.close()

    def test_binary_chunk_above_metadata_limit_uploads_intact(self):
        manifest, _ = heart_manifest()
        content = b"binary" * (512 * 1024)
        artifact = next(a for a in manifest["artifacts"] if a["type"] == "packet")
        artifact.update(sizeBytes=len(content), sha256=hashlib.sha256(content).hexdigest())
        request("PUT", f"/v1/recordings/{uuid.uuid4()}", manifest)
        path = f"/v1/artifacts/{artifact['id']}"
        self.assertEqual(request("PATCH", path + "/content", content, {"Upload-Offset": "0"})[0], 204)
        request("POST", path + "/complete", {})
        self.assertEqual(request("GET", path + "/content")[2], content)
