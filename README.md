# Gatling examples: Java / Scala / Kotlin

Три независимых Maven-проекта с ОДНИМ И ТОМ ЖЕ сценарием, чтобы сравнить реализацию 1-в-1.
Сценарий повторяет стандартный JMeter-план: Header Manager, Cookie Manager, Once Only Controller,
Loop Controller, Constant Timer, CSV Data Set Config, thread groups с ramp-up и интенсивностью.

## Структура

```
java/     pom.xml + src/test/java/load/JavaSimulation.java    (официальный Java DSL)
scala/    pom.xml + src/test/scala/load/ScalaSimulation.scala (классический Scala DSL)
kotlin/   pom.xml + src/test/kotlin/load/KotlinSimulation.kt  (Java DSL API + идиоматичный Kotlin)
```

## Запуск

```bash
cd java   && mvn gatling:test
cd scala  && mvn gatling:test
cd kotlin && mvn gatling:test
```

Отчёт откроется в `target/gatling/<sim>-<ts>/index.html`.

Целевой URL — https://httpbin.org (публичный эхо-сервис), замени BASE_URL на свой стенд.

## Маппинг JMeter → Gatling

| JMeter                        | Gatling                                                     |
|-------------------------------|-------------------------------------------------------------|
| Thread Group (ramp-up)        | `inject(rampUsers(10).during(30s))`                         |
| Thread Group (throughput)     | `inject(constantUsersPerSec(2).during(60s))`                |
| Setup Thread Group            | отдельный scenario + `atOnceUsers(1)`, `before`/`after`     |
| Once Only Controller          | `exec(login)` ДО `repeat(...)` — выполняется раз на VU      |
| Loop Controller               | `repeat(10, "i") { ... }`                                   |
| HTTP Header Manager (глоб.)   | `HttpProtocolBuilder.acceptHeader/contentTypeHeader/...`    |
| HTTP Header Manager (лок.)    | `.header(...)` на конкретном запросе                        |
| Cookie Manager                | включён по умолчанию, отдельно не нужен                     |
| CSV Data Set Config           | feeder: `csvFeeder` / `listFeeder(...).circular()`          |
| Constant Timer / Think time   | `pause(1)`                                                  |
| JSON Extractor                | `.check(jsonPath("$.x").saveAs("x"))`                       |
| Response Assertion            | `.check(status().is(200))` + `assertions(...)` в setUp      |
| Throughput Shaping Timer      | `constantUsersPerSec` / `throttle`                          |
| Stop Thread on error          | `.exitHereIfFailed()`                                       |

## Отличия по языкам (кратко)

- **Java**: официальный DSL с 3.7, `#{el}` синтаксис, verbose, но знакомый любому джависту.
- **Scala**: родной DSL Gatling — самый компактный и «канонический», `${el}` синтаксис,
  неявные конверсии, всё в одном `Predef`.
- **Kotlin**: использует тот же Java DSL API (`io.gatling.javaapi`), но лямбды/named code
  делают код заметно компактнее Java; null-safe и backticks для `is`/`in`.

Все три используют open-model (интенсивность в пользователях/сек), как Throughput Shaping Timer
в JMeter, а не фиксированное число потоков.

## Большие сценарии для сравнения языков

В модулях лежит «жирный» реалистичный план (одинаковая логика на всех языках) — для замера
влияния выбора языка на реальном объёме, а не на hello-world:

- **Customer journey** (~14 запросов/VU): home → CSRF (корреляция №1) → login
  (once only, `exitHereIfFailed`) → цикл 5× (каталог + карточка товара, SKU/qty рандомизируются,
  URL из ответа коррелируется в referer) → корзина (локальный Header Manager) → checkout
  (группа: orderId → оплата в ~40% прогонов через `doIf` — аналог If Controller) →
  подтверждение заказа → logout.
- **Guest search** (6 запросов/VU): поиск (feeder) → промо-баннер (корреляция заголовка) →
  карточка товара → медленный сторонний виджет (`/delay/1`) → подписка на рассылку.

Внутри: параметризация через feeders + рандом в session-лямбдах, четыре корреляции
(csrf → session → orderId → echo → подтверждение), Loop Controller, Constant Timer 1–2 c,
If Controller, groups для читаемых отчётов.

Нагрузка — два профиля одновременно:
- **open-model**: `nothingFor` → `rampUsers` → `constantUsersPerSec` → `rampUsersPerSec` →
  … (аналог Throughput Shaping Timer);
- **closed-model**: `rampConcurrentUsers` + `constantConcurrentUsers`
  (аналог классической Thread Group JMeter с фиксированным числом потоков).

Профили специально занижены под публичный httpbin.org (порядка единиц rps, ~5–7 минут прогон).
Для реального замера подними интенсивности и выставь свой BASE_URL.

### Как мерить влияние языка

1. Время компиляции: `mvn -q test-compile` в каждом модуле, сравни.
2. LOC и дифф на типовую правку: «добавь header всем POST», «вынеси таймер в конфиг»,
   «добавь проверку токена в каждый чекаут» — сделай одну правку в трёх модулях и сравни диффы.
3. Скорость написания нового сценария: два человека с разным бэкграундом пишут один сценарий.
4. Читаемость на ревью: обменяйся файлами и оцени, сколько времени ушло на разбор чужого кода.
5. Прогоны: логика идентична, так что цифры отчётов должны совпасть в пределах погрешности —
   если совпали, выбор языка влияет только на DX (разработку), а не на нагрузку.
