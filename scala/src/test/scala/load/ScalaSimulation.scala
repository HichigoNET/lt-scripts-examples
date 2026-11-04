package load

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/**
 * Аналог JMeter-плана:
 *  - HTTP Header Manager (глобальный)  -> httpProtocol
 *  - Cookie Manager                    -> включён в Gatling по умолчанию
 *  - CSV Data Set Config               -> feeder
 *  - Once Only Controller              -> exec(login) ДО repeat
 *  - Loop Controller + Constant Timer  -> repeat(10) + pause(1)
 *  - Thread Groups                     -> inject(rampUsers / constantUsersPerSec / atOnceUsers)
 *
 * Scala DSL — родной/канонический DSL Gatling: короче всех, всё через Predef.
 */
class ScalaSimulation extends Simulation {

  private val baseUrl = "https://httpbin.org" // TODO: свой стенд

  // ~ HTTP Header Manager: заголовки для всех запросов
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("gatling-scala-example")
    .shareConnections // keep-alive, общий пул соединений

  // ~ CSV Data Set Config
  val userFeeder = Array(
    Map("login" -> "user1", "password" -> "pass1")
  ).circular

  // ~ Once Only Controller: логин ОДИН раз на виртуального пользователя, вне цикла
  val login =
    exec(
      http("Login")
        .post("/post")
        .body(StringBody("""{"login":"${login}","password":"${password}"}"""))
        .check(status.is(200))
        .check(jsonPath("$.origin").saveAs("origin"))
    ).exitHereIfFailed // ~ «Action after error: Stop Thread»

  val mainScenario =
    scenario("Main flow")
      .feed(userFeeder)
      .exec(login)
      .repeat(10, "i") { // ~ Loop Controller
        exec(http("Browse page").get("/get").queryParam("page", "#{i}"))
          .pause(1) // ~ Constant Timer (think time)
      }
      .exec(session => session.set("requestId", java.util.UUID.randomUUID().toString))
      .exec(
        http("Submit form")
          .post("/post")
          .header("X-Request-Id", "#{requestId}") // ~ локальный Header Manager на запросе
          .body(StringBody("""{"q":"gatling","i":${i}}"""))
          .check(status.in(200 to 201))
          .check(jsonPath("$.json.q").saveAs("echo"))
      )

  // ~ Setup Thread Group: один прогон smoke
  val smokeScenario =
    scenario("Smoke").exec(http("Health check").get("/status/200").check(status.is(200)))

  setUp(
    // ~ Thread Group «main»: ramp-up 10 юзеров за 30с, затем 2 юзера/сек на протяжении 60с
    mainScenario.inject(
      rampUsers(10).during(30.seconds),
      constantUsersPerSec(2).during(60.seconds)
    ),
    smokeScenario.inject(atOnceUsers(1))
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(500), // ~ Response Time Percentiles
      global.failedRequests.percent.lt(5.0)
    )
    .maxDuration(120.seconds)
}
