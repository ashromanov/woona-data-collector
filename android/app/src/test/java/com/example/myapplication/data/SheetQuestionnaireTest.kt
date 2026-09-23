package com.example.myapplication.data

import org.junit.Assert.*
import org.junit.Test

class SheetQuestionnaireTest {
    @Test fun dogIdentityIsSeparateAndUnknownMeasurementsStayOptional() {
        val dog = DogQuestionnaire(schemaVersion = 2, animalId = "финик", numberOrName = "Финик", medications = "нет")
        assertTrue(dog.validate().isValid)
        assertFalse(dog.copy(animalId = "").validate().isValid)
        assertFalse(dog.copy(neckCircumferenceCm = 0.0).validate().isValid)
        assertFalse(dog.copy(weightKg = Double.NaN).validate().isValid)
    }

    @Test fun sheetSessionAllowsMultipleSurfacesAndBottomSensorPosition() {
        val session = SessionQuestionnaire(schemaVersion = 2, sessionLabel = "2", plannedActivities = listOf("Аллюр/движение"),
            surfaces = listOf("Асфальт", "Грунт", "Трава"), sensorPosition = "Снизу на горле", sessionDate = "2026-09-04")
        assertTrue(session.validate().isValid)
        assertFalse(session.copy(surfaces = listOf("Трава", "Трава")).validate().isValid)
        assertFalse(session.copy(sessionDate = "2026-02-30").validate().isValid)
        assertFalse(session.copy(durationMinutes = Double.NaN).validate().isValid)
    }
}
