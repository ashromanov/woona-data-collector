import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

from pydantic import ValidationError

from server.app import (
    DogPayload,
    RecordingPayload,
    SyncPayload,
    required_artifact_types,
)
from server.tests.test_api import dog_questionnaire, session_questionnaire


ROOT = Path(__file__).parents[2]


class QuestionnaireSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        model_directory = ROOT / "docs" / "target-server-plan" / "models"
        cls.dog_schema = json.loads(
            (model_directory / "dog-questionnaire.schema.json").read_text()
        )
        cls.session_schema = json.loads(
            (model_directory / "session-questionnaire.schema.json").read_text()
        )
        Draft202012Validator.check_schema(cls.dog_schema)
        Draft202012Validator.check_schema(cls.session_schema)

    def test_every_required_dog_field_is_enforced(self):
        validator = Draft202012Validator(self.dog_schema)
        valid = dog_questionnaire()
        self.assertEqual([], list(validator.iter_errors(valid)))
        for field in self.dog_schema["required"]:
            broken = copy.deepcopy(valid)
            broken.pop(field)
            self.assertNotEqual([], list(validator.iter_errors(broken)), field)
        extra = copy.deepcopy(valid)
        extra["unexpected"] = True
        self.assertNotEqual([], list(validator.iter_errors(extra)))

    def test_every_required_session_field_is_enforced(self):
        validator = Draft202012Validator(self.session_schema)
        valid = session_questionnaire()
        self.assertEqual([], list(validator.iter_errors(valid)))
        for field in self.session_schema["required"]:
            broken = copy.deepcopy(valid)
            broken.pop(field)
            self.assertNotEqual([], list(validator.iter_errors(broken)), field)
        extra = copy.deepcopy(valid)
        extra["unexpected"] = True
        self.assertNotEqual([], list(validator.iter_errors(extra)))

    def test_conditionals_reject_stale_or_incompatible_children(self):
        dog_validator = Draft202012Validator(self.dog_schema)
        dog = dog_questionnaire()
        dog["breedName"] = "stale"
        self.assertNotEqual([], list(dog_validator.iter_errors(dog)))

        session_validator = Draft202012Validator(self.session_schema)
        session = session_questionnaire()
        session["location"] = "outdoors"
        session["surface"] = "carpet"
        self.assertNotEqual([], list(session_validator.iter_errors(session)))

    def test_video_is_required_only_when_camera_did_not_fail(self):
        base = {
            "source": "live",
            "captureStatus": "completed",
            "startedAtUtc": "2026-07-30T17:00:00Z",
            "endedAtUtc": "2026-07-30T17:01:00Z",
            "timezone": "UTC",
            "sessionLabel": "camera",
            "videoRequested": True,
            "appVersion": "test",
            "questionnaireSchemaVersion": 1,
            "questionnaireValidationState": "complete",
            "sessionQuestionnaire": session_questionnaire(),
        }
        self.assertIn(
            "video",
            required_artifact_types(RecordingPayload.model_validate(base)),
        )
        base["captureErrorCode"] = "camera_failed"
        self.assertNotIn(
            "video",
            required_artifact_types(RecordingPayload.model_validate(base)),
        )

    def test_ios_clock_and_avfoundation_source_are_accepted(self):
        value = SyncPayload.model_validate(
            {
                "schemaVersion": 2,
                "monotonicClock": "ios.CMClock.hostTime",
                "sessionZeroAtUtc": "2026-08-17T00:00:00Z",
                "sessionZeroWallClockMs": 1786924800000,
                "sessionZeroMonotonicNs": 1,
                "sessionZeroUncertaintyNs": 0,
                "cameraTimestampSource": "avfoundation_session_clock",
                "cameraClockQuality": "hardware_monotonic",
                "sensorClockQuality": "first_packet_arrival",
                "overallSyncQuality": "arrival_aligned",
            }
        )
        self.assertEqual("ios.CMClock.hostTime", value.monotonic_clock)

    def test_whitespace_only_required_text_is_rejected_before_database(self):
        with self.assertRaises(ValidationError):
            DogPayload.model_validate(
                {
                    "id": "10000000-0000-0000-0000-000000000099",
                    "numberOrName": "   ",
                    "expectedRevision": 0,
                }
            )
