package com.example.myapplication.data

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireValidationTest {
    @Test
    fun completeQuestionnairesValidate() {
        assertTrue(completeDog().validate().isValid)
        assertTrue(completeSession().validate().isValid)
    }

    @Test
    fun parentChoicesRejectStaleChildrenAndExclusiveSigns() {
        val dog = completeDog().copy(
            breedStatus = "unknown",
            breedName = "Must not survive",
            observedSigns = listOf("none", "cough"),
        )
        assertTrue("breedName" in dog.validate().errors)
        assertTrue("observedSigns" in dog.validate().errors)

        val session = completeSession().copy(location = "outdoors", surface = "carpet")
        assertTrue("surface" in session.validate().errors)
    }

    @Test
    fun explicitNotMeasuredUsesNullAndDoesNotRequireTime() {
        val session = completeSession().copy(
            pulseStatus = "not_measured",
            pulseBpm = null,
            measurementAtUtc = null,
        )
        assertFalse("pulse" in session.validate().errors)
        assertNull(session.measurementAtUtc)
    }

    @Test
    fun everyConfiguredActivityAndSurfaceCombinationValidates() {
        ACTIVITY_TYPES.forEach { (activityGroup, activityTypes) ->
            activityTypes.forEach { activityType ->
                SURFACES.forEach { (location, surfaces) ->
                    surfaces.forEach { surface ->
                        val result = completeSession().copy(
                            activityGroup = activityGroup,
                            activityType = activityType,
                            activityDetails = "details".takeIf { activityType in setOf("mixed", "other") },
                            location = location,
                            surface = surface,
                            surfaceDetails = "details".takeIf { surface == "other" },
                        ).validate()
                        assertTrue("$activityGroup/$activityType at $location/$surface: ${result.errors}", result.isValid)
                    }
                }
            }
        }
    }

    @Test
    fun everyConditionalDogBranchAcceptsCompleteData() {
        val variants = listOf(
            completeDog().copy(breedStatus = "purebred", breedName = "Labrador"),
            completeDog().copy(breedStatus = "mixed", breedName = "Mixed", resembles = "Shepherd"),
            completeDog().copy(ageStatus = "known", ageYears = 4, ageSource = "documents"),
            completeDog().copy(ageStatus = "estimated", ageMonths = 8, ageSource = "dental_estimate"),
            completeDog().copy(weightStatus = "measured", weightKg = 18.5),
            completeDog().copy(weightStatus = "estimated", weightKg = 20.0),
            completeDog().copy(bodyConditionStatus = "assessed", bodyConditionScore = 5),
            completeDog().copy(neckCircumferenceStatus = "measured", neckCircumferenceCm = 42.0),
            completeDog().copy(shavedAreasStatus = "present", shavedAreasDetails = "Left shoulder"),
            completeDog().copy(observedSigns = listOf("cough", "distress")),
            completeDog().copy(diagnosesStatus = "yes", diagnosesDetails = "Known diagnosis"),
            completeDog().copy(housing = "other", housingDetails = "Foster facility"),
            completeDog().copy(walksStatus = "known", walksDescription = "Twice daily"),
            completeDog().copy(notesStatus = "provided", notes = "Calm dog"),
        )

        variants.forEachIndexed { index, questionnaire ->
            val result = questionnaire.validate()
            assertTrue("Dog branch $index: ${result.errors}", result.isValid)
        }
    }

    @Test
    fun everyConditionalSessionBranchAcceptsCompleteData() {
        val variants = listOf(
            completeSession().copy(airTemperatureStatus = "measured", airTemperatureC = 21.5),
            completeSession().copy(sensorPosition = "other", sensorPositionDetails = "Harness"),
            completeSession().copy(preMeasurementState = "other", preMeasurementStateDetails = "Transport"),
            completeSession().copy(respirationStatus = "measured", respirationPerMinute = 24),
            completeSession().copy(bodyTemperatureStatus = "measured", bodyTemperatureC = 38.4),
            completeSession().copy(
                pulseStatus = "not_measured",
                pulseBpm = null,
                measurementAtUtc = null,
            ),
        )

        variants.forEachIndexed { index, questionnaire ->
            val result = questionnaire.validate()
            assertTrue("Session branch $index: ${result.errors}", result.isValid)
        }
    }

}

private fun completeDog() = DogQuestionnaire(
    numberOrName = "Rex",
    shelterOrPlace = "Shelter 1",
    breedStatus = "unknown",
    size = "medium",
    ageStatus = "unknown",
    ageSource = "unknown",
    sex = "unknown",
    sterilizationStatus = "unknown",
    weightStatus = "unknown",
    bodyConditionStatus = "unable",
    muscleMass = "unable",
    neckCircumferenceStatus = "not_measured",
    coatLength = "unknown",
    undercoat = "unknown",
    shavedAreasStatus = "unknown",
    observedSigns = listOf("none"),
    diagnosesStatus = "unknown",
    housing = "unknown",
    walksStatus = "unknown",
    cohabitants = "unknown",
    shelterPermission = "unknown",
    notesStatus = "none",
)

private fun completeSession() = SessionQuestionnaire(
    sessionLabel = "Baseline",
    operatorName = "Operator",
    activityGroup = "stationary",
    activityType = "rest",
    location = "indoors",
    surface = "concrete",
    airTemperatureStatus = "not_measured",
    sensorPosition = "dorsal_neck",
    collarTightness = "snug",
    preMeasurementState = "rest",
    pulseStatus = "measured",
    pulseBpm = 72,
    respirationStatus = "not_measured",
    bodyTemperatureStatus = "not_measured",
    measurementAtUtc = Instant.parse("2026-07-30T10:00:00Z").toString(),
    videoRequested = true,
)
