"""Run against the isolated Compose test API, alongside test_api."""
import json
import unittest
import uuid

from server.questionnaires import empty_sheet
from server.tests.test_api import canonical_hash, expect_http, request


class SheetApiTest(unittest.TestCase):
    def test_external_identity_cannot_create_two_dogs(self):
        dog_id, profile_id = str(uuid.uuid4()), str(uuid.uuid4())
        questionnaire = empty_sheet("dog")
        questionnaire.update(animalId="sheet-api-" + uuid.uuid4().hex, numberOrName="Финик", species="собака", weightKg=27.0)
        payload = {"dog": {"id": dog_id, "numberOrName": "Финик", "expectedRevision": 0},
                   "profileVersion": {"id": profile_id, "schemaVersion": 2, "validationState": "complete", "questionnaire": questionnaire,
                                      "contentSha256": canonical_hash(questionnaire), "clientCreatedAtUtc": "2026-09-21T12:00:00Z"}}
        path = f"/v1/dogs/{dog_id}/profile-versions/{profile_id}"
        self.assertEqual(request("PUT", path, payload)[0], 200)
        # Mobile JSON round trips remove the trailing .0 while retaining the
        # immutable profile's server-provided hash.
        payload["profileVersion"]["questionnaire"]["weightKg"] = 27
        self.assertEqual(request("PUT", path, payload)[0], 200)
        other_dog, other_profile = str(uuid.uuid4()), str(uuid.uuid4())
        payload["dog"]["id"] = other_dog
        payload["profileVersion"]["id"] = other_profile
        payload["profileVersion"]["contentSha256"] = canonical_hash(payload["profileVersion"]["questionnaire"])
        error = expect_http(409, "PUT", f"/v1/dogs/{other_dog}/profile-versions/{other_profile}", payload)
        self.assertEqual(error["code"], "animal_id_already_exists")
        self.assertEqual(error["dogId"], dog_id)
        expect_http(404, "GET", f"/v1/dogs/{other_dog}")
        _, _, body = request("GET", f"/v1/dogs/{dog_id}")
        self.assertIn(questionnaire["animalId"], body.decode())
