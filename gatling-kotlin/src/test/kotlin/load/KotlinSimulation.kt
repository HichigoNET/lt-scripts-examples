package load

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.*
import io.gatling.javaapi.http.HttpDsl.*
import io.gatling.javaapi.http.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Большой реалистичный сценарий для сравнения языков (та же логика на Java / Kotlin / Scala / k6).
 * Java DSL API + лямбды Kotlin; `is`/`in` — в backticks.
 *
 * Customer journey ~14 запросов/VU, Guest search ~6 запросов/VU.
 * Параметризация: feeders + рандом в session-лямбдах.
 * Корреляции: csrf -> session -> orderId -> echo -> подтверждение.
 */
class KotlinSimulation : Simulation() {

  private val baseUrl = "https://httpbin.org" // TODO: свой стенд

  // ~ Header Manager: заголовки по умолчанию для всех запросов
  private val httpProtocol: HttpProtocolBuilder = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("gatling-kotlin-benchmark")
    .shareConnections()

  // ~ CSV Data Set Config: зарегистрированные покупатели
  private val userFeeder: FeederBuilder<*> = listFeeder(
    listOf(
      mapOf("login" to "u1", "password" to "p1", "segment" to "b2c", "device" to "desktop"),
      mapOf("login" to "u2", "password" to "p2", "segment" to "b2c", "device" to "mobile"),
      mapOf("login" to "u3", "password" to "p3", "segment" to "b2b", "device" to "desktop"),
      mapOf("login" to "u4", "password" to "p4", "segment" to "b2c", "device" to "mobile"),
      mapOf("login" to "u5", "password" to "p5", "segment" to "b2b", "device" to "tablet")
    )
  ).circular()

  // ~ CSV Data Set Config: гостевые поисковые запросы
  private val searchFeeder: FeederBuilder<*> = listFeeder(
    listOf(
      mapOf("query" to "laptop", "email" to "guest1@example.com"),
      mapOf("query" to "smartphone", "email" to "guest2@example.com"),
      mapOf("query" to "headphones", "email" to "guest3@example.com"),
      mapOf("query" to "smart watch", "email" to "guest4@example.com"),
      mapOf("query" to "e-reader", "email" to "guest5@example.com")
    )
  ).circular()

  // ---- Пользовательские шаги ------------------------------------------------

  private val openHome: ChainBuilder = exec(
    http("Open home page")
      .get("/get")
      .queryParam("ref", "home")
      .queryParam("device", "#{device}")
      .check(status().`is`(200))
  )
    .exec { session: Session ->
      session.set("page", ThreadLocalRandom.current().nextInt(1, 6).toString())
    }
    .exec { session: Session ->
      session.set("category", CATEGORIES[ThreadLocalRandom.current().nextInt(CATEGORIES.size)])
    }

  // Корреляция №1: серверный токен, извлечённый из ответа
  private val fetchCsrf: ChainBuilder = exec(
    http("Fetch CSRF token")
      .get("/uuid")
      .check(status().`is`(200))
      .check(jsonPath("$.uuid").saveAs("csrfToken"))
  )

  // ~ Once Only Controller: раз на VU, до цикла
  private val login: ChainBuilder = exec(
    http("Login")
      .post("/post")
      .body(StringBody("""{"login":"#{login}","password":"#{password}","csrf_token":"#{csrfToken}","remember":true}"""))
      .check(status().`is`(200))
      .check(jsonPath("$.json.csrf_token").saveAs("sessionToken"))
  ).exitHereIfFailed() // ~ Stop Thread on error

  // ~ Loop Controller + Constant Timer; параметризация SKU/qty рандомом
  private val browseProducts: ChainBuilder = repeat(5, "i").on(
    exec { session: Session ->
      val rnd = ThreadLocalRandom.current()
      session.set("sku", String.format("SKU-%03d", rnd.nextInt(100, 1000)))
        .set("qty", rnd.nextInt(1, 4).toString())
    }
      .exec(
        http("Open catalog page")
          .get("/get")
          .queryParam("path", "/catalog")
          .queryParam("category", "#{category}")
          .queryParam("page", "#{page}")
          .queryParam("cursor", "#{i}")
          .check(status().`is`(200))
      )
      .exec(
        http("Open product page")
          .get("/get")
          .queryParam("path", "/product")
          .queryParam("sku", "#{sku}")
          .queryParam("ref", "catalog")
          .check(status().`is`(200))
          // Корреляция №2: URL из ответа уходит referer-ом в корзину
          .check(jsonPath("$.url").saveAs("productUrl"))
      )
      .pause(Duration.ofSeconds(1), Duration.ofSeconds(2)) // think time 1-2с
  )

  private val cart: ChainBuilder = group("cart").on(
    exec(
      http("Add to cart")
        .post("/post")
        .header("X-Session-Token", "#{sessionToken}") // ~ локальный Header Manager
        .body(StringBody("""{"sku":"#{sku}","qty":#{qty},"referer":"#{productUrl}"}"""))
        .check(status().`is`(200))
        .check(jsonPath("$.json.sku").exists())
    )
      .exec(
        http("Open cart")
          .get("/get")
          .queryParam("path", "/cart")
          .queryParam("last_sku", "#{sku}")
          .check(status().`is`(200))
      )
  )

  private val checkout: ChainBuilder = group("checkout").on(
    // Корреляция №3: orderId создаётся до оплаты, уходит в тело и подтверждение
    exec(
      http("Create order id")
        .get("/uuid")
        .check(status().`is`(200))
        .check(jsonPath("$.uuid").saveAs("orderId"))
    )
      .exec { session: Session ->
        session.set("amount", ThreadLocalRandom.current().nextInt(50, 1000).toString())
          .set("requestId", UUID.randomUUID().toString())
      }
      .exec(
        http("Checkout")
          .post("/post")
          .header("X-Session-Token", "#{sessionToken}")
          .header("X-Request-Id", "#{requestId}")
          .body(StringBody("""{"order_id":"#{orderId}","amount":#{amount},"currency":"USD"}"""))
          .check(status().`is`(200))
          // Корреляция №4: круговой прогон значения — эхо order_id сохраняем обратно
          .check(jsonPath("$.json.order_id").saveAs("echoedOrderId"))
      )
      // ~ If Controller: оплачиваем ~40% корзин
      .doIf(
        { session: Session -> ThreadLocalRandom.current().nextInt(100) < 40 },
        exec(
          http("Pay order")
            .post("/post")
            .body(StringBody("""{"order_id":"#{orderId}","method":"card","pan_mask":"4111 **** **** 1234"}"""))
            .check(status().`is`(200))
            .check(jsonPath("$.json.method").exists())
        )
      )
  )

  private val confirmOrder: ChainBuilder = exec(
    http("Open order confirmation")
      .get("/get")
      .queryParam("path", "/order")
      .queryParam("order_id", "#{orderId}")
      .check(status().`is`(200))
      .check(jsonPath("$.args.order_id").exists())
  )

  private val logout: ChainBuilder = exec(
    http("Logout")
      .post("/post")
      .header("X-Session-Token", "#{sessionToken}")
      .body(StringBody("""{"login":"#{login}","logout":true}"""))
      .check(status().`is`(200))
  )

  private val customerJourney: ScenarioBuilder = scenario("Customer journey")
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
  private val guestSearch: ScenarioBuilder = scenario("Guest search")
    .feed(searchFeeder)
    .exec { session: Session -> session.set("page", ThreadLocalRandom.current().nextInt(1, 4).toString()) }
    .exec(
      http("Search products")
        .get("/get")
        .queryParam("path", "/search")
        .queryParam("q", "#{query}")
        .queryParam("page", "#{page}")
        .check(status().`is`(200))
        .check(jsonPath("$.args.q").saveAs("searchEcho"))
    )
    .exec(
      http("Open promo banner")
        .get("/json")
        .check(status().`is`(200))
        .check(jsonPath("$.slideshow.title").saveAs("promoTitle"))
    )
    .exec { session: Session ->
      session.set("sku", String.format("SKU-%03d", ThreadLocalRandom.current().nextInt(100, 1000)))
    }
    .exec(
      http("Open product from promo")
        .get("/get")
        .queryParam("path", "/product")
        .queryParam("sku", "#{sku}")
        .queryParam("promo", "#{promoTitle}") // параметризация значением из ответа
        .check(status().`is`(200))
    )
    .exec(
      http("Load recommendations widget") // ~ медленный сторонний сервис
        .get("/delay/1")
        .queryParam("path", "/third-party")
        .check(status().`is`(200))
    )
    .exec(
      http("Subscribe to newsletter")
        .post("/post")
        .body(StringBody("""{"email":"#{email}","source":"#{promoTitle}"}"""))
        .check(status().`is`(200))
    )

  init {
    setUp(
      // ~ Open-model: интенсивность в юзерах/сек (аналог Throughput Shaping Timer)
      customerJourney.injectOpen(
          nothingFor(Duration.ofSeconds(5)),
          rampUsers(10).during(Duration.ofSeconds(45)),
          constantUsersPerSec(1).during(Duration.ofSeconds(60)),
          rampUsersPerSec(0.5).to(2).during(Duration.ofSeconds(30)),
          constantUsersPerSec(2).during(Duration.ofSeconds(90)),
          rampUsers(10).during(Duration.ofSeconds(30))
        ).protocols(httpProtocol),
      // ~ Closed-model: фиксированное число активных VU (аналог Thread Group JMeter)
      guestSearch.injectClosed(
          rampConcurrentUsers(2).to(10).during(Duration.ofSeconds(90)),
          constantConcurrentUsers(10).during(Duration.ofSeconds(60))
        ).protocols(httpProtocol)
    ).assertions(
      global().responseTime().percentile3().lt(2500),
      global().failedRequests().percent().lt(5.0),
      global().requestsPerSec().gt(1.0)
    ).maxDuration(Duration.ofSeconds(600))
  }

  companion object {
    private val CATEGORIES = arrayOf("electronics", "books", "sports")
  }
}
