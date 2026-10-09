package com.example.myapplication.data

import org.junit.Assert.*
import org.junit.Test

class HeartQuestionnaireTest {
    private fun unknown() = HeartQuestionnaire(
        answers = HEART_OPTIONS.keys.filter { it != "acuteHeartRateFactors" }.associateWith { "unknown" },
        acuteHeartRateFactors = listOf("unknown"),
    )

    @Test fun requiredUnknownExclusiveFactorsAndConditionalReference() {
        val valid = unknown()
        assertTrue(valid.validate().isValid)
        assertFalse(HeartQuestionnaire().validate().isValid)
        assertFalse(valid.copy(acuteHeartRateFactors = listOf("unknown", "pain")).validate().isValid)
        assertFalse(valid.copy(acuteHeartRateFactors = listOf("none", "stress")).validate().isValid)
        assertTrue(valid.copy(acuteHeartRateFactors = listOf("pain", "stress", "overheating")).validate().isValid)
        val ecg = valid.copy(answers = valid.answers + ("referenceMethod" to "ecg"))
        assertFalse(ecg.validate().isValid)
        assertTrue(ecg.copy(referenceArtifact = "ECG-1 at 2026-10-05T12:00:00Z").validate().isValid)
        val bpm = valid.copy(answers = valid.answers + ("referenceMethod" to "bpm_only"), referenceBpm = 120.0, referenceMeasuredAtUtc = "2026-10-05T12:00:00Z")
        assertTrue(bpm.validate().isValid)
        assertFalse(bpm.copy(referenceBpm = Double.NaN).validate().isValid)
        assertFalse(bpm.copy(referenceMeasuredAtUtc = "12:00").validate().isValid)
        assertFalse(SessionQuestionnaire(sessionKind = "heart").readyForSync())
        assertTrue(SessionQuestionnaire(sessionKind = "heart", heartQuestionnaire = valid).readyForSync())
        assertFalse(ReferenceMetadata("Polar", "2026-10-05T12:00:00Z", "2026-10-05T11:00:00Z").validate().isValid)
    }
}
