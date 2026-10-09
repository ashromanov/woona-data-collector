"""Heart questionnaire contract and additive reference upload checks."""
import asyncio
import copy
import hashlib
import json
import unittest
import uuid
import urllib.request
import urllib.error

from fastapi import HTTPException
from starlette.requests import Request
from jsonschema import ValidationError

from server.app import DATABASE_URL
from server.dashboard import reference_form, require_upload_origin, dashboard_add_reference, dashboard_reference_chunk, dashboard_reference_complete
from server.label_sync import app_tasks
from server.questionnaires import MODELS, empty_sheet, validate_heart
from server.tests import test_api
from server.tests.test_api import DEVICE, SECOND_TOKEN, TOKEN, BASE, canonical_hash, expect_http


def request(method, path, payload=None, headers=None, token=TOKEN):
    if not isinstance(payload, bytes): return test_api.request(method, path, payload, headers, token)
    outgoing = urllib.request.Request(BASE + path, payload, {"Authorization": "Bearer " + token, "Content-Type": "application/offset+octet-stream", **(headers or {})}, method=method)
    with urllib.request.urlopen(outgoing, timeout=10) as response:
        return response.status, {key.lower(): value for key, value in response.headers.items()}, response.read()


def heart_answers():
    schema = json.loads((MODELS / "heart-questionnaire.schema.json").read_text())
    result = {"schemaVersion": 1}
    for key in ("knownHeartCondition", "heartRelevantMedication", "preRecordingState", "actualActivity", "acuteHeartRateFactors", "referenceMethod"):
        rule = schema["properties"][key]
        option = next(item for item in rule.get("items", rule)["oneOf"] if item["properties"]["key"]["const"] == "unknown")
        answer = {name: value["const"] for name, value in option["properties"].items()}
        result[key] = [answer] if key == "acuteHeartRateFactors" else answer
    return result


def heart_manifest():
    dog = empty_sheet("dog")
    dog.update(animalId="heart-" + uuid.uuid4().hex, numberOrName="Тест сердца")
    session = empty_sheet("session")
    session.update(sessionKind="heart", heartQuestionnaire=heart_answers(), plannedActivities=["Активность"], sessionLabel="Сердцебиение", videoRequested=True)
    contents = {"video": ("mp4", b"synthetic-video"), "packet": ("bin", b"packets"), "raw": ("binlog", b"raw"), "packet_timeline": ("bin", b"timeline"), "diagnostic": ("log", b"log"), "sync": ("json", b"{}")}
    artifacts = [{"id": str(uuid.uuid4()), "type": kind, "fileName": f"{kind}.{ext}",
                  "mimeType": "video/mp4" if kind == "video" else "text/plain" if kind == "diagnostic" else "application/json" if kind == "sync" else "application/octet-stream",
                  "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "clientCreatedAtUtc": "2026-10-05T10:00:00Z"}
                 for kind, (ext, data) in contents.items()]
    manifest = {"schemaVersion": 1, "captureDeviceId": DEVICE,
        "dog": {"id": str(uuid.uuid4()), "numberOrName": dog["numberOrName"], "expectedRevision": 0},
        "dogProfileVersion": {"id": str(uuid.uuid4()), "schemaVersion": 2, "validationState": "complete", "questionnaire": dog,
                              "contentSha256": canonical_hash(dog), "clientCreatedAtUtc": "2026-10-05T10:00:00Z"},
        "recording": {"source": "live", "captureStatus": "completed", "startedAtUtc": "2026-10-05T10:00:00Z", "endedAtUtc": "2026-10-05T10:01:00Z",
            "timezone": "UTC", "sessionLabel": session["sessionLabel"], "videoRequested": True, "appVersion": "test",
            "questionnaireSchemaVersion": 2, "questionnaireValidationState": "complete", "sessionQuestionnaire": session},
        "sync": {"schemaVersion": 2, "monotonicClock": "android.elapsedRealtimeNanos", "sessionZeroAtUtc": "2026-10-05T10:00:00Z",
            "sessionZeroWallClockMs": 1791194400000, "sessionZeroMonotonicNs": 1, "sessionZeroUncertaintyNs": 0,
            "cameraClockQuality": "unavailable", "sensorClockQuality": "first_packet_arrival", "overallSyncQuality": "unavailable"},
        "artifacts": artifacts}
    return manifest, {kind: data for kind, (_, data) in contents.items()}


class HeartContractTest(unittest.TestCase):
    def test_required_answers_exact_text_exclusivity_and_reference_conditionals(self):
        answers = heart_answers()
        validate_heart(answers)
        for key in answers:
            invalid = copy.deepcopy(answers)
            del invalid[key]
            with self.assertRaises(ValidationError, msg=key):
                validate_heart(invalid)
        invalid = copy.deepcopy(answers)
        invalid["actualActivity"]["text"] = "выдуманный текст"
        with self.assertRaises(ValidationError): validate_heart(invalid)
        invalid = copy.deepcopy(answers)
        invalid["acuteHeartRateFactors"].append({"key": "pain", "text": "Боль"})
        with self.assertRaises(ValidationError): validate_heart(invalid)
        answers["knownHeartCondition"] = {"key": "yes", "text": "Да"}
        validate_heart(answers)  # optional diagnosis remains optional
        answers["referenceMethod"] = {"key": "ecg", "text": "ЭКГ с временными метками"}
        with self.assertRaises(ValidationError): validate_heart(answers)
        answers["referenceArtifact"] = "ECG-1, UTC 10:00–10:01"
        validate_heart(answers)
        answers["referenceMethod"] = {"key": "bpm_only", "text": "Только число ЧСС, измеренное вручную или прибором"}
        answers.pop("referenceArtifact")
        with self.assertRaises(ValidationError): validate_heart(answers)
        answers["referenceBpm"] = {"bpm": 120, "measuredAtUtc": "2026-10-05T10:00:30Z"}
        validate_heart(answers)
        answers["referenceBpm"]["measuredAtUtc"] = "2026-10-05T10:00:30"
        with self.assertRaises((ValidationError, ValueError)): validate_heart(answers)


class HeartApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        test_api.ApiIntegrationTest.setUpClass()

    def test_post_recording_references_preserve_receipt_and_stay_out_of_label_studio(self):
        manifest, contents = heart_manifest()
        recording = str(uuid.uuid4())
        path = f"/v1/recordings/{recording}"
        incomplete = copy.deepcopy(manifest)
        del incomplete["recording"]["sessionQuestionnaire"]["heartQuestionnaire"]
        expect_http(422, "PUT", path, incomplete)
        request("PUT", path, manifest)
        for artifact in manifest["artifacts"]:
            request("PATCH", f'/v1/artifacts/{artifact["id"]}/content', contents[artifact["type"]], {"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"})
            request("POST", f'/v1/artifacts/{artifact["id"]}/complete', {})
        receipt = json.loads(request("POST", path + "/complete", {})[2])["receiptSha256"]
        data = b"utc,bpm,rr_ms\n2026-10-05T10:00:30Z,120,500\n"
        reference = {"id": str(uuid.uuid4()), "type": "rr", "fileName": "polar-rr.csv", "mimeType": "application/octet-stream",
            "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "clientCreatedAtUtc": "2026-10-05T11:00:00Z",
            "referenceMetadata": {"source": "Polar H10", "startedAtUtc": "2026-10-05T10:00:00Z", "endedAtUtc": "2026-10-05T10:01:00Z", "offsetFromRecordingMs": 0}}
        invalid = copy.deepcopy(reference)
        del invalid["referenceMetadata"]
        expect_http(422, "POST", path + "/references", invalid, token=SECOND_TOKEN)
        request("POST", path + "/references", reference, token=SECOND_TOKEN)
        request("POST", path + "/references", reference, token=SECOND_TOKEN)
        self.assertEqual(receipt, json.loads(request("POST", path + "/complete", {})[2])["receiptSha256"])
        artifact_path = f'/v1/artifacts/{reference["id"]}'
        with self.assertRaises(urllib.error.HTTPError) as denied:
            request("PATCH", artifact_path + "/content", data[:5], {"Upload-Offset": "0"})
        self.assertEqual(403, denied.exception.code)
        denied.exception.close()
        request("PATCH", artifact_path + "/content", data[:5], {"Upload-Offset": "0"}, token=SECOND_TOKEN)
        self.assertEqual("5", request("HEAD", artifact_path + "/content", token=SECOND_TOKEN)[1]["upload-offset"])
        request("PATCH", artifact_path + "/content", data[5:], {"Upload-Offset": "5"}, token=SECOND_TOKEN)
        request("POST", artifact_path + "/complete", {}, token=SECOND_TOKEN)
        self.assertEqual(data, request("GET", artifact_path + "/content")[2])
        restored = json.loads(request("GET", path)[2])
        attached = next(item for item in restored["artifacts"] if item["id"] == reference["id"])
        self.assertEqual(attached["referenceMetadata"]["source"], "Polar H10")
        self.assertEqual("complete", restored["ingestStatus"])
        self.assertEqual(receipt, json.loads(request("POST", path + "/complete", {})[2])["receiptSha256"])
        self.assertNotIn("woona:" + recording, {item["data"]["source_id"] for item in app_tasks(DATABASE_URL)})
        conflict = copy.deepcopy(reference)
        conflict["sha256"] = "0" * 64
        expect_http(409, "POST", path + "/references", conflict, token=SECOND_TOKEN)
        # Dashboard delegates to the same upload handlers; origin checks protect writes.
        self.assertEqual(200, reference_form(uuid.UUID(recording)).status_code)
        def web_request(body=b"", headers=None):
            values = {"host": "testserver", "origin": "http://testserver", "x-woona-upload": "1", "content-length": str(len(body)), **(headers or {})}
            async def receive(): return {"type": "http.request", "body": body, "more_body": False}
            return Request({"type": "http", "method": "POST", "path": "/dashboard", "headers": [(key.encode(), value.encode()) for key, value in values.items()]}, receive)
        with self.assertRaises(HTTPException): require_upload_origin(web_request(headers={"origin": "https://another-site"}))
        require_upload_origin(web_request())
        other = dict(reference, id=str(uuid.uuid4()), fileName="other.csv")
        created = asyncio.run(dashboard_add_reference(uuid.UUID(recording), web_request(json.dumps(other).encode())))
        self.assertEqual(other["id"], created["artifactId"])
        with self.assertRaises(HTTPException) as invalid:
            asyncio.run(dashboard_add_reference(uuid.UUID(recording), web_request(b"{}")))
        self.assertEqual(422, invalid.exception.status_code)
        response = asyncio.run(dashboard_reference_chunk(uuid.UUID(other["id"]), web_request(data, {"upload-offset": "0"})))
        self.assertEqual(204, response.status_code)
        self.assertEqual(other["id"], dashboard_reference_complete(uuid.UUID(other["id"]))["artifactId"])
