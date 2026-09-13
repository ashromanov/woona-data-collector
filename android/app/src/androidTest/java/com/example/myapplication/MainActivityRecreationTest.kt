package com.example.myapplication

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.DogQuestionnaire
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLanguagePreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRecreationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun openSessionQuestionnaireAndDraft_surviveActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppLanguagePreferences(context).setSelectedLanguage(AppLanguage.ENGLISH)
        WoonaDatabase(context).use { database ->
            database.saveProfile(completeDogQuestionnaire())
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule
                .onNodeWithText("Upload binary (debug)")
                .performScrollTo()
                .performClick()
            composeRule
                .onNodeWithText("Session label *")
                .performTextInput("Lifecycle draft")

            scenario.recreate()

            composeRule.onNodeWithText("Recording session").assertIsDisplayed()
            composeRule.onNodeWithText("Lifecycle draft").assertIsDisplayed()
        }
    }

    private fun completeDogQuestionnaire() = DogQuestionnaire(
        numberOrName = "Lifecycle dog",
        shelterOrPlace = "Test shelter",
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
}
