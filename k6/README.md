# k6 (Grafana k6)

Аналог «жирного» сценария из модулей `gatling-java` / `gatling-scala` / `gatling-kotlin`:
та же логика (customer journey ~14 запросов + guest search 6 запросов, те же четыре
корреляции, feeders, once-only, if-контроллер, think times), но на JavaScript.

## Запуск

```bash
# установка: https://grafana.com/docs/k6/latest/set-up/install-k6/
brew install k6    # macOS
choco install k6   # Windows

cd k6
k6 run customer-journey.js

# экспорт сводки для CI:
k6 run --summary-export=summary.json customer-journey.js
```

## Маппинг JMeter → k6

| JMeter                        | k6                                                          |
|-------------------------------|-------------------------------------------------------------|
| Thread Group (throughput)     | executor `ramping-arrival-rate` / `constant-arrival-rate`   |
| Thread Group (фикс. потоки)   | executor `ramping-vus` / `constant-vus`                     |
| Setup Thread Group            | `setup()` — раз на весь тест                                |
| Once Only Controller          | top-level переменная (per-VU) или `setup()`                 |
| Loop Controller               | `for (let i = 0; ...)`                                      |
| HTTP Header Manager (глоб.)   | `BASE_HEADERS` во всех запросах                             |
| HTTP Header Manager (лок.)    | `headers: { ...BASE_HEADERS, 'X-...': ... }` на запросе     |
| Cookie Manager                | cookie jar per-VU, включён по умолчанию                     |
| CSV Data Set Config           | `SharedArray` + индекс по `__VU`                            |
| Constant Timer                | `sleep(1 + Math.random())`                                  |
| JSON Extractor                | `resp.json('$.path')`                                       |
| Response Assertion            | `check()` (per-request) + `thresholds` (агрегаты)           |
| If Controller                 | `if (Math.random() < 0.4)`                                  |

## Отличия от gatling-* модулей

- **Не JVM**: движок на Go + встроенный JS-контекст. Цифры нагрузки с Gatling напрямую
  сравнивать нельзя (другой HTTP-клиент и планировщик) — сравнивай DX и экосистему.
- **thresholds вместо assertions**: порог проверяется по агрегату за тест (провал теста),
  per-request проверки — через `check()`.
- **Session = top-level `let`**: каждый VU — отдельный JS-контекст, переменные верхнего
  уровня живут per-VU между итерациями. Аналог Gatling Session без явного API.
- Нет Maven/Gradle: один `.js` файл, вся сборка — это сам бинарь k6. CI-интеграция
  из коробки (JSON/InfluxDB/Prometheus/Grafana, `k6/cloud`).

Важный нюанс for «once only»: `setup()` в k6 выполняется раз на весь ТЕСТ, а не на VU —
поэтому логин по пользователю из фидера делается лениво в первой итерации VU
(см. `loginOnce`), как Once Only Controller в JMeter.
