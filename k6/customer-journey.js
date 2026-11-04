import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { SharedArray } from 'k6/data';

/**
 * k6: ТА ЖЕ логика, что в gatling-java / gatling-scala / gatling-kotlin —
 * для честного сравнения DX между движками.
 *
 * Соответствие элементам JMeter/Gatling:
 *  - CSV Data Set Config / feeder    -> SharedArray + индекс по __VU
 *  - Header Manager (глоб.)          -> BASE_HEADERS во всех запросах
 *  - Header Manager (лок.)           -> headers на конкретном http.post
 *  - Cookie Manager                  -> cookie jar per-VU, включён по умолчанию
 *  - Once Only Controller            -> if (!session.sessionToken) loginOnce()
 *  - Loop Controller                 -> for (let i = 0; i < 5; i++)
 *  - Constant Timer                  -> sleep(1 + Math.random())
 *  - If Controller                   -> if (Math.random() < 0.4) pay()
 *  - JSON Extractor / saveAs         -> resp.json('$.path') в session
 *  - Throughput Shaping Timer (open) -> ramping-arrival-rate
 *  - Thread Group (closed)           -> ramping-vus
 *  - Response Assertions             -> check() + thresholds
 *
 * Customer journey ~14 запросов/итерация, Guest search ~6 запросов/итерация.
 * Корреляции: csrf -> session -> orderId -> echo -> подтверждение (как в Gatling-версиях).
 */

const BASE_URL = 'https://httpbin.org'; // TODO: свой стенд
const CATEGORIES = ['electronics', 'books', 'sports'];

// ~ CSV Data Set Config: зарегистрированные покупатели
const users = new SharedArray('users', () => [
  { login: 'u1', password: 'p1', segment: 'b2c', device: 'desktop' },
  { login: 'u2', password: 'p2', segment: 'b2c', device: 'mobile' },
  { login: 'u3', password: 'p3', segment: 'b2b', device: 'desktop' },
  { login: 'u4', password: 'p4', segment: 'b2c', device: 'mobile' },
  { login: 'u5', password: 'p5', segment: 'b2b', device: 'tablet' },
]);

// ~ CSV Data Set Config: гостевые поисковые запросы
const searches = new SharedArray('searches', () => [
  { query: 'laptop', email: 'guest1@example.com' },
  { query: 'smartphone', email: 'guest2@example.com' },
  { query: 'headphones', email: 'guest3@example.com' },
  { query: 'smart watch', email: 'guest4@example.com' },
  { query: 'e-reader', email: 'guest5@example.com' },
]);

// ~ HTTP Header Manager (глобальный): заголовки по умолчанию для всех запросов
const BASE_HEADERS = {
  Accept: 'application/json',
  'Content-Type': 'application/json',
  'User-Agent': 'k6-benchmark',
};

// ~ «Session»: ВАЖНО, что в k6 каждый VU — отдельный JS-контекст, поэтому
// top-level переменная живёт per-VU между итерациями. Аналог Gatling Session.
let session = {};

// query string с кодированием (как .queryParam в Gatling)
function qs(params) {
  return Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
}

// ~ JSON Extractor + saveAs: сохраняем значение из ответа в session
function save(res, path, key) {
  const value = res.json(path);
  if (value === undefined) {
    console.error(`correlation failed: ${path}`);
    return false;
  }
  session[key] = value;
  return true;
}

// ~ Once Only Controller: логин ОДИН раз на VU (top-level state живёт между итерациями).
// Возврат false прерывает итерацию — аналог exitHereIfFailed (Stop Thread on error).
function loginOnce(user) {
  if (session.sessionToken) return true;

  // Корреляция №1: серверный токен из ответа
  const csrfRes = http.get(`${BASE_URL}/uuid`, {
    headers: BASE_HEADERS,
    tags: { name: 'Fetch CSRF token' },
  });
  if (!check(csrfRes, { 'csrf: status 200': (r) => r.status === 200 })) return false;
  if (!save(csrfRes, 'uuid', 'csrfToken')) return false;

  const loginRes = http.post(
    `${BASE_URL}/post`,
    JSON.stringify({
      login: user.login,
      password: user.password,
      csrf_token: session.csrfToken,
      remember: true,
    }),
    { headers: BASE_HEADERS, tags: { name: 'Login' } }
  );
  if (!check(loginRes, { 'login: status 200': (r) => r.status === 200 })) return false;
  // Корреляция №2: session token из тела ответа
  if (!save(loginRes, 'json.csrf_token', 'sessionToken')) return false;
  return true;
}

export function customerJourney() {
  const user = users[(__VU - 1) % users.length];

  // 1. Open home page (параметризация device из фидера)
  session.page = 1 + Math.floor(Math.random() * 5);
  session.category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
  const home = http.get(`${BASE_URL}/get?${qs({ ref: 'home', device: user.device })}`, {
    headers: BASE_HEADERS,
    tags: { name: 'Open home page' },
  });
  if (!check(home, { 'home: status 200': (r) => r.status === 200 })) return;

  // 2-3. Once Only: csrf + login
  if (!loginOnce(user)) return;

  // 4. Loop Controller x5 + Constant Timer (параметризация SKU/qty рандомом)
  for (let i = 0; i < 5; i++) {
    session.sku = `SKU-${100 + Math.floor(Math.random() * 900)}`;
    session.qty = 1 + Math.floor(Math.random() * 3);

    const catalog = http.get(
      `${BASE_URL}/get?${qs({ path: '/catalog', category: session.category, page: session.page, cursor: i })}`,
      { headers: BASE_HEADERS, tags: { name: 'Open catalog page' } }
    );
    if (!check(catalog, { 'catalog: status 200': (r) => r.status === 200 })) return;

    const product = http.get(
      `${BASE_URL}/get?${qs({ path: '/product', sku: session.sku, ref: 'catalog' })}`,
      { headers: BASE_HEADERS, tags: { name: 'Open product page' } }
    );
    if (!check(product, { 'product: status 200': (r) => r.status === 200 })) return;
    // Корреляция №3: URL из ответа уходит referer-ом в корзину
    save(product, 'url', 'productUrl');

    sleep(1 + Math.random()); // think time 1-2с
  }

  // 5. Cart (локальный Header Manager на запросе)
  group('cart', () => {
    const add = http.post(
      `${BASE_URL}/post`,
      JSON.stringify({ sku: session.sku, qty: session.qty, referer: session.productUrl }),
      {
        headers: { ...BASE_HEADERS, 'X-Session-Token': session.sessionToken },
        tags: { name: 'Add to cart' },
      }
    );
    check(add, {
      'cart add: status 200': (r) => r.status === 200,
      'cart add: sku echoed': (r) => r.json('json.sku') !== undefined,
    });

    const cart = http.get(`${BASE_URL}/get?${qs({ path: '/cart', last_sku: session.sku })}`, {
      headers: BASE_HEADERS,
      tags: { name: 'Open cart' },
    });
    check(cart, { 'cart: status 200': (r) => r.status === 200 });
  });

  // 6. Checkout (group) + If Controller: оплата ~40% итераций
  group('checkout', () => {
    // Корреляция №4: orderId до оплаты
    const orderRes = http.get(`${BASE_URL}/uuid`, {
      headers: BASE_HEADERS,
      tags: { name: 'Create order id' },
    });
    if (!check(orderRes, { 'order id: status 200': (r) => r.status === 200 })) return;
    if (!save(orderRes, 'uuid', 'orderId')) return;

    session.amount = 50 + Math.floor(Math.random() * 950);
    session.requestId = `${__VU}-${Date.now()}`;

    const checkoutRes = http.post(
      `${BASE_URL}/post`,
      JSON.stringify({ order_id: session.orderId, amount: session.amount, currency: 'USD' }),
      {
        headers: {
          ...BASE_HEADERS,
          'X-Session-Token': session.sessionToken,
          'X-Request-Id': session.requestId,
        },
        tags: { name: 'Checkout' },
      }
    );
    if (!check(checkoutRes, { 'checkout: status 200': (r) => r.status === 200 })) return;
    // эхо order_id обратно в session
    save(checkoutRes, 'json.order_id', 'echoedOrderId');

    if (Math.random() < 0.4) {
      const payRes = http.post(
        `${BASE_URL}/post`,
        JSON.stringify({ order_id: session.orderId, method: 'card', pan_mask: '4111 **** **** 1234' }),
        { headers: BASE_HEADERS, tags: { name: 'Pay order' } }
      );
      check(payRes, {
        'pay: status 200': (r) => r.status === 200,
        'pay: method echoed': (r) => r.json('json.method') !== undefined,
      });
    }
  });

  // 7. Order confirmation
  const confirm = http.get(`${BASE_URL}/get?${qs({ path: '/order', order_id: session.orderId })}`, {
    headers: BASE_HEADERS,
    tags: { name: 'Open order confirmation' },
  });
  check(confirm, {
    'confirm: status 200': (r) => r.status === 200,
    'confirm: order_id echoed': (r) => r.json('args.order_id') !== undefined,
  });

  // 8. Logout
  const logout = http.post(
    `${BASE_URL}/post`,
    JSON.stringify({ login: user.login, logout: true }),
    {
      headers: { ...BASE_HEADERS, 'X-Session-Token': session.sessionToken },
      tags: { name: 'Logout' },
    }
  );
  check(logout, { 'logout: status 200': (r) => r.status === 200 });
}

// Второй сценарий: гость без логина — корреляция без сессии
export function guestSearch() {
  const s = searches[(__VU - 1) % searches.length];
  session.page = 1 + Math.floor(Math.random() * 3);

  const search = http.get(
    `${BASE_URL}/get?${qs({ path: '/search', q: s.query, page: session.page })}`,
    { headers: BASE_HEADERS, tags: { name: 'Search products' } }
  );
  if (!check(search, { 'search: status 200': (r) => r.status === 200 })) return;
  save(search, 'args.q', 'searchEcho');

  const promo = http.get(`${BASE_URL}/json`, {
    headers: BASE_HEADERS,
    tags: { name: 'Open promo banner' },
  });
  if (!check(promo, { 'promo: status 200': (r) => r.status === 200 })) return;
  save(promo, 'slideshow.title', 'promoTitle');

  session.sku = `SKU-${100 + Math.floor(Math.random() * 900)}`;
  const product = http.get(
    `${BASE_URL}/get?${qs({ path: '/product', sku: session.sku, promo: session.promoTitle })}`,
    { headers: BASE_HEADERS, tags: { name: 'Open product from promo' } }
  );
  if (!check(product, { 'promo product: status 200': (r) => r.status === 200 })) return;

  // ~ медленный сторонний сервис
  const widget = http.get(`${BASE_URL}/delay/1?${qs({ path: '/third-party' })}`, {
    headers: BASE_HEADERS,
    tags: { name: 'Load recommendations widget' },
  });
  if (!check(widget, { 'widget: status 200': (r) => r.status === 200 })) return;

  const subscribe = http.post(
    `${BASE_URL}/post`,
    JSON.stringify({ email: s.email, source: session.promoTitle }),
    { headers: BASE_HEADERS, tags: { name: 'Subscribe to newsletter' } }
  );
  check(subscribe, { 'newsletter: status 200': (r) => r.status === 200 });
}

export const options = {
  scenarios: {
    customer_journey: {
      // ~ Open-model (аналог Throughput Shaping Timer): интенсивность в итерациях/сек
      executor: 'ramping-arrival-rate',
      exec: 'customerJourney',
      startRate: 0,
      timeUnit: '1s',
      stages: [
        { target: 1, duration: '45s' }, // ~ rampUsers(10).during(45s)
        { target: 1, duration: '60s' }, // ~ constantUsersPerSec(1).during(60s)
        { target: 2, duration: '30s' }, // ~ rampUsersPerSec(0.5 -> 2).during(30s)
        { target: 2, duration: '90s' }, // ~ constantUsersPerSec(2).during(90s)
        { target: 3, duration: '30s' }, // ~ финальный ramp
      ],
      preAllocatedVUs: 50,
      maxVUs: 100,
      gracefulStop: '30s',
    },
    guest_search: {
      // ~ Closed-model (аналог классической Thread Group): фиксированное число VU
      executor: 'ramping-vus',
      exec: 'guestSearch',
      startVUs: 2,
      stages: [
        { target: 10, duration: '90s' }, // ~ rampConcurrentUsers(2 -> 10).during(90s)
        { target: 10, duration: '60s' }, // ~ constantConcurrentUsers(10).during(60s)
      ],
      gracefulStop: '30s',
    },
  },
  // ~ Response Assertions: агрегированные пороги (провал теста, а не запроса)
  thresholds: {
    http_req_duration: ['p(95)<2500'],
    http_req_failed: ['rate<0.05'],
    http_reqs: ['rate>1'],
  },
};
