package load

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/**
 * Большой реалистичный сценарий для сравнения языков (та же логика на Java / Kotlin / Scala / k6).
 * Родной Scala DSL — самый компактный из трёх.
 *
 * Customer journey ~14 запросов/VU, Guest search ~6 запросов/VU.
 * Параметризация: feeders + Random в session-лямбдах.
 * Корреляции: csrf -> session -> orderId -> echo -> подтверждение.
 */
class ScalaSimulation extends Simulation {

  private val baseUrl = "https://httpbin.org" // TODO: свой стенд

  private val categories = Vector("electronics", "books", "sports")

  // ~ Header Manager: заголовки по умолчанию для всех запросов
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("gatling-scala-benchmark")
    .shareConnections

  // ~ CSV Data Set Config: зарегистрированные покупатели
  val userFeeder = Array(
    Map("login" -> "u1", "password" -> "p1", "segment" -> "b2c", "device" -> "desktop"),
    Map("login" -> "u2", "password" -> "p2", "segment" -> "b2c", "device" -> "mobile"),
    Map("login" -> "u3", "password" -> "p3", "segment" -> "b2b", "device" -> "desktop"),
    Map("login" -> "u4", "password" -> "p4", "segment" -> "b2c", "device" -> "mobile"),
    Map("login" -> "u5", "password" -> "p5", "segment" -> "b2b", "device" -> "tablet")
  ).circular

  // ~ CSV Data Set Config: гостевые поисковые запросы
  val searchFeeder = Array(
    Map("query" -> "laptop", "email" -> "guest1@example.com"),
    Map("query" -> "smartphone", "email" -> "guest2@example.com"),
    Map("query" -> "headphones", "email" -> "guest3@example.com"),
    Map("query" -> "smart watch", "email" -> "guest4@example.com"),
    Map("query" -> "e-reader", "email" -> "guest5@example.com")
  ).circular

  // ---- Пользовательские шаги ------------------------------------------------

  val openHome =
    exec(
      http("Open home page")
        .get("/get")
        .queryParam("ref", "home")
        .queryParam("device", "#{device}")
        .check(status.is(200))
    )
    .exec(session => session.set("page", Random.between(1, 6)))
    .exec(session => session.set("category", categories(Random.nextInt(categories.length))))

  // Корреляция №1: серверный токен, извлечённый из ответа
  val fetchCsrf =
    exec(
      http("Fetch CSRF token")
        .get("/uuid")
        .check(status.is(200))
        .check(jsonPath("$.uuid").saveAs("csrfToken"))
    )

  // ~ Once Only Controller: раз на VU, до цикла
  val login =
    exec(
      http("Login")
        .post("/post")
        .body(StringBody("""{"login":"${login}","password":"${password}","csrf_token":"${csrfToken}","remember":true}"""))
        .check(status.is(200))
        .check(jsonPath("$.json.csrf_token").saveAs("sessionToken"))
    ).exitHereIfFailed // ~ Stop Thread on error

  // ~ Loop Controller + Constant Timer; параметризация SKU/qty рандомом
  val browseProducts =
    repeat(5, "i") {
      exec(session =>
        session
          .set("sku", f"SKU-${Random.between(100, 1000)}%03d")
          .set("qty", Random.between(1, 4))
      )
      .exec(
        http("Open catalog page")
          .get("/get")
          .queryParam("path", "/catalog")
          .queryParam("category", "#{category}")
          .queryParam("page", "#{page}")
          .queryParam("cursor", "#{i}")
          .check(status.is(200))
      )
      .exec(
        http("Open product page")
          .get("/get")
          .queryParam("path", "/product")
          .queryParam("sku", "#{sku}")
          .queryParam("ref", "catalog")
          .check(status.is(200))
          // Корреляция №2: URL из ответа уходит referer-ом в корзину
          .check(jsonPath("$.url").saveAs("productUrl"))
      )
      .pause(1.second, 2.seconds) // think time 1-2с
    }

  val cart =
    group("cart") {
      exec(
        http("Add to cart")
          .post("/post")
          .header("X-Session-Token", "#{sessionToken}") // ~ локальный Header Manager
          .body(StringBody("""{"sku":"#{sku}","qty":#{qty},"referer":"#{productUrl}"}"""))
          .check(status.is(200))
          .check(jsonPath("$.json.sku").exists)
      )
      .exec(
        http("Open cart")
          .get("/get")
          .queryParam("path", "/cart")
          .queryParam("last_sku", "#{sku}")
          .check(status.is(200))
      )
    }

  val checkout =
    group("checkout") {
      // Корреляция №3: orderId создаётся до оплаты, уходит в тело и подтверждение
      exec(
        http("Create order id")
          .get("/uuid")
          .check(status.is(200))
          .check(jsonPath("$.uuid").saveAs("orderId"))
      )
      .exec(session =>
        session
          .set("amount", Random.between(50, 1000))
          .set("requestId", java.util.UUID.randomUUID.toString)
      )
      .exec(
        http("Checkout")
          .post("/post")
          .header("X-Session-Token", "#{sessionToken}")
          .header("X-Request-Id", "#{requestId}")
          .body(StringBody("""{"order_id":"#{orderId}","amount":#{amount},"currency":"USD"}"""))
          .check(status.is(200))
          // Корреляция №4: круговой прогон значения — эхо order_id сохраняем обратно
          .check(jsonPath("$.json.order_id").saveAs("echoedOrderId"))
      )
      // ~ If Controller: оплачиваем ~40% корзин
      .doIf(session => Random.nextInt(100) < 40) {
        exec(
          http("Pay order")
            .post("/post")
            .body(StringBody("""{"order_id":"#{orderId}","method":"card","pan_mask":"4111 **** **** 1234"}"""))
            .check(status.is(200))
            .check(jsonPath("$.json.method").exists)
        )
      }
    }

  val confirmOrder =
    exec(
      http("Open order confirmation")
        .get("/get")
        .queryParam("path", "/order")
        .queryParam("order_id", "#{orderId}")
        .check(status.is(200))
        .check(jsonPath("$.args.order_id").exists)
    )

  val logout =
    exec(
      http("Logout")
        .post("/post")
        .header("X-Session-Token", "#{sessionToken}")
        .body(StringBody("""{"login":"${login}","logout":true}"""))
        .check(status.is(200))
    )

  val customerJourney =
    scenario("Customer journey")
      .feed(userFeeder)
      .exec(openHome)
      .exec(fetchCsrf)
      .exec(login)
      .exec(browseProducts)
      .exec(cart)
      .exec(checkout)
      .exec(confirmOrder)
      .exec(logout)

  // Второй сценарий: гость без логина — корреляция без сессии
  val guestSearch =
    scenario("Guest search")
      .feed(searchFeeder)
      .exec(session => session.set("page", Random.between(1, 4)))
      .exec(
        http("Search products")
          .get("/get")
          .queryParam("path", "/search")
          .queryParam("q", "#{query}")
          .queryParam("page", "#{page}")
          .check(status.is(200))
          .check(jsonPath("$.args.q").saveAs("searchEcho"))
      )
      .exec(
        http("Open promo banner")
          .get("/json")
          .check(status.is(200))
          .check(jsonPath("$.slideshow.title").saveAs("promoTitle"))
      )
      .exec(session => session.set("sku", f"SKU-${Random.between(100, 1000)}%03d"))
      .exec(
        http("Open product from promo")
          .get("/get")
          .queryParam("path", "/product")
          .queryParam("sku", "#{sku}")
          .queryParam("promo", "#{promoTitle}") // параметризация значением из ответа
          .check(status.is(200))
      )
      .exec(
        http("Load recommendations widget") // ~ медленный сторонний сервис
          .get("/delay/1")
          .queryParam("path", "/third-party")
          .check(status.is(200))
      )
      .exec(
        http("Subscribe to newsletter")
          .post("/post")
          .body(StringBody("""{"email":"${email}","source":"${promoTitle}"}"""))
          .check(status.is(200))
      )

  setUp(
    // ~ Open-model: интенсивность в юзерах/сек (аналог Throughput Shaping Timer)
    customerJourney.inject(
      nothingFor(5.seconds),
      rampUsers(10).during(45.seconds),
      constantUsersPerSec(1).during(60.seconds),
      rampUsersPerSec(0.5).to(2).during(30.seconds),
      constantUsersPerSec(2).during(90.seconds),
      rampUsers(10).during(30.seconds)
    ),
    // ~ Closed-model: фиксированное число активных VU (аналог Thread Group JMeter)
    guestSearch.inject(
      rampConcurrentUsers(2).to(10).during(90.seconds),
      constantConcurrentUsers(10).during(60.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(2500),
      global.failedRequests.percent.lt(5.0),
      global.requestsPerSec.gt(1.0)
    )
    .maxDuration(10.minutes)
}
