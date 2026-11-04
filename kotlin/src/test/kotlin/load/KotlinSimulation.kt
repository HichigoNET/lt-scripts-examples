package load

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.*
import io.gatling.javaapi.http.HttpDsl.*
import io.gatling.javaapi.http.*
import java.time.Duration
import java.util.UUID

/**
 * Аналог JMeter-плана:
 *  - HTTP Header Manager (глобальный)  -> HttpProtocolBuilder
 *  - Cookie Manager                    -> включён в Gatling по умолчанию
 *  - CSV Data Set Config               -> feeder
 *  - Once Only Controller              -> exec(login) ДО repeat
 *  - Loop Controller + Constant Timer  -> repeat(10) + pause(1)
 *  - Thread Groups                     -> inject(rampUsers / constantUsersPerSec / atOnceUsers)
 *
 * Kotlin использует официальный Java DSL API (io.gatling.javaapi):
 * те же возможности, но лямбды/трейлинг-синтаксис делают код компактнее Java.
 * `is` и `in` — ключевые слова Kotlin, поэтому в backticks: `is`(200), `in`(200, 201).
 */
class KotlinSimulation : Simulation() {

  private val baseUrl = "https://httpbin.org" // TODO: свой стенд

  // ~ HTTP Header Manager: заголовки для всех запросов
  private val httpProtocol: HttpProtocolBuilder = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("gatling-kotlin-example")
    .shareConnections() // keep-alive, общий пул соединений

  // ~ CSV Data Set Config
  private val userFeeder: FeederBuilder<*> = listFeeder(
    listOf(mapOf<String, Any>("login" to "user1", "password" to "pass1"))
  ).circular()

  // ~ Once Only Controller: логин ОДИН раз на виртуального пользователя, вне цикла
  private val login: ChainBuilder = exec(
    http("Login")
      .post("/post")
      .body(StringBody("""{"login":"#{login}","password":"#{password}"}"""))
      .check(status().`is`(200))
      .check(jsonPath("$.origin").saveAs("origin"))
  ).exitHereIfFailed() // ~ «Action after error: Stop Thread»

  private val mainScenario: ScenarioBuilder = scenario("Main flow")
    .feed(userFeeder)
    .exec(login)
    .repeat(10, "i").on(                       // ~ Loop Controller
      exec(http("Browse page")
        .get("/get")
        .queryParam("page", "#{i}"))
        .pause(1)                              // ~ Constant Timer (think time)
    )
    .exec { session: Session -> session.set("requestId", UUID.randomUUID().toString()) }
    .exec(
      http("Submit form")
        .post("/post")
        .header("X-Request-Id", "#{requestId}") // ~ локальный Header Manager на запросе
        .body(StringBody("""{"q":"gatling","i":#{i}}"""))
        .check(status().`in`(200, 201))
        .check(jsonPath("$.json.q").saveAs("echo"))
    )

  // ~ Setup Thread Group: один прогон smoke
  private val smokeScenario: ScenarioBuilder = scenario("Smoke")
    .exec(http("Health check").get("/status/200").check(status().`is`(200)))

  init {
    setUp(
      // ~ Thread Group «main»: ramp-up 10 юзеров за 30с, затем 2 юзера/сек на протяжении 60с
      mainScenario.injectOpen(
        rampUsers(10).during(Duration.ofSeconds(30)),
        constantUsersPerSec(2).during(Duration.ofSeconds(60))
      ).protocols(httpProtocol),
      smokeScenario.injectOpen(atOnceUsers(1)).protocols(httpProtocol)
    ).assertions(
      global().responseTime().percentile3().lt(500), // ~ Response Time Percentiles
      global().failedRequests().percent().lt(5.0)
    ).maxDuration(Duration.ofSeconds(120))
  }
}
