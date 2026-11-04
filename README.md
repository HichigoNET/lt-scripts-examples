# Gatling vs k6: Java / Scala / Kotlin примеры

Четыре независимых проекта с ОДНИМ И ТОМ ЖЕ «жирным» реалистичным сценарием, чтобы
сравнить реализацию и DX 1-в-1. Сценарий повторяет стандартный JMeter-план: Header Manager,
Cookie Manager, Once Only Controller, Loop Controller, Constant Timer, CSV Data Set Config,
If Controller, thread groups с ramp-up и интенсивностью.

## Структура

```
gatling-java/    pom.xml + src/test/java/load/JavaSimulation.java    (официальный Java DSL)
gatling-scala/   pom.xml + src/test/scala/load/ScalaSimulation.scala (классический Scala DSL)
gatling-kotlin/  pom.xml + src/test/kotlin/load/KotlinSimulation.kt  (Java DSL API + идиоматичный Kotlin)
k6/              customer-journey.js                                  (Grafana k6, JavaScript)
```

## Запуск

```bash
cd gatling-java   && mvn gatling:test
cd gatling-scala  && mvn gatling:test
cd gatling-kotlin && mvn gatling:test
cd k6             && k6 run customer-journey.js
```

Отчёты Gatling: `target/gatling/<sim>-<ts>/index.html`. Сводка k6 — в stdout
(опционально `--summary-export=summary.json`).

Целевой URL — https://httpbin.org (публичный эхо-сервис), замени BASE_URL на свой стенд.

## Сценарий (одинаков во всех четырёх)

- **Customer journey** (~14 запросов/VU): home → CSRF (корреляция №1) → login
  (once only, stop-on-error) → цикл 5× (каталог + карточка, SKU/qty рандомизируются,
  URL из ответа → referer корзины) → корзина (локальный Header Manager) → checkout
  (orderId → оплата в ~40% прогонов) → подтверждение → logout.
- **Guest search** (6 запросов/VU): поиск → промо-баннер (корреляция заголовка) →
  карточка товара → медленный сторонний виджет → подписка на рассылку.

Нагрузка — два профиля одновременно:
- **open-model** (интенсивность в юзерах/сек, аналог Throughput Shaping Timer):
  Gatling `injectOpen(...)`, k6 executor `ramping-arrival-rate`;
- **closed-model** (фиксированное число активных VU, аналог Thread Group JMeter):
  Gatling `injectClosed(...)`, k6 executor `ramping-vus`.

Профили занижены под публичный httpbin.org (~5–7 минут прогон). Для реального замера
подними интенсивности и выставь свой BASE_URL.

## Маппинг JMeter → Gatling → k6

| JMeter                      | Gatling                                                  | k6                                        |
|-----------------------------|----------------------------------------------------------|-------------------------------------------|
| Thread Group (ramp-up)      | `rampUsers(10).during(30s)`                               | `ramping-vus` / `ramping-arrival-rate`    |
| Thread Group (throughput)   | `constantUsersPerSec(2).during(60s)`                      | `constant-arrival-rate`                   |
| Setup Thread Group          | отдельный scenario + `atOnceUsers(1)` / `before`          | `setup()`                                 |
| Once Only Controller        | `exec(login)` ДО `repeat(...)`                            | top-level `let` (per-VU)                  |
| Loop Controller             | `repeat(10, "i")`                                         | `for`                                     |
| Header Manager (глоб.)      | `HttpProtocolBuilder.*`                                   | `BASE_HEADERS`                            |
| Header Manager (лок.)       | `.header(...)` на запросе                                 | `headers: {...}` на запросе               |
| Cookie Manager              | включён по умолчанию                                      | cookie jar per-VU по умолчанию            |
| CSV Data Set Config         | `csvFeeder` / `listFeeder(...).circular()`                | `SharedArray` + индекс по `__VU`          |
| Constant Timer              | `pause(1)`                                                | `sleep(1 + Math.random())`                |
| JSON Extractor              | `.check(jsonPath("$.x").saveAs("x"))`                     | `resp.json('$.x')`                        |
| Response Assertion          | `.check(status().is(200))` + `assertions(...)`            | `check()` + `thresholds`                  |
| Throughput Shaping Timer    | `constantUsersPerSec` / `throttle`                        | `ramping-arrival-rate` (stages)           |
| Stop Thread on error        | `.exitHereIfFailed()`                                     | `return` из iteration function            |
| If Controller               | `doIf(...)`                                               | `if (Math.random() < p)`                  |

## Отличия по языкам (кратко)

- **Java**: официальный DSL с 3.7, `#{el}` синтаксис, verbose, но знакомый любому джависту.
- **Scala**: родной DSL Gatling — самый компактный и «канонический», `${el}` синтаксис.
- **Kotlin**: тот же Java DSL API (`io.gatling.javaapi`), лямбды делают код компактнее Java;
  null-safe и backticks для `is`/`in`.
- **k6**: не JVM — движок Go + встроенный JS. Порог входа низкий, CI/Grafana из коробки,
  но цифры нагрузки с Gatling напрямую несравнимы (другой HTTP-клиент и планировщик).

## Как мерить влияние языка/инструмента

1. Время компиляции: `mvn -q test-compile` в каждом gatling-модуле (k6 компилирует на лету).
2. LOC и дифф на типовую правку: «добавь header всем POST», «вынеси таймер в конфиг»,
   «добавь проверку токена в чекаут» — сделай одну правку во всех модулях и сравни диффы.
3. Скорость написания нового сценария: два человека с разным бэкграундом пишут один сценарий.
4. Читаемость на ревью: обменяйся файлами и оцени, сколько времени ушло на разбор чужого кода.
5. Прогоны: логика идентична внутри gatling-* (цифры должны совпасть в пределах погрешности).
   k6 — другой движок: используй для сравнения DX/поддержки, не абсолютных цифр.
