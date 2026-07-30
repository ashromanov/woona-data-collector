package com.example.myapplication.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.data.DogProfile
import com.example.myapplication.data.DogQuestionnaire
import com.example.myapplication.data.Recording
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.feature.device.VideoCaptureState
import com.example.myapplication.localization.AppLanguage
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable
fun ProfilesOverview(
    profiles: List<DogProfile>,
    selectedProfileId: String?,
    recordings: List<Recording>,
    language: AppLanguage,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onEditProfile: (DogProfile) -> Unit,
    onShareRecording: (String) -> Unit,
    videoState: VideoCaptureState,
    videoOffsetMillis: Double?,
    onVideoAction: () -> Unit,
) {
    val selected = profiles.firstOrNull { it.id == selectedProfileId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr(language, "Active dog", "Активная собака"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(profile.id) },
                ) {
                    RadioButton(
                        selected = profile.id == selectedProfileId,
                        onClick = { onSelectProfile(profile.id) },
                    )
                    Text(
                        text = profile.numberOrName,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateProfile) {
                    Text(tr(language, "New dog", "Новая собака"))
                }
                OutlinedButton(
                    onClick = { selected?.let(onEditProfile) },
                    enabled = selected != null,
                ) {
                    Text(tr(language, "Edit", "Изменить"))
                }
            }
        }
    }

    if (videoState != VideoCaptureState.IDLE) {
        VideoRecordingCard(
            state = videoState,
            offsetMillis = videoOffsetMillis,
            language = language,
            onAction = onVideoAction,
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = tr(language, "Last 10 recordings", "Последние 10 записей"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (recordings.isEmpty()) {
                Text(
                    text = tr(language, "No recordings for this dog", "У этой собаки пока нет записей"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recordings.forEachIndexed { index, recording ->
                if (index > 0) HorizontalDivider()
                val fileNames = recording.artifacts.map { it.relativePath.substringAfterLast('/') }
                Text(
                    text = recording.startedAtUtc.replace('T', ' ').take(19),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${recording.source.value} · ${recording.status.value}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = tr(
                        language,
                        "Files: ${fileNames.size}${fileNames.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") ?: ""}",
                        "Файлы: ${fileNames.size}${fileNames.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") ?: ""}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onShareRecording(recording.id) },
                    enabled = fileNames.isNotEmpty(),
                ) {
                    Text(tr(language, "Share all", "Поделиться всеми"))
                }
            }
        }
    }
}

@Composable
private fun VideoRecordingCard(
    state: VideoCaptureState,
    offsetMillis: Double?,
    language: AppLanguage,
    onAction: () -> Unit,
) {
    val status = when (state) {
        VideoCaptureState.WAITING_FOR_SENSOR ->
            tr(language, "Available when the sensor starts", "Доступно после запуска датчика")
        VideoCaptureState.READY ->
            tr(language, "Sensor is recording · video is optional", "Датчик пишет · видео необязательно")
        VideoCaptureState.STARTING ->
            tr(language, "Starting camera…", "Запуск камеры…")
        VideoCaptureState.RECORDING ->
            tr(language, "Video is recording", "Видео записывается")
        VideoCaptureState.STOPPING ->
            tr(language, "Finalizing video…", "Сохранение видео…")
        VideoCaptureState.FINISHED ->
            tr(language, "Video saved with synchronization metadata", "Видео сохранено с метаданными синхронизации")
        VideoCaptureState.FAILED ->
            tr(language, "Video is not recording", "Видео не записывается")
        VideoCaptureState.IDLE -> ""
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr(language, "Session video", "Видео сессии"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            offsetMillis?.let { offset ->
                Text(
                    text = tr(
                        language,
                        "First frame offset from sensor start: ${"%.3f".format(offset)} ms",
                        "Смещение первого кадра от запуска датчика: ${"%.3f".format(offset)} мс",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state !in setOf(VideoCaptureState.FINISHED, VideoCaptureState.STOPPING)) {
                Button(
                    onClick = onAction,
                    enabled = state != VideoCaptureState.WAITING_FOR_SENSOR,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state in setOf(VideoCaptureState.STARTING, VideoCaptureState.RECORDING)) {
                            tr(language, "Stop video", "Остановить видео")
                        } else {
                            tr(language, "Start video", "Начать видео")
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DogQuestionnaireDialog(
    initial: DogQuestionnaire?,
    required: Boolean,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (DogQuestionnaire) -> Unit,
) {
    val values = remember(initial) { mutableStateMapOf<String, String>().apply { putDog(initial) } }
    var showRequiredError by remember { mutableStateOf(false) }
    QuestionnaireDialog(
        title = tr(language, "Dog profile", "Карточка собаки"),
        language = language,
        canDismiss = !required,
        onDismiss = onDismiss,
        onSave = {
            val name = values["numberOrName"].orEmpty().trim()
            if (name.isEmpty()) {
                showRequiredError = true
            } else {
                onSave(values.toDogQuestionnaire(name))
            }
        },
    ) {
        SectionTitle(tr(language, "Who", "Кто"))
        TextField(
            values,
            "numberOrName",
            tr(language, "Number or name *", "Номер или кличка *"),
            isError = showRequiredError,
        )
        TextField(values, "shelterOrPlace", tr(language, "Shelter or place", "Приют или место"))
        TextField(values, "breed", tr(language, "Breed", "Порода"))
        TextField(values, "resembles", tr(language, "If mixed, resembles", "Если метис, на кого похож"))
        ChoiceField(
            values,
            "size",
            tr(language, "Size", "Размер"),
            options(
                language,
                "small" to ("Small" to "Мелкая"),
                "medium" to ("Medium" to "Средняя"),
                "large" to ("Large" to "Крупная"),
                "giant" to ("Giant" to "Гигантская"),
            ),
        )
        TextField(values, "ageYears", tr(language, "Age, years", "Возраст, лет"), KeyboardType.Number)
        TextField(values, "ageMonths", tr(language, "Additional months", "И месяцев"), KeyboardType.Number)
        ChoiceField(
            values,
            "ageSource",
            tr(language, "Age source", "Откуда известен возраст"),
            options(
                language,
                "documents" to ("Documents" to "Документы"),
                "shelter" to ("Shelter report" to "Со слов приюта"),
                "dental" to ("Dental estimate" to "Оценка по зубам"),
            ),
        )
        ChoiceField(
            values,
            "sex",
            tr(language, "Sex", "Пол"),
            options(
                language,
                "male" to ("Male" to "Самец"),
                "female" to ("Female" to "Самка"),
                "unknown" to ("Unknown" to "Неизвестно"),
            ),
        )
        ChoiceField(
            values,
            "sterilized",
            tr(language, "Sterilized", "Стерилизация"),
            yesNoUnknown(language),
        )

        SectionTitle(tr(language, "Body", "Тело"))
        TextField(values, "weightKg", tr(language, "Weight, kg", "Вес, кг"), KeyboardType.Decimal)
        ChoiceField(
            values,
            "weightSource",
            tr(language, "Weight source", "Как получен вес"),
            options(
                language,
                "weighed" to ("Weighed" to "Взвешен"),
                "estimated" to ("Estimated" to "На глаз"),
            ),
        )
        ChoiceField(
            values,
            "bodyConditionScore",
            tr(language, "Body condition score", "Оценка упитанности"),
            (1..9).map { it.toString() to it.toString() } +
                ("unable" to tr(language, "Unable to assess", "Не удалось оценить")),
        )
        ChoiceField(
            values,
            "muscleMass",
            tr(language, "Muscle mass", "Мышечная масса"),
            options(
                language,
                "normal" to ("Normal" to "Норма"),
                "mild_loss" to ("Mild loss" to "Лёгкая потеря"),
                "moderate_loss" to ("Moderate loss" to "Умеренная потеря"),
                "severe_loss" to ("Severe loss" to "Выраженная потеря"),
                "unable" to ("Unable to assess" to "Не удалось оценить"),
            ),
        )
        TextField(values, "neckCircumferenceCm", tr(language, "Neck circumference, cm", "Обхват шеи, см"), KeyboardType.Decimal)
        ChoiceField(
            values,
            "coatLength",
            tr(language, "Coat length", "Длина шерсти"),
            options(
                language,
                "short" to ("Short" to "Короткая"),
                "medium" to ("Medium" to "Средняя"),
                "long" to ("Long" to "Длинная"),
            ),
        )
        ChoiceField(
            values,
            "undercoat",
            tr(language, "Undercoat", "Подшёрсток"),
            options(
                language,
                "none" to ("None" to "Нет"),
                "moderate" to ("Moderate" to "Умеренный"),
                "dense" to ("Dense" to "Плотный"),
            ),
        )
        TextField(values, "shavedAreas", tr(language, "Shaved areas", "Выстриженные участки"))

        SectionTitle(tr(language, "Condition today", "Состояние сегодня"))
        MultiChoiceField(
            values,
            "observedSigns",
            tr(language, "Visible signs now", "Что видно сейчас"),
            options(
                language,
                "labored_breathing" to ("Labored breathing at rest" to "Затруднённое дыхание в покое"),
                "fainting" to ("Fainting or sudden weakness" to "Обморок или резкая слабость"),
                "seizures" to ("Seizures" to "Судороги"),
                "cannot_urinate" to ("Cannot urinate" to "Не может помочиться"),
                "limb_weakness" to ("Limb weakness" to "Слабость конечностей"),
                "jaundice" to ("Yellow gums or eyes" to "Желтушность дёсен или глаз"),
                "vomiting_or_no_appetite" to ("Vomiting or no appetite" to "Рвота или отказ от еды"),
                "stool_changes" to ("Stool changes" to "Изменения стула"),
                "cough" to ("Cough" to "Кашель"),
                "pain_or_lameness" to ("Pain or lameness" to "Боль или хромота"),
                "thirst_changes" to ("Thirst or urination changes" to "Изменение жажды или мочеиспускания"),
                "distress" to ("Distress, shaking, vocalization" to "Беспокойство, дрожь, вокализация"),
            ),
        )
        ChoiceField(values, "diagnosesPresent", tr(language, "Veterinary diagnoses", "Диагнозы от врача"), yesNoUnknown(language))
        TextField(values, "diagnosesDetails", tr(language, "Diagnoses and medication", "Диагнозы и препараты"), singleLine = false)

        SectionTitle(tr(language, "Housing and permission", "Содержание и разрешение"))
        ChoiceField(
            values,
            "housing",
            tr(language, "Housing", "Где живёт"),
            options(
                language,
                "enclosure" to ("Enclosure" to "Вольер"),
                "room" to ("Room" to "Комната"),
                "free_range" to ("Free range" to "Свободный выгул"),
            ),
        )
        TextField(values, "walks", tr(language, "Daily walks", "Сколько и как гуляет"))
        ChoiceField(
            values,
            "cohabitants",
            tr(language, "Lives with", "С кем живёт"),
            options(
                language,
                "alone" to ("Alone" to "Один"),
                "other_animals" to ("Other animals" to "С другими животными"),
            ),
        )
        ChoiceField(values, "shelterPermission", tr(language, "Written shelter permission", "Письменное разрешение приюта"), yesNo(language))
        TextField(values, "notes", tr(language, "Notes", "Примечание"), singleLine = false)
    }
}

@Composable
fun SessionQuestionnaireDialog(
    initial: SessionQuestionnaire = SessionQuestionnaire(),
    language: AppLanguage,
    allowSkip: Boolean,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onSave: (SessionQuestionnaire) -> Unit,
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply { putSession(initial) }
    }
    var invalidDateTime by remember { mutableStateOf(false) }
    QuestionnaireDialog(
        title = tr(language, "Recording session", "Карточка сессии"),
        language = language,
        canDismiss = true,
        onDismiss = onDismiss,
        extraAction = if (allowSkip) {
            {
                TextButton(onClick = onSkip) {
                    Text(tr(language, "Skip questionnaire", "Пропустить анкету"))
                }
            }
        } else {
            null
        },
        onSave = {
            val questionnaire = values.toSessionQuestionnaire()
            invalidDateTime = runCatching {
                LocalDate.parse(questionnaire.date)
                LocalTime.parse(questionnaire.startTime)
            }.isFailure
            if (!invalidDateTime) onSave(questionnaire)
        },
    ) {
        SectionTitle(tr(language, "What and when", "Что и когда"))
        TextField(values, "sessionNumber", tr(language, "Session number", "Номер сессии"), readOnly = true)
        TextField(values, "date", tr(language, "Date (YYYY-MM-DD)", "Дата (ГГГГ-ММ-ДД)"), isError = invalidDateTime)
        TextField(values, "startTime", tr(language, "Start time (HH:MM)", "Время начала (ЧЧ:ММ)"), isError = invalidDateTime)
        TextField(values, "operator", tr(language, "Operator", "Кто снимал"))
        MultiChoiceField(
            values,
            "recordingContents",
            tr(language, "Recorded", "Что записывали"),
            options(
                language,
                "gait" to ("Gait" to "Аллюр"),
                "activity" to ("Activity" to "Активность"),
                "rest" to ("Rest and sleep" to "Покой и сон"),
                "other" to ("Other" to "Прочее"),
            ),
        )

        SectionTitle(tr(language, "Conditions", "Условия"))
        ChoiceField(
            values,
            "surface",
            tr(language, "Surface", "Поверхность"),
            options(
                language,
                "concrete" to ("Concrete" to "Бетон"),
                "tile" to ("Tile" to "Плитка"),
                "asphalt" to ("Asphalt" to "Асфальт"),
                "grass" to ("Grass" to "Трава"),
                "soil" to ("Soil" to "Грунт"),
                "indoor_floor" to ("Indoor floor" to "Пол в помещении"),
            ),
        )
        ChoiceField(
            values,
            "location",
            tr(language, "Location", "Где"),
            options(
                language,
                "indoors" to ("Indoors" to "В помещении"),
                "outdoors" to ("Outdoors" to "На улице"),
            ),
        )
        TextField(values, "airTemperatureC", tr(language, "Air temperature, °C", "Температура воздуха, °C"), KeyboardType.Number)
        TextField(values, "sensorPosition", tr(language, "Sensor position", "Где сидел блок"))
        ChoiceField(
            values,
            "collarTightness",
            tr(language, "Collar tightness", "Ошейник затянут"),
            options(
                language,
                "loose" to ("Loose" to "Свободно"),
                "snug" to ("Snug" to "Плотно"),
                "tight" to ("Tight" to "Туго"),
            ),
        )

        SectionTitle(tr(language, "Manual measurements", "Измерения руками"))
        TextField(values, "preMeasurementState", tr(language, "State before measurement", "Что делала перед измерением"))
        TextField(values, "pulseBpm", tr(language, "Pulse, bpm", "Пульс, уд/мин"), KeyboardType.Number)
        TextField(values, "respirationPerMinute", tr(language, "Respiration, breaths/min", "Дыхание, вдохов/мин"), KeyboardType.Number)
        TextField(values, "bodyTemperatureC", tr(language, "Body temperature, °C", "Температура тела, °C"), KeyboardType.Decimal)
        TextField(values, "measurementTime", tr(language, "Measurement time (HH:MM)", "Время измерения (ЧЧ:ММ)"))
    }
}

@Composable
private fun QuestionnaireDialog(
    title: String,
    language: AppLanguage,
    canDismiss: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (canDismiss) {
                        TextButton(onClick = onDismiss) {
                            Text(tr(language, "Cancel", "Отмена"))
                        }
                    }
                    Button(onClick = onSave) {
                        Text(tr(language, "Save", "Сохранить"))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    extraAction?.let { action -> item { action() } }
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TextField(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = values[key].orEmpty(),
        onValueChange = { values[key] = it },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceField(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    options: List<Pair<String, String>>,
) {
    Column {
        Text(label, fontWeight = FontWeight.Medium)
        options.forEach { (value, title) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        values[key] = if (values[key] == value) "" else value
                    },
            ) {
                RadioButton(
                    selected = values[key] == value,
                    onClick = { values[key] = if (values[key] == value) "" else value },
                )
                Text(title, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun MultiChoiceField(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    options: List<Pair<String, String>>,
) {
    Column {
        Text(label, fontWeight = FontWeight.Medium)
        options.forEach { (value, title) ->
            val selected = values.list(key).contains(value)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { values.toggle(key, value) },
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { values.toggle(key, value) },
                )
                Text(title, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

private fun MutableMap<String, String>.putDog(value: DogQuestionnaire?) {
    if (value == null) return
    put("numberOrName", value.numberOrName)
    putIfNotNull("shelterOrPlace", value.shelterOrPlace)
    putIfNotNull("breed", value.breed)
    putIfNotNull("resembles", value.resembles)
    putIfNotNull("size", value.size)
    putIfNotNull("ageYears", value.ageYears)
    putIfNotNull("ageMonths", value.ageMonths)
    putIfNotNull("ageSource", value.ageSource)
    putIfNotNull("sex", value.sex)
    putIfNotNull("sterilized", value.sterilized?.let { if (it) "yes" else "no" })
    putIfNotNull("weightKg", value.weightKg)
    putIfNotNull("weightSource", value.weightSource)
    putIfNotNull("bodyConditionScore", if (value.bodyConditionUnableToAssess == true) "unable" else value.bodyConditionScore)
    putIfNotNull("muscleMass", value.muscleMass)
    putIfNotNull("neckCircumferenceCm", value.neckCircumferenceCm)
    putIfNotNull("coatLength", value.coatLength)
    putIfNotNull("undercoat", value.undercoat)
    putIfNotNull("shavedAreas", value.shavedAreas)
    put("observedSigns", value.observedSigns.joinToString(LIST_SEPARATOR))
    putIfNotNull("diagnosesPresent", value.diagnosesPresent?.let { if (it) "yes" else "no" })
    putIfNotNull("diagnosesDetails", value.diagnosesDetails)
    putIfNotNull("housing", value.housing)
    putIfNotNull("walks", value.walks)
    putIfNotNull("cohabitants", value.cohabitants)
    putIfNotNull("shelterPermission", value.shelterPermission?.let { if (it) "yes" else "no" })
    putIfNotNull("notes", value.notes)
}

private fun MutableMap<String, String>.toDogQuestionnaire(name: String) = DogQuestionnaire(
    numberOrName = name,
    shelterOrPlace = text("shelterOrPlace"),
    breed = text("breed"),
    resembles = text("resembles"),
    size = text("size"),
    ageYears = text("ageYears")?.toIntOrNull(),
    ageMonths = text("ageMonths")?.toIntOrNull(),
    ageSource = text("ageSource"),
    sex = text("sex"),
    sterilized = boolean("sterilized"),
    weightKg = text("weightKg")?.toDoubleOrNull(),
    weightSource = text("weightSource"),
    bodyConditionScore = text("bodyConditionScore")?.toIntOrNull(),
    bodyConditionUnableToAssess = text("bodyConditionScore")?.let { it == "unable" },
    muscleMass = text("muscleMass"),
    neckCircumferenceCm = text("neckCircumferenceCm")?.toDoubleOrNull(),
    coatLength = text("coatLength"),
    undercoat = text("undercoat"),
    shavedAreas = text("shavedAreas"),
    observedSigns = list("observedSigns"),
    diagnosesPresent = boolean("diagnosesPresent"),
    diagnosesDetails = text("diagnosesDetails"),
    housing = text("housing"),
    walks = text("walks"),
    cohabitants = text("cohabitants"),
    shelterPermission = boolean("shelterPermission"),
    notes = text("notes"),
)

private fun MutableMap<String, String>.putSession(value: SessionQuestionnaire) {
    put("sessionNumber", value.sessionNumber)
    put("date", value.date)
    put("startTime", value.startTime)
    putIfNotNull("operator", value.operator)
    put("recordingContents", value.recordingContents.joinToString(LIST_SEPARATOR))
    putIfNotNull("surface", value.surface)
    putIfNotNull("location", value.location)
    putIfNotNull("airTemperatureC", value.airTemperatureC)
    putIfNotNull("sensorPosition", value.sensorPosition)
    putIfNotNull("collarTightness", value.collarTightness)
    putIfNotNull("preMeasurementState", value.preMeasurementState)
    putIfNotNull("pulseBpm", value.pulseBpm)
    putIfNotNull("respirationPerMinute", value.respirationPerMinute)
    putIfNotNull("bodyTemperatureC", value.bodyTemperatureC)
    putIfNotNull("measurementTime", value.measurementTime)
}

private fun MutableMap<String, String>.toSessionQuestionnaire() = SessionQuestionnaire(
    sessionNumber = get("sessionNumber") ?: UUID.randomUUID().toString(),
    date = get("date").orEmpty(),
    startTime = get("startTime").orEmpty(),
    operator = text("operator"),
    recordingContents = list("recordingContents"),
    surface = text("surface"),
    location = text("location"),
    airTemperatureC = text("airTemperatureC")?.toIntOrNull(),
    sensorPosition = text("sensorPosition"),
    collarTightness = text("collarTightness"),
    preMeasurementState = text("preMeasurementState"),
    pulseBpm = text("pulseBpm")?.toIntOrNull(),
    respirationPerMinute = text("respirationPerMinute")?.toIntOrNull(),
    bodyTemperatureC = text("bodyTemperatureC")?.toDoubleOrNull(),
    measurementTime = text("measurementTime"),
)

private fun MutableMap<String, String>.putIfNotNull(key: String, value: Any?) {
    if (value != null) put(key, value.toString())
}

private fun MutableMap<String, String>.text(key: String): String? = get(key)?.trim()?.takeIf(String::isNotEmpty)

private fun MutableMap<String, String>.boolean(key: String): Boolean? = when (text(key)) {
    "yes" -> true
    "no" -> false
    else -> null
}

private fun MutableMap<String, String>.list(key: String): List<String> =
    get(key).orEmpty().split(LIST_SEPARATOR).filter(String::isNotEmpty)

private fun MutableMap<String, String>.toggle(key: String, value: String) {
    val next = list(key).toMutableSet()
    if (!next.add(value)) next.remove(value)
    put(key, next.joinToString(LIST_SEPARATOR))
}

private fun options(
    language: AppLanguage,
    vararg values: Pair<String, Pair<String, String>>,
): List<Pair<String, String>> = values.map { (value, labels) ->
    value to tr(language, labels.first, labels.second)
}

private fun yesNo(language: AppLanguage) = options(
    language,
    "yes" to ("Yes" to "Да"),
    "no" to ("No" to "Нет"),
)

private fun yesNoUnknown(language: AppLanguage) = yesNo(language) +
    ("unknown" to tr(language, "Unknown", "Неизвестно"))

private fun tr(language: AppLanguage, english: String, russian: String): String =
    if (language == AppLanguage.RUSSIAN) russian else english

private const val LIST_SEPARATOR = "\u001F"
