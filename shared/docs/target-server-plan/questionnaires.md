# Исторические анкеты V1 и правила UI

С 21 сентября 2026 новые анкеты используют **V2 по Google Sheets**.
Действующая спецификация и правила импорта: [ACTIVITY_IMPORT.md](../../ACTIVITY_IMPORT.md),
точное соответствие столбцов и серверные ограничения — `shared/server/questionnaires.py`.
Ниже сохранены правила V1 для чтения и проверки исторических данных.

## 1. Значение «все поля обязательны»

Обязательность означает, что оператор должен дать осмысленный ответ на каждый
вопрос. Она не означает, что приложение заставляет выдумать неизвестный вес,
диагноз или измерение.

Для объективно отсутствующих значений используются явные ответы:

- `unknown` — информация неизвестна;
- `not_measured` — измерение не выполнялось;
- `none` — признака/заметки/объекта нет;
- `not_required` — разрешение не требуется.

Пустая строка, пустой список и невыбранный radio недопустимы в новой анкете.
Условное detail-поле либо обязательно и заполнено, либо disabled и сохраняется
как `null`.

Точная machine-readable модель находится в:

- `models/dog-questionnaire.schema.json`;
- `models/session-questionnaire.schema.json`.

## 2. Общий UI-контракт

### 2.1. Один источник правил

Не строить универсальный dynamic form renderer. Для двух фиксированных анкет
это лишняя абстракция.

Сделать:

- `DogQuestionnaireDraft`;
- `SessionQuestionnaireDraft`;
- по одному pure Kotlin `validate()` на draft;
- Compose fields читают те же typed values;
- serializer принимает только validated value;
- сервер повторяет правила своей Pydantic/JSON Schema validation.

Client validation нужна для UX. Server validation нужна как trust boundary.

### 2.2. Save

- Save disabled, пока обязательные prerequisites явно не выбраны.
- После попытки Save ошибки показываются inline.
- Экран прокручивается к первому ошибочному полю.
- Вверху отображается `Заполнено N из M`.
- Ошибка содержит действие, например `Выберите поверхность`, а не просто
  красную рамку.
- Radio нельзя снять повторным нажатием. Можно только заменить другим значением.
- Text field trim выполняется до validation.
- Decimal separator принимает точку и запятую, но canonical JSON использует
  JSON number.
- Числа не превращаются тихо в `null`.

### 2.3. Изменение родительского поля

Если родитель меняет допустимые дочерние значения:

1. вычислить новый allowed set;
2. если старое значение осталось допустимым — сохранить;
3. если стало недопустимым — очистить дочернее значение;
4. показать короткое inline объяснение;
5. disabled значение всегда сериализуется как `null`;
6. validation не считает hidden/disabled поле пропущенным.

Это правило обязательно тестируется для каждого dependency.

## 3. Анкета собаки

Анкета создаёт стабильную собаку и immutable версию её данных. Редактирование
не меняет исторические записи.

### 3.1. Раздел «Кто»

| Поле | Тип | Правило |
|---|---|---|
| Номер или кличка | text | 1–100 символов |
| Приют или место | text | 1–200 символов |
| Статус породы | purebred/mixed/unknown | обязательно |
| Название породы | text/null | обязательно для purebred/mixed |
| На кого похож | text/null | только и обязательно для mixed |
| Размер | small/medium/large/giant/unknown | обязательно |
| Возраст известен | known/estimated/unknown | обязательно |
| Полных лет | 0–40/null | для known/estimated хотя бы годы или месяцы |
| Дополнительных месяцев | 0–11/null | для known/estimated |
| Источник возраста | enum | unknown только при unknown age |
| Пол | male/female/unknown | обязательно |
| Стерилизация | yes/no/unknown | обязательно |

Dependency:

```text
breedStatus=purebred -> breedName enabled, resembles disabled
breedStatus=mixed    -> breedName + resembles enabled
breedStatus=unknown  -> оба disabled/null

ageStatus=unknown    -> years/months disabled/null, source=unknown
known/estimated      -> numeric inputs enabled, минимум одно значение
```

### 3.2. Раздел «Тело»

| Поле | Тип | Правило |
|---|---|---|
| Статус веса | measured/estimated/unknown | обязательно |
| Вес | >0 и ≤150 kg/null | только для measured/estimated |
| BCS status | assessed/unable | обязательно |
| BCS | 1–9/null | только для assessed |
| Мышечная масса | normal/mild/moderate/severe/unable | обязательно |
| Статус обхвата шеи | measured/not_measured | обязательно |
| Обхват шеи | >0 и ≤150 cm/null | только measured |
| Длина шерсти | short/medium/long/unknown | обязательно |
| Подшёрсток | none/moderate/dense/unknown | обязательно |
| Выстриженные участки | none/present/unknown | обязательно |
| Описание выстриженных участков | text/null | только и обязательно при present |

### 3.3. Раздел «Состояние»

`observedSigns` — multi-choice, но он всегда имеет минимум один ответ.

Специальные ответы:

- `none`;
- `unknown`.

Они взаимоисключающие и несовместимы с любым симптомом. При выборе `none`
все симптомы снимаются; при выборе симптома `none` снимается.

Диагноз:

```text
yes     -> details обязательно
no      -> details disabled/null
unknown -> details disabled/null
```

В details одной строкой/многострочно указываются диагнозы и препараты.

### 3.4. Раздел «Содержание»

| Поле | Тип | Правило |
|---|---|---|
| Где живёт | enclosure/room/home/free_range/other/unknown | обязательно |
| Описание жилья | text/null | только и обязательно для other |
| Прогулки | known/none/unknown | обязательно |
| Описание прогулок | text/null | только known |
| С кем живёт | alone/other_animals/people_only/unknown | обязательно |
| Письменное разрешение | yes/no/not_required/unknown | обязательно |
| Примечания | none/provided | обязательно |
| Текст примечаний | text/null | только provided |

## 4. Анкета сессии

### 4.1. Что больше не является вопросом

Технический UUID не показывается как «Номер сессии». Он создаётся автоматически.

Отдельно остаётся обязательная человекочитаемая `sessionLabel`, например:

```text
Приют-1 / Rex / контроль после прогулки
```

Дата и время фактического старта не вводятся вручную. Приложение записывает их
при нажатии Start:

```text
startedAtUtc
timezone
sessionZeroMonotonicNs
```

Так анкета не может сдвинуть техническую временную шкалу случайным текстом.

### 4.2. Активность

Вместо пересекающихся галочек `gait/activity/rest/other` вводятся два
single-choice уровня.

#### Группа

| `activityGroup` | Значение |
|---|---|
| locomotion | направленное движение |
| stationary | неподвижное состояние |
| daily_living | бытовая активность |
| other | другой протокол |

#### Доступные значения

| Группа | `activityType` |
|---|---|
| locomotion | walk, trot, gallop, run, stairs_up, stairs_down, jump, mixed |
| stationary | stand, sit, lie, rest, sleep |
| daily_living | play, eat, drink, scratch, groom, other |
| other | other |

`activityDetails`:

- обязательно для `mixed` и `other`;
- disabled/null для остальных.

Именно это исправляет текущий «бред»: нельзя одновременно записать
взаимоисключающие `rest + gait + activity`, потому что primary protocol один.

Если в будущем реально понадобится разметить несколько последовательных фаз,
добавлять `recording_segments` нужно только вместе с временными границами фаз.
Multi-select без границ не возвращается.

### 4.3. Место и поверхность

Сначала выбирается `location`, затем только допустимая поверхность:

| Location | Surface |
|---|---|
| indoors | tile, concrete, wood, laminate, carpet, bed, kennel_mat, other |
| outdoors | asphalt, concrete, grass, soil, gravel, snow, other |

`concrete` допустим в обоих. `surfaceDetails` обязательно только для `other`.

При переключении indoor/outdoor несовместимая поверхность очищается.

### 4.4. Условия и крепление

| Поле | Тип | Правило |
|---|---|---|
| Температура измерена | measured/not_measured | обязательно |
| Температура воздуха | −60…70 °C/null | только measured |
| Положение сенсора | dorsal_neck/left_neck/right_neck/chest/back/other | обязательно |
| Описание позиции | text/null | только other |
| Затяжка ошейника | loose/snug/tight | обязательно |
| Состояние до измерения | rest/walk/run/play/stress/other/unknown | обязательно |
| Описание состояния | text/null | только и обязательно для other |

Свободное «Где сидел блок» заменяется enum, иначе аналитика получит десятки
вариантов одного положения.

### 4.5. Ручные измерения

Для каждого измерения сначала обязательный status:

```text
measured | not_measured
```

Если measured:

| Значение | Диапазон |
|---|---|
| Пульс | 20–300 bpm |
| Дыхание | 1–200 / min |
| Температура тела | 30–45 °C |

Если хотя бы одно измерено, `measurementAtUtc` обязательно. В UI оператор
вводит/выбирает локальное время, а приложение немедленно переводит его в UTC с
текущей IANA-зоной.

### 4.6. Видео

`videoRequested` — обязательный boolean:

- default `true`;
- `true` блокирует Start до permission + preview ready;
- `false` явно фиксирует sensor-only сессию;
- переключить значение после Start нельзя.

Отсутствие камеры нельзя тихо преобразовать в `false`. Пользователь должен
вернуться в preflight и явно согласиться на sensor-only.

## 5. Replay

Replay больше нельзя запускать с пустой анкетой.

Для импортируемого старого файла:

- выбирается собака;
- заполняется session questionnaire;
- source автоматически `replay`;
- неизвестные старые условия отмечаются явными unknown/not_measured;
- camera option автоматически `false`;
- исходный импортированный файл сохраняется отдельным artifact, если это
  необходимо для доказуемого provenance.

## 6. Legacy migration

Существующие JSON не дополняются выдуманными ответами.

Алгоритм:

1. прочитать текущий JSON без потери полей;
2. преобразовать однозначные значения;
3. сохранить исходный JSON в versioned migration audit;
4. поставить `validation_state=legacy_incomplete`, если чего-то не хватает;
5. старую запись разрешить выгрузить и просмотреть;
6. перед новой live/replay сессией потребовать создать complete новую версию
   профиля;
7. старую session questionnaire не заставлять ретроспективно угадывать.

Server normal endpoint не принимает новые `legacy_incomplete`. Такой статус
доступен только migration command/endpoint.

## 7. Минимальные тесты формы

Pure Kotlin:

- valid complete dog/session;
- каждое required field по одному отсутствует;
- каждый numeric min/max и значение за границей;
- каждый conditional enable/disable;
- смена group очищает invalid activity;
- смена location очищает invalid surface;
- none/unknown mutual exclusion;
- measured требует value и measurement time;
- serialization round-trip сохраняет enum/null точно.

Compose:

- Save disabled/ошибка;
- scroll к первому invalid;
- hidden field не остаётся в payload;
- radio не снимается повторным нажатием;
- русский/английский;
- small screen + keyboard;
- large font.
