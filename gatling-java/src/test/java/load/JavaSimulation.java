package load;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Большой реалистичный сценарий для сравнения языков (та же логика на Java / Kotlin / Scala / k6).
 *
 * JMeter-аналоги:
 *  - CSV Data Set Config        -> userFeeder / searchFeeder (параметризация)
 *  - Header Manager (глоб.)     -> httpProtocol
 *  - Header Manager (лок.)      -> .header(...) на корзине/чекауте
 *  - Once Only Controller       -> login вне repeat (раз на VU)
 *  - Loop Controller            -> repeat(5, "i") по карточкам товара
 *  - Constant Timer             -> pause(1s..2s) uniform
 *  - If Controller              -> doIf(...) — оплата в ~40% прогонов
 *  - JSON Extractor             -> saveAs (корреляция csrf -> session -> orderId -> echo)
 *  - Throughput Shaping Timer   -> open-model inject (ramp / constantUsersPerSec / rampUsersPerSec)
 *  - Thread Group (фикс.потоки) -> closed-model inject (rampConcurrentUsers), как в JMeter
 *
 * Customer journey ~14 запросов/VU, Guest search ~6 запросов/VU.
 */
public class JavaSimulation extends Simulation {

  private static final String BASE_URL = "https://httpbin.org"; // TODO: свой стенд
  private static final String[] CATEGORIES = {"electronics", "books", "sports"};

  // ~ Header Manager: заголовки по умолчанию для всех запросов
  HttpProtocolBuilder httpProtocol = http
      .baseUrl(BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json")
      .userAgentHeader("gatling-java-benchmark")
      .shareConnections();

  // ~ CSV Data Set Config: зарегистрированные покупатели
  FeederBuilder<?> userFeeder = listFeeder(List.of(
      Map.of("login", "u1", "password", "p1", "segment", "b2c", "device", "desktop"),
      Map.of("login", "u2", "password", "p2", "segment", "b2c", "device", "mobile"),
      Map.of("login", "u3", "password", "p3", "segment", "b2b", "device", "desktop"),
      Map.of("login", "u4", "password", "p4", "segment", "b2c", "device", "mobile"),
      Map.of("login", "u5", "password", "p5", "segment", "b2b", "device", "tablet"))).circular();

  // ~ CSV Data Set Config: гостевые поисковые запросы
  FeederBuilder<?> searchFeeder = listFeeder(List.of(
      Map.of("query", "laptop", "email", "guest1@example.com"),
      Map.of("query", "smartphone", "email", "guest2@example.com"),
      Map.of("query", "headphones", "email", "guest3@example.com"),
      Map.of("query", "smart watch", "email", "guest4@example.com"),
      Map.of("query", "e-reader", "email", "guest5@example.com"))).circular();

  // ---- Пользовательские шаги ------------------------------------------------

  ChainBuilder openHome = exec(http("Open home page")
          .get("/get")
          .queryParam("ref", "home")
          .queryParam("device", "#{device}")
          .check(status().is(200)))
      .exec(session -> session.set("page", String.valueOf(ThreadLocalRandom.current().nextInt(1, 6))))
      .exec(session -> session.set("category", CATEGORIES[ThreadLocalRandom.current().nextInt(CATEGORIES.length)]));

  // Корреляция №1: серверный токен, извлечённый из ответа
  ChainBuilder fetchCsrf = exec(http("Fetch CSRF token")
          .get("/uuid")
          .check(status().is(200))
          .check(jsonPath("$.uuid").saveAs("csrfToken")));

  // ~ Once Only Controller: раз на VU, до цикла
  ChainBuilder login = exec(http("Login")
          .post("/post")
          .body(StringBody(
              "{\"login\":\"#{login}\",\"password\":\"#{password}\",\"csrf_token\":\"#{csrfToken}\",\"remember\":true}"))
          .check(status().is(200))
          .check(jsonPath("$.json.csrf_token").saveAs("sessionToken")))
      .exitHereIfFailed(); // ~ Stop Thread on error

  // ~ Loop Controller + Constant Timer; параметризация SKU/qty рандомом
  ChainBuilder browseProducts = repeat(5, "i").on(
      exec(session -> {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return session.set("sku", String.format("SKU-%03d", rnd.nextInt(100, 1000)))
            .set("qty", String.valueOf(rnd.nextInt(1, 4)));
      })
          .exec(http("Open catalog page")
              .get("/get")
              .queryParam("path", "/catalog")
              .queryParam("category", "#{category}")
              .queryParam("page", "#{page}")
              .queryParam("cursor", "#{i}")
              .check(status().is(200)))
          .exec(http("Open product page")
              .get("/get")
              .queryParam("path", "/product")
              .queryParam("sku", "#{sku}")
              .queryParam("ref", "catalog")
              .check(status().is(200))
              // Корреляция №2: URL из ответа уходит referer-ом в корзину
              .check(jsonPath("$.url").saveAs("productUrl")))
          .pause(Duration.ofSeconds(1), Duration.ofSeconds(2))); // think time 1-2с

  ChainBuilder cart = group("cart").on(
      exec(http("Add to cart")
          .post("/post")
          .header("X-Session-Token", "#{sessionToken}") // ~ локальный Header Manager
          .body(StringBody("{\"sku\":\"#{sku}\",\"qty\":#{qty},\"referer\":\"#{productUrl}\"}"))
          .check(status().is(200))
          .check(jsonPath("$.json.sku").exists()))
          .exec(http("Open cart")
              .get("/get")
              .queryParam("path", "/cart")
              .queryParam("last_sku", "#{sku}")
              .check(status().is(200))));

  ChainBuilder checkout = group("checkout").on(
      // Корреляция №3: orderId создаётся до оплаты, уходит в тело и подтверждение
      exec(http("Create order id")
          .get("/uuid")
          .check(status().is(200))
          .check(jsonPath("$.uuid").saveAs("orderId")))
          .exec(session -> session.set("amount", String.valueOf(ThreadLocalRandom.current().nextInt(50, 1000)))
              .set("requestId", UUID.randomUUID().toString()))
          .exec(http("Checkout")
              .post("/post")
              .header("X-Session-Token", "#{sessionToken}")
              .header("X-Request-Id", "#{requestId}")
              .body(StringBody("{\"order_id\":\"#{orderId}\",\"amount\":#{amount},\"currency\":\"USD\"}"))
              .check(status().is(200))
              // Корреляция №4: круговой прогон значения — эхо order_id сохраняем обратно
              .check(jsonPath("$.json.order_id").saveAs("echoedOrderId")))
          // ~ If Controller: оплачиваем ~40% корзин
          .doIf(session -> ThreadLocalRandom.current().nextInt(100) < 40,
              exec(http("Pay order")
                  .post("/post")
                  .body(StringBody(
                      "{\"order_id\":\"#{orderId}\",\"method\":\"card\",\"pan_mask\":\"4111 **** **** 1234\"}"))
                  .check(status().is(200))
                  .check(jsonPath("$.json.method").exists()))));

  ChainBuilder confirmOrder = exec(http("Open order confirmation")
      .get("/get")
      .queryParam("path", "/order")
      .queryParam("order_id", "#{orderId}")
      .check(status().is(200))
      .check(jsonPath("$.args.order_id").exists()));

  ChainBuilder logout = exec(http("Logout")
      .post("/post")
      .header("X-Session-Token", "#{sessionToken}")
      .body(StringBody("{\"login\":\"#{login}\",\"logout\":true}"))
      .check(status().is(200)));

  ScenarioBuilder customerJourney = scenario("Customer journey")
      .feed(userFeeder)
      .exec(openHome)
      .exec(fetchCsrf)
      .exec(login)
      .exec(browseProducts)
      .exec(cart)
      .exec(checkout)
      .exec(confirmOrder)
      .exec(logout);

  // Второй сценарий: гость без логина — корреляция без сессии
  ScenarioBuilder guestSearch = scenario("Guest search")
      .feed(searchFeeder)
      .exec(session -> session.set("page", String.valueOf(ThreadLocalRandom.current().nextInt(1, 4))))
      .exec(http("Search products")
          .get("/get")
          .queryParam("path", "/search")
          .queryParam("q", "#{query}")
          .queryParam("page", "#{page}")
          .check(status().is(200))
          .check(jsonPath("$.args.q").saveAs("searchEcho")))
      .exec(http("Open promo banner")
          .get("/json")
          .check(status().is(200))
          .check(jsonPath("$.slideshow.title").saveAs("promoTitle")))
      .exec(session -> session.set("sku", String.format("SKU-%03d", ThreadLocalRandom.current().nextInt(100, 1000))))
      .exec(http("Open product from promo")
          .get("/get")
          .queryParam("path", "/product")
          .queryParam("sku", "#{sku}")
          .queryParam("promo", "#{promoTitle}") // параметризация значением из ответа
          .check(status().is(200)))
      .exec(http("Load recommendations widget") // ~ медленный сторонний сервис
          .get("/delay/1")
          .queryParam("path", "/third-party")
          .check(status().is(200)))
      .exec(http("Subscribe to newsletter")
          .post("/post")
          .body(StringBody("{\"email\":\"#{email}\",\"source\":\"#{promoTitle}\"}"))
          .check(status().is(200)));

  {
    setUp(
        // ~ Open-model: интенсивность в юзерах/сек (аналог Throughput Shaping Timer)
        customerJourney.injectOpen(
                nothingFor(Duration.ofSeconds(5)),
                rampUsers(10).during(Duration.ofSeconds(45)),
                constantUsersPerSec(1).during(Duration.ofSeconds(60)),
                rampUsersPerSec(0.5).to(2).during(Duration.ofSeconds(30)),
                constantUsersPerSec(2).during(Duration.ofSeconds(90)),
                rampUsers(10).during(Duration.ofSeconds(30)))
            .protocols(httpProtocol),
        // ~ Closed-model: фиксированное число активных VU (аналог Thread Group JMeter)
        guestSearch.injectClosed(
                rampConcurrentUsers(2).to(10).during(Duration.ofSeconds(90)),
                constantConcurrentUsers(10).during(Duration.ofSeconds(60)))
            .protocols(httpProtocol))
        .assertions(
            global().responseTime().percentile3().lt(2500),
            global().failedRequests().percent().lt(5.0),
            global().requestsPerSec().gt(1.0))
        .maxDuration(Duration.ofSeconds(600));
  }
}
