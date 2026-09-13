import SwiftUI
import UIKit

struct DogQuestionnaireEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: DogQuestionnaire
    @State private var errors: [String: String] = [:]
    let language: AppLanguage
    let onSave: (DogQuestionnaire) -> Void

    init(initial: DogQuestionnaire?, language: AppLanguage, onSave: @escaping (DogQuestionnaire) -> Void) {
        _draft = State(initialValue: initial ?? DogQuestionnaire())
        self.language = language
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                Form {
                    Section {
                        ProgressView(value: progress)
                        Text(language == .russian ? "Заполнено \(Int(progress * 100))%" : "Completed \(Int(progress * 100))%")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Section(language == .russian ? "Идентификация" : "Identification") {
                        field("numberOrName", language == .russian ? "Номер или имя" : "Number or name", text: $draft.numberOrName)
                        field("shelterOrPlace", language == .russian ? "Приют или место" : "Shelter or place", text: $draft.shelterOrPlace)
                        choice("breedStatus", language == .russian ? "Порода" : "Breed status", selection: $draft.breedStatus, values: ["purebred", "mixed", "unknown"])
                        if ["purebred", "mixed"].contains(draft.breedStatus) {
                            optionalField("breedName", language == .russian ? "Название породы" : "Breed name", value: optionalText(\.breedName))
                        }
                        if draft.breedStatus == "mixed" {
                            optionalField("resembles", language == .russian ? "На кого похожа" : "Resembles", value: optionalText(\.resembles))
                        }
                        choice("size", language == .russian ? "Размер" : "Size", selection: $draft.size, values: ["small", "medium", "large", "giant", "unknown"])
                    }

                    Section(language == .russian ? "Возраст и пол" : "Age and sex") {
                        choice("ageStatus", language == .russian ? "Возраст" : "Age status", selection: $draft.ageStatus, values: ["known", "estimated", "unknown"])
                        if ["known", "estimated"].contains(draft.ageStatus) {
                            optionalField("ageYears", language == .russian ? "Полных лет" : "Years", value: optionalInt(\.ageYears), keyboard: .numberPad)
                            optionalField("ageMonths", language == .russian ? "Месяцев" : "Months", value: optionalInt(\.ageMonths), keyboard: .numberPad)
                            choice("ageSource", language == .russian ? "Источник возраста" : "Age source", selection: $draft.ageSource, values: ["documents", "shelter_report", "dental_estimate", "operator_estimate"])
                        }
                        choice("sex", language == .russian ? "Пол" : "Sex", selection: $draft.sex, values: ["male", "female", "unknown"])
                        choice("sterilizationStatus", language == .russian ? "Стерилизация" : "Sterilization", selection: $draft.sterilizationStatus, values: ["yes", "no", "unknown"])
                    }

                    Section(language == .russian ? "Физические параметры" : "Physical measurements") {
                        choice("weightStatus", language == .russian ? "Вес" : "Weight status", selection: $draft.weightStatus, values: ["measured", "estimated", "unknown"])
                        if ["measured", "estimated"].contains(draft.weightStatus) {
                            optionalField("weightKg", language == .russian ? "Вес, кг" : "Weight, kg", value: optionalDouble(\.weightKg), keyboard: .decimalPad)
                        }
                        choice("bodyConditionStatus", language == .russian ? "Оценка кондиции" : "Body condition", selection: $draft.bodyConditionStatus, values: ["assessed", "unable"])
                        if draft.bodyConditionStatus == "assessed" {
                            optionalField("bodyConditionScore", language == .russian ? "Баллы 1–9" : "Score 1–9", value: optionalInt(\.bodyConditionScore), keyboard: .numberPad)
                        }
                        choice("muscleMass", language == .russian ? "Мышечная масса" : "Muscle mass", selection: $draft.muscleMass, values: ["normal", "mild_loss", "moderate_loss", "severe_loss", "unable"])
                        choice("neckCircumferenceStatus", language == .russian ? "Обхват шеи" : "Neck circumference", selection: $draft.neckCircumferenceStatus, values: ["measured", "not_measured"])
                        if draft.neckCircumferenceStatus == "measured" {
                            optionalField("neckCircumferenceCm", language == .russian ? "Обхват, см" : "Circumference, cm", value: optionalDouble(\.neckCircumferenceCm), keyboard: .decimalPad)
                        }
                    }

                    Section(language == .russian ? "Шерсть и признаки" : "Coat and observed signs") {
                        choice("coatLength", language == .russian ? "Длина шерсти" : "Coat length", selection: $draft.coatLength, values: ["short", "medium", "long", "unknown"])
                        choice("undercoat", language == .russian ? "Подшёрсток" : "Undercoat", selection: $draft.undercoat, values: ["none", "moderate", "dense", "unknown"])
                        choice("shavedAreasStatus", language == .russian ? "Выбритые участки" : "Shaved areas", selection: $draft.shavedAreasStatus, values: ["none", "present", "unknown"])
                        if draft.shavedAreasStatus == "present" {
                            optionalField("shavedAreasDetails", language == .russian ? "Описание" : "Details", value: optionalText(\.shavedAreasDetails))
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text(language == .russian ? "Наблюдаемые признаки" : "Observed signs")
                            ForEach(DogQuestionnaire.observedSignValues.sorted(), id: \.self) { value in
                                Toggle(optionTitle(value), isOn: signBinding(value))
                            }
                            errorText("observedSigns")
                        }
                        .id("observedSigns")
                    }

                    Section(language == .russian ? "Здоровье и содержание" : "Health and housing") {
                        choice("diagnosesStatus", language == .russian ? "Диагнозы" : "Diagnoses", selection: $draft.diagnosesStatus, values: ["yes", "no", "unknown"])
                        if draft.diagnosesStatus == "yes" {
                            optionalField("diagnosesDetails", language == .russian ? "Диагнозы" : "Diagnosis details", value: optionalText(\.diagnosesDetails))
                        }
                        choice("housing", language == .russian ? "Содержание" : "Housing", selection: $draft.housing, values: ["enclosure", "room", "home", "free_range", "other", "unknown"])
                        if draft.housing == "other" {
                            optionalField("housingDetails", language == .russian ? "Описание" : "Housing details", value: optionalText(\.housingDetails))
                        }
                        choice("walksStatus", language == .russian ? "Прогулки" : "Walks", selection: $draft.walksStatus, values: ["known", "none", "unknown"])
                        if draft.walksStatus == "known" {
                            optionalField("walksDescription", language == .russian ? "Режим прогулок" : "Walk description", value: optionalText(\.walksDescription))
                        }
                        choice("cohabitants", language == .russian ? "Совместное проживание" : "Cohabitants", selection: $draft.cohabitants, values: ["alone", "other_animals", "people_only", "unknown"])
                        choice("shelterPermission", language == .russian ? "Разрешение приюта" : "Shelter permission", selection: $draft.shelterPermission, values: ["yes", "no", "not_required", "unknown"])
                        choice("notesStatus", language == .russian ? "Заметки" : "Notes", selection: $draft.notesStatus, values: ["none", "provided"])
                        if draft.notesStatus == "provided" {
                            optionalField("notes", language == .russian ? "Заметки" : "Notes", value: optionalText(\.notes))
                        }
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(language == .russian ? "Отмена" : "Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(language == .russian ? "Сохранить" : "Save") {
                            var candidate = draft
                            candidate.normalizeConditionals()
                            let validation = candidate.validate()
                            errors = validation.errors
                            guard validation.isValid else {
                                if let first = Self.fieldOrder.first(where: { errors[$0] != nil }) {
                                    withAnimation { proxy.scrollTo(first, anchor: .center) }
                                }
                                return
                            }
                            draft = candidate
                            onSave(candidate)
                            dismiss()
                        }
                    }
                }
                .navigationTitle(language == .russian ? "Анкета собаки" : "Dog questionnaire")
            }
        }
    }

    private var progress: Double {
        max(0, 1 - Double(draft.validate().errors.count) / Double(Self.fieldOrder.count))
    }

    private func field(_ id: String, _ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading) { TextField(title, text: text); errorText(id) }.id(id)
    }

    private func optionalField(_ id: String, _ title: String, value: Binding<String>, keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading) { TextField(title, text: value).keyboardType(keyboard); errorText(id) }.id(id)
    }

    private func choice(_ id: String, _ title: String, selection: Binding<String>, values: [String]) -> some View {
        VStack(alignment: .leading) {
            Picker(title, selection: selection) { ForEach(values, id: \.self) { Text(optionTitle($0)).tag($0) } }
            errorText(id)
        }.id(id)
    }

    @ViewBuilder private func errorText(_ id: String) -> some View {
        if let error = errors[id] { Text(questionnaireValidationError(error, language: language)).font(.caption).foregroundStyle(.red) }
    }

    private func optionalText(_ path: WritableKeyPath<DogQuestionnaire, String?>) -> Binding<String> {
        Binding(get: { draft[keyPath: path] ?? "" }, set: { draft[keyPath: path] = $0.isEmpty ? nil : $0 })
    }
    private func optionalInt(_ path: WritableKeyPath<DogQuestionnaire, Int?>) -> Binding<String> {
        Binding(get: { draft[keyPath: path].map(String.init) ?? "" }, set: { draft[keyPath: path] = Int($0) })
    }
    private func optionalDouble(_ path: WritableKeyPath<DogQuestionnaire, Double?>) -> Binding<String> {
        Binding(get: { draft[keyPath: path].map { String($0) } ?? "" }, set: { draft[keyPath: path] = Double($0.replacingOccurrences(of: ",", with: ".")) })
    }
    private func signBinding(_ value: String) -> Binding<Bool> {
        Binding(
            get: { draft.observedSigns.contains(value) },
            set: { selected in
                if selected {
                    draft.observedSigns = ["none", "unknown"].contains(value) ? [value] : draft.observedSigns.filter { !["none", "unknown"].contains($0) } + [value]
                } else {
                    draft.observedSigns.removeAll { $0 == value }
                }
            }
        )
    }
    private func optionTitle(_ value: String) -> String { questionnaireOptionTitle(value, language: language) }

    private static let fieldOrder = [
        "numberOrName", "shelterOrPlace", "breedStatus", "breedName", "resembles", "size",
        "ageStatus", "ageYears", "ageMonths", "ageSource", "sex", "sterilizationStatus",
        "weightStatus", "weightKg", "bodyConditionStatus", "bodyConditionScore", "muscleMass",
        "neckCircumferenceStatus", "neckCircumferenceCm", "coatLength", "undercoat",
        "shavedAreasStatus", "shavedAreasDetails", "observedSigns", "diagnosesStatus",
        "diagnosesDetails", "housing", "housingDetails", "walksStatus", "walksDescription",
        "cohabitants", "shelterPermission", "notesStatus", "notes",
    ]
}

struct SessionQuestionnaireEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft = SessionQuestionnaire()
    @State private var errors: [String: String] = [:]
    let language: AppLanguage
    let source: String
    let onSave: (SessionQuestionnaire) -> Void

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                Form {
                    Section {
                        ProgressView(value: progress)
                        Toggle(language == .russian ? "Записывать видео" : "Record video", isOn: $draft.videoRequested)
                            .disabled(source == "replay")
                    }
                    Section(language == .russian ? "Сессия" : "Session") {
                        field("sessionLabel", language == .russian ? "Название" : "Session label", text: $draft.sessionLabel)
                        field("operatorName", language == .russian ? "Оператор" : "Operator", text: $draft.operatorName)
                        choice("activityGroup", language == .russian ? "Группа активности" : "Activity group", selection: $draft.activityGroup, values: SessionQuestionnaire.activityTypes.keys.sorted())
                        choice("activityType", language == .russian ? "Активность" : "Activity", selection: $draft.activityType, values: SessionQuestionnaire.activityTypes[draft.activityGroup]?.sorted() ?? [])
                        if ["mixed", "other"].contains(draft.activityType) { optionalField("activityDetails", detailsTitle, value: optionalText(\.activityDetails)) }
                    }
                    Section(language == .russian ? "Условия" : "Environment") {
                        choice("location", language == .russian ? "Место" : "Location", selection: $draft.location, values: ["indoors", "outdoors"])
                        choice("surface", language == .russian ? "Поверхность" : "Surface", selection: $draft.surface, values: SessionQuestionnaire.surfaces[draft.location]?.sorted() ?? [])
                        if draft.surface == "other" { optionalField("surfaceDetails", detailsTitle, value: optionalText(\.surfaceDetails)) }
                        measurement("airTemperature", title: language == .russian ? "Температура воздуха" : "Air temperature", status: $draft.airTemperatureStatus, value: optionalDouble(\.airTemperatureC))
                        choice("sensorPosition", language == .russian ? "Положение датчика" : "Sensor position", selection: $draft.sensorPosition, values: ["dorsal_neck", "left_neck", "right_neck", "chest", "back", "other"])
                        if draft.sensorPosition == "other" { optionalField("sensorPositionDetails", detailsTitle, value: optionalText(\.sensorPositionDetails)) }
                        choice("collarTightness", language == .russian ? "Затяжка ошейника" : "Collar tightness", selection: $draft.collarTightness, values: ["loose", "snug", "tight"])
                    }
                    Section(language == .russian ? "Состояние" : "State and measurements") {
                        choice("preMeasurementState", language == .russian ? "Состояние до замера" : "Pre-measurement state", selection: $draft.preMeasurementState, values: ["rest", "walk", "run", "play", "stress", "other", "unknown"])
                        if draft.preMeasurementState == "other" { optionalField("preMeasurementStateDetails", detailsTitle, value: optionalText(\.preMeasurementStateDetails)) }
                        measurement("pulse", title: language == .russian ? "Пульс" : "Pulse", status: $draft.pulseStatus, value: optionalInt(\.pulseBpm))
                        measurement("respiration", title: language == .russian ? "Дыхание" : "Respiration", status: $draft.respirationStatus, value: optionalInt(\.respirationPerMinute))
                        measurement("bodyTemperature", title: language == .russian ? "Температура тела" : "Body temperature", status: $draft.bodyTemperatureStatus, value: optionalDouble(\.bodyTemperatureC))
                        if [draft.pulseStatus, draft.respirationStatus, draft.bodyTemperatureStatus].contains("measured") {
                            optionalField("measurementAtUtc", language == .russian ? "Время ISO-8601" : "Measurement time ISO-8601", value: optionalText(\.measurementAtUtc))
                        }
                    }
                }
                .onAppear { if source == "replay" { draft.videoRequested = false } }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(language == .russian ? "Отмена" : "Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(language == .russian ? "Продолжить" : "Continue") {
                            var candidate = draft
                            candidate.normalizeConditionals()
                            if [candidate.pulseStatus, candidate.respirationStatus, candidate.bodyTemperatureStatus].contains("measured"),
                               candidate.measurementAtUtc == nil {
                                candidate.measurementAtUtc = WoonaStore.iso8601(Date())
                            }
                            let validation = candidate.validate()
                            errors = validation.errors
                            guard validation.isValid else {
                                if let first = Self.fieldOrder.first(where: { errors[$0] != nil }) { withAnimation { proxy.scrollTo(first, anchor: .center) } }
                                return
                            }
                            draft = candidate
                            onSave(candidate)
                            dismiss()
                        }
                    }
                }
                .navigationTitle(language == .russian ? "Анкета сессии" : "Session questionnaire")
            }
        }
    }

    private var progress: Double { max(0, 1 - Double(draft.validate().errors.count) / Double(Self.fieldOrder.count)) }
    private func field(_ id: String, _ title: String, text: Binding<String>) -> some View { VStack(alignment: .leading) { TextField(title, text: text); errorText(id) }.id(id) }
    private func optionalField(_ id: String, _ title: String, value: Binding<String>) -> some View { VStack(alignment: .leading) { TextField(title, text: value); errorText(id) }.id(id) }
    private func choice(_ id: String, _ title: String, selection: Binding<String>, values: [String]) -> some View {
        VStack(alignment: .leading) { Picker(title, selection: selection) { ForEach(values, id: \.self) { Text(optionTitle($0)).tag($0) } }; errorText(id) }.id(id)
    }
    private func measurement(_ id: String, title: String, status: Binding<String>, value: Binding<String>) -> some View {
        VStack(alignment: .leading) {
            Picker(title, selection: status) { Text(optionTitle("measured")).tag("measured"); Text(optionTitle("not_measured")).tag("not_measured") }
            if status.wrappedValue == "measured" { TextField(title, text: value).keyboardType(.decimalPad) }
            errorText(id); errorText("\(id)Status")
        }.id(id)
    }
    @ViewBuilder private func errorText(_ id: String) -> some View { if let error = errors[id] { Text(questionnaireValidationError(error, language: language)).font(.caption).foregroundStyle(.red) } }
    private func optionalText(_ path: WritableKeyPath<SessionQuestionnaire, String?>) -> Binding<String> { Binding(get: { draft[keyPath: path] ?? "" }, set: { draft[keyPath: path] = $0.isEmpty ? nil : $0 }) }
    private func optionalInt(_ path: WritableKeyPath<SessionQuestionnaire, Int?>) -> Binding<String> { Binding(get: { draft[keyPath: path].map(String.init) ?? "" }, set: { draft[keyPath: path] = Int($0) }) }
    private func optionalDouble(_ path: WritableKeyPath<SessionQuestionnaire, Double?>) -> Binding<String> { Binding(get: { draft[keyPath: path].map { String($0) } ?? "" }, set: { draft[keyPath: path] = Double($0.replacingOccurrences(of: ",", with: ".")) }) }
    private var detailsTitle: String { language == .russian ? "Описание" : "Details" }
    private func optionTitle(_ value: String) -> String { questionnaireOptionTitle(value, language: language) }
    private static let fieldOrder = ["sessionLabel", "operatorName", "activityGroup", "activityType", "activityDetails", "location", "surface", "surfaceDetails", "airTemperature", "sensorPosition", "sensorPositionDetails", "collarTightness", "preMeasurementState", "preMeasurementStateDetails", "pulse", "respiration", "bodyTemperature", "measurementAtUtc"]
}

private func questionnaireOptionTitle(_ value: String, language: AppLanguage) -> String {
    guard language == .russian else { return value.replacingOccurrences(of: "_", with: " ").capitalized }
    return russianQuestionnaireOptions[value] ?? value.replacingOccurrences(of: "_", with: " ").capitalized
}

private func questionnaireValidationError(_ error: String, language: AppLanguage) -> String {
    guard language == .russian else { return error }
    if error.hasPrefix("Maximum ") {
        return error.replacingOccurrences(of: "Maximum ", with: "Максимум ").replacingOccurrences(of: " characters", with: " символов")
    }
    return [
        "Required": "Обязательное поле",
        "Must be empty": "Поле должно быть пустым",
        "Choose an activity": "Выберите активность",
        "Choose a surface": "Выберите поверхность",
        "Unsupported schema": "Неподдерживаемая версия анкеты",
        "Enter years or months": "Укажите годы или месяцы",
        "Unknown age cannot contain an estimate": "Для неизвестного возраста нельзя указывать оценку",
        "Invalid or duplicate sign": "Некорректный или повторяющийся признак",
        "None/unknown cannot be combined": "«Нет» и «Неизвестно» нельзя совмещать с другими ответами",
        "Out of range": "Значение вне допустимого диапазона",
        "Enter a valid measurement time": "Укажите корректное время измерения",
    ][error] ?? (error.hasPrefix("Use ") ? "Значение вне допустимого диапазона" : error)
}

private let russianQuestionnaireOptions: [String: String] = [
    "purebred": "Породистая", "mixed": "Метис", "unknown": "Неизвестно",
    "small": "Маленький", "medium": "Средний", "large": "Большой", "giant": "Гигантский",
    "known": "Известно", "estimated": "Оценено", "documents": "Документы",
    "shelter_report": "Данные приюта", "dental_estimate": "Оценка по зубам",
    "operator_estimate": "Оценка оператора", "male": "Самец", "female": "Самка",
    "yes": "Да", "no": "Нет", "measured": "Измерено", "not_measured": "Не измерено",
    "assessed": "Оценено", "unable": "Невозможно оценить", "normal": "Норма",
    "mild_loss": "Лёгкая потеря", "moderate_loss": "Умеренная потеря",
    "severe_loss": "Выраженная потеря", "short": "Короткая", "long": "Длинная",
    "none": "Нет", "moderate": "Умеренный", "dense": "Густой", "present": "Есть",
    "labored_breathing": "Затруднённое дыхание", "fainting": "Обмороки", "seizures": "Судороги",
    "cannot_urinate": "Не может помочиться", "limb_weakness": "Слабость конечностей",
    "jaundice": "Желтуха", "vomiting_or_no_appetite": "Рвота или нет аппетита",
    "stool_changes": "Изменения стула", "cough": "Кашель", "pain_or_lameness": "Боль или хромота",
    "thirst_changes": "Изменение жажды", "distress": "Сильное беспокойство",
    "enclosure": "Вольер", "room": "Комната", "home": "Дом", "free_range": "Свободное содержание",
    "other": "Другое", "alone": "Один", "other_animals": "С другими животными",
    "people_only": "Только с людьми", "not_required": "Не требуется", "provided": "Указаны",
    "locomotion": "Движение", "stationary": "Без движения", "daily_living": "Повседневная активность",
    "walk": "Ходьба", "trot": "Рысь", "gallop": "Галоп", "run": "Бег",
    "stairs_up": "Подъём по лестнице", "stairs_down": "Спуск по лестнице", "jump": "Прыжок",
    "stand": "Стоит", "sit": "Сидит", "lie": "Лежит", "rest": "Отдых", "sleep": "Сон",
    "play": "Игра", "eat": "Еда", "drink": "Питьё", "scratch": "Чешется", "groom": "Уход",
    "indoors": "В помещении", "outdoors": "На улице", "tile": "Плитка", "concrete": "Бетон",
    "wood": "Дерево", "laminate": "Ламинат", "carpet": "Ковёр", "bed": "Лежанка",
    "kennel_mat": "Коврик в вольере", "asphalt": "Асфальт", "grass": "Трава", "soil": "Грунт",
    "gravel": "Гравий", "snow": "Снег", "dorsal_neck": "Сверху на шее",
    "left_neck": "Слева на шее", "right_neck": "Справа на шее", "chest": "Грудь", "back": "Спина",
    "loose": "Свободно", "snug": "Плотно", "tight": "Туго", "stress": "Стресс",
]
