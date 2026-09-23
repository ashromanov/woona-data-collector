"""Version 2 questionnaire contract, matching the supplied Google Sheet columns.

V1 remains readable and valid for immutable historical profiles. V2 stores the
sheet's wording without coercing missing answers to invented yes/no values.
The retained V1 keys are transport compatibility fields, not additional questions.
"""

import csv
import io
import json
import math
from datetime import date, time
from pathlib import Path

from jsonschema import Draft202012Validator

DOG_COLUMNS = (
    ("savedAtLocal", "Дата и время сохранения"),
    ("animalId", "Номер/ID животного"), ("numberOrName", "Кличка"), ("species", "Вид"),
    ("shelterOrPlace", "Приют/лагерь/место сбора"), ("breedName", "Порода"),
    ("resembles", "Если метис — похожая порода/тип"), ("size", "Размер"),
    ("ageYears", "Возраст, лет"), ("ageMonths", "Возраст, месяцев"),
    ("ageSource", "Источник данных о возрасте"), ("sex", "Пол"),
    ("sterilizationStatus", "Стерилизован/кастрирован"), ("weightKg", "Вес, кг"),
    ("weightStatus", "Как получен вес"), ("bodyConditionScore", "BCS (1-9)"),
    ("muscleMass", "Оценка мышечной массы"), ("neckCircumferenceCm", "Обхват шеи, см"),
    ("coatLength", "Длина шерсти"), ("undercoat", "Подшёрсток"),
    ("shavedAreasStatus", "Выстриженные/выбритые участки"), ("shavedAreasDetails", "Где и почему (выстрижено)"),
    ("diagnosesStatus", "Диагнозы поставлены ветеринаром"), ("diagnosesDetails", "Диагнозы (описание)"),
    ("diseaseCategory", "Категория подтверждённого заболевания"),
    ("diseaseCategoryDetails", "Категория — уточнение (другое)"),
    ("chronicLameness", "Хроническая хромота/проблемы с суставами"),
    ("medications", "Регулярные препараты и дозировка"), ("history", "Анамнез/важные комментарии"),
    ("housing", "Где живёт"), ("housingDetails", "Где живёт — уточнение (другое)"),
    ("walksDescription", "Прогулки (частота и продолжительность)"),
    ("cohabitants", "Проживание с другими животными"), ("shelterPermission", "Разрешение на съёмку"),
    ("notes", "Примечания"), ("specialistName", "ФИО специалиста, проводящего запись"),
)
SESSION_COLUMNS = (
    ("savedAtLocal", "Метка времени сохранения"), ("sessionLabel", "Номер сессии"),
    ("animalId", "Номер/ID животного"), ("sessionDate", "Дата сессии"),
    ("startTime", "Время начала записи"), ("endTime", "Время окончания"),
    ("durationMinutes", "Фактическая продолжительность"), ("operatorName", "Кто проводил запись"),
    ("plannedActivities", "Что планировалось записывать"),
    ("activityDetails", "Формат записи - уточнение (другое)"), ("location", "Где проходила сессия"),
    ("surfaces", "Поверхность"), ("surfaceDetails", "Поверхность - уточнение (другое)"),
    ("airTemperatureC", "Температура воздуха, °C"), ("sensorPosition", "Положение блока"),
    ("sensorPositionDetails", "Положение блока - уточнение (другое)"),
    ("collarTightness", "Насколько затянут ошейник"), ("preMeasurementState", "Состояние животного перед записью"),
    ("preMeasurementStateDetails", "Состояние перед записью - уточнение (другое)"),
    ("lastMedicationAt", "Когда последний раз получал препарат (ЧСС/дыхание/активность)"),
    ("notes", "Примечания"), ("specialistName", "ФИО специалиста, проводящего запись"),
)
MODELS = Path(__file__).resolve().parents[1] / "docs/target-server-plan/models"


def sheet_schema(kind: str) -> dict:
    old = json.loads((MODELS / f"{kind}-questionnaire.schema.json").read_text())
    properties = {}
    for key, value in old["properties"].items():
        if key == "schemaVersion":
            properties[key] = {"const": 2}
        elif key == "videoRequested":
            properties[key] = {"type": "boolean"}
        elif value.get("type") == "array":
            properties[key] = {"type": "array", "items": {"type": "string"}, "maxItems": 100}
        elif "enum" in value:
            properties[key] = {"type": "string", "maxLength": 2000}
        else:
            properties[key] = {k: v for k, v in value.items() if k in ("type", "minimum", "maximum", "exclusiveMinimum", "maxLength")}
    for key, _ in DOG_COLUMNS if kind == "dog" else SESSION_COLUMNS:
        properties.setdefault(key, {"type": ["string", "null"], "maxLength": 2000})
    for key in ("animalId", "numberOrName") if kind == "dog" else ("sessionLabel",):
        properties[key] = {"type": "string", "minLength": 1, "maxLength": 100, "pattern": r"\S"}
    if kind == "session":
        properties["durationMinutes"] = {"type": ["number", "null"], "minimum": 0}
        for key in ("plannedActivities", "surfaces"):
            properties[key] = {"type": "array", "items": {"type": "string", "minLength": 1, "maxLength": 100}, "uniqueItems": True, "maxItems": 30}
        properties["plannedActivities"]["minItems"] = 1
    required = ["schemaVersion"] + (["animalId", "numberOrName"] if kind == "dog" else ["sessionLabel", "plannedActivities", "videoRequested"])
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": False,
            "properties": properties, "required": required}


def validate_sheet(kind: str, answers: dict) -> None:
    for key, value in answers.items():
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError(f"{key}: enter a finite number")
    Draft202012Validator(sheet_schema(kind)).validate(answers)
    if kind == "session":
        if answers.get("sessionDate"):
            date.fromisoformat(answers["sessionDate"])
        for key in ("startTime", "endTime"):
            if answers.get(key):
                time.fromisoformat(answers[key])


def empty_sheet(kind: str) -> dict:
    """Supply compatibility keys so older typed storage decoders remain usable."""
    result = {}
    for key, rule in sheet_schema(kind)["properties"].items():
        types = rule.get("type", "")
        result[key] = None if "null" in types else [] if types == "array" else True if types == "boolean" else ""
    result["schemaVersion"] = 2
    return result


def read_sheet(path: Path, kind: str) -> list[dict]:
    reader = csv.DictReader(io.StringIO(path.read_text(encoding="utf-8-sig")))
    columns = DOG_COLUMNS if kind == "dog" else SESSION_COLUMNS
    if reader.fieldnames != [title for _, title in columns]:
        raise ValueError(f"{kind} sheet columns changed; review mapping before import")
    rows = []
    numeric = {"ageYears": int, "ageMonths": int, "bodyConditionScore": int,
               "weightKg": float, "neckCircumferenceCm": float, "airTemperatureC": float, "durationMinutes": float}
    for number, row in enumerate(reader, 2):
        value = empty_sheet(kind)
        issues = []
        for key, title in columns:
            raw = row[title].strip()
            if key in numeric:
                try:
                    value[key] = numeric[key](raw.replace(",", ".")) if raw else None
                except ValueError:
                    issues.append(f"invalid number: {title}={raw}")
            elif key in ("surfaces", "plannedActivities"):
                value[key] = [part.strip() for part in raw.split(",") if part.strip()]
            else:
                value[key] = raw or value[key]
        try:
            validate_sheet(kind, value)
        except Exception as error:
            issues.append(str(error).split("\n")[0])
        rows.append({"row": number, "raw": row, "answers": value, "issues": issues})
    return rows
