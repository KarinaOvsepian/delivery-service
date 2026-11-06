package ua.course.delivery.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;


class ShipmentApiTest {

    static final String BASE = baseUrl();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    static final long MISSING_ID = 999_999_999_999L;


    record Resp(int status, String location, JsonNode body) {
        long id() { return body.get("id").asLong(); }
        String errorCode() { return body.get("code").asText(); }
    }

    static String baseUrl() {
        String env = System.getenv("API_BASE_URL");
        return env == null || env.isBlank() ? "http://127.0.0.1:8080" : env.replaceAll("/+$", "");
    }

    static Resp send(String method, String path, String json) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(10));
            if (json == null) {
                builder.method(method, BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(json));
            }
            var response = HTTP.send(builder.build(), BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? null : JSON.readTree(response.body());
            return new Resp(response.statusCode(), response.headers().firstValue("Location").orElse(null), body);
        } catch (IOException e) {
            throw new UncheckedIOException("Запит " + method + " " + BASE + path + " не виконано", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Resp get(String path) { return send("GET", path, null); }
    static Resp post(String path, String json) { return send("POST", path, json); }
    static Resp action(long shipmentId, String action) { return send("POST", "/shipments/" + shipmentId + "/" + action, null); }


    static String unique(String prefix) { return prefix + UUID.randomUUID(); } // 2 + 36 = 38 символів <= 40

    static Resp createCustomer(String code, boolean regular) {
        ObjectNode n = JSON.createObjectNode().put("code", code).put("regular", regular);
        return post("/customers", n.toString());
    }

    static Resp createParcel(String code, int weightGrams, long declaredValue, boolean fragile) {
        ObjectNode n = JSON.createObjectNode().put("code", code).put("weightGrams", weightGrams)
                .put("declaredValue", declaredValue).put("fragile", fragile);
        return post("/parcels", n.toString());
    }

    static Resp createShipment(String reference, long customerId, long parcelId, int distanceKm,
                               boolean urgent, boolean pickup, boolean couponValid) {
        ObjectNode n = JSON.createObjectNode().put("reference", reference).put("customerId", customerId)
                .put("parcelId", parcelId).put("distanceKm", distanceKm).put("urgent", urgent)
                .put("pickup", pickup).put("couponValid", couponValid);
        return post("/shipments", n.toString());
    }

    static long newCustomerId(boolean regular) {
        Resp r = createCustomer(unique("C-"), regular);
        assertEquals(201, r.status(), "підготовка: клієнт має створитися");
        return r.id();
    }

    static long newParcelId(int weightGrams) {
        Resp r = createParcel(unique("P-"), weightGrams, 10_000, false);
        assertEquals(201, r.status(), "підготовка: посилка має створитися");
        return r.id();
    }

    static Resp newShipment() {
        Resp r = createShipment(unique("S-"), newCustomerId(false), newParcelId(1000), 100, false, false, false);
        assertEquals(201, r.status(), "підготовка: відправлення має створитися");
        return r;
    }

    static void assertError(Resp r, int status, String code) {
        assertEquals(status, r.status(), "HTTP-статус");
        assertNotNull(r.body(), "тіло помилки має бути JSON");
        assertEquals(code, r.errorCode(), "поле code");
        assertTrue(r.body().hasNonNull("message") && !r.body().get("message").asText().isBlank(),
                "поле message має бути непорожнім");
        assertEquals(2, r.body().size(), "error response містить лише code та message");
    }

    @BeforeAll
    static void sutIsRunning() {
        try {
            get("/shipments/" + MISSING_ID);
        } catch (RuntimeException e) {
            fail("SUT недоступний за адресою " + BASE + ". Запустіть застосунок (mvn spring-boot:run) "
                    + "або задайте API_BASE_URL. Причина: " + e.getMessage());
        }
    }

    // ================================================================ 1. POST — створення ресурсу (201)

    @Test
    @DisplayName("1. POST /shipments: 201, Location, id і основні поля response")
    void createShipment_returns201WithIdAndMainFields() {
        long customerId = newCustomerId(false);
        long parcelId = newParcelId(1000);
        String reference = unique("S-");

        Resp r = createShipment(reference, customerId, parcelId, 100, true, true, false);

        assertEquals(201, r.status());
        JsonNode b = r.body();
        assertTrue(b.get("id").asLong() > 0, "id має бути додатним");
        assertNotNull(r.location(), "Location має бути заповнений");
        assertTrue(r.location().endsWith("/shipments/" + r.id()), "Location вказує на новий ресурс: " + r.location());
        assertEquals(reference, b.get("reference").asText());
        assertEquals(customerId, b.get("customerId").asLong());
        assertEquals(parcelId, b.get("parcelId").asLong());
        assertEquals(100, b.get("distanceKm").asInt());
        assertTrue(b.get("urgent").asBoolean());
        assertTrue(b.get("pickup").asBoolean());
        assertEquals("Registered", b.get("status").asText());
        assertEquals(1000, b.get("weightGrams").asInt());
        assertEquals(10_000, b.get("declaredValue").asLong());
        assertFalse(b.get("fragile").asBoolean());
        assertFalse(b.get("regular").asBoolean());
        assertFalse(b.get("couponValid").asBoolean());
        assertEquals(7000, b.get("baseFee").asLong());
        assertEquals(0, b.get("adjustment").asLong());
        assertEquals(7000, b.get("total").asLong());
        assertEquals(0, b.get("refund").asLong());
    }

    @Test
    @DisplayName("1. POST /customers і /parcels: 201, Location, id та поля")
    void createCustomerAndParcel_return201() {
        String customerCode = unique("C-");
        Resp c = createCustomer(customerCode, true);
        assertEquals(201, c.status());
        assertTrue(c.id() > 0);
        assertTrue(c.location().endsWith("/customers/" + c.id()));
        assertEquals(customerCode, c.body().get("code").asText());
        assertTrue(c.body().get("regular").asBoolean());

        String parcelCode = unique("P-");
        Resp p = createParcel(parcelCode, 2500, 50_000, true);
        assertEquals(201, p.status());
        assertTrue(p.id() > 0);
        assertTrue(p.location().endsWith("/parcels/" + p.id()));
        assertEquals(parcelCode, p.body().get("code").asText());
        assertEquals(2500, p.body().get("weightGrams").asInt());
        assertEquals(50_000, p.body().get("declaredValue").asLong());
        assertTrue(p.body().get("fragile").asBoolean());
    }

    // ================================================================ 2. GET за id (200)

    @Test
    @DisplayName("2. GET /shipments/{id}: 200, дані збігаються зі створеним ресурсом")
    void getShipmentById_returnsCreatedResource() {
        Resp created = newShipment();

        Resp fetched = get("/shipments/" + created.id());

        assertEquals(200, fetched.status());
        assertEquals(created.body(), fetched.body(), "GET має повертати те саме, що повернув POST");
    }

    @Test
    @DisplayName("2. GET /customers/{id} і /parcels/{id}: 200 і збережені дані")
    void getCustomerAndParcelById_returnStoredData() {
        Resp c = createCustomer(unique("C-"), false);
        Resp p = createParcel(unique("P-"), 30_000, 100_000_000, false);

        Resp gc = get("/customers/" + c.id());
        Resp gp = get("/parcels/" + p.id());

        assertEquals(200, gc.status());
        assertEquals(c.body(), gc.body());
        assertEquals(200, gp.status());
        assertEquals(p.body(), gp.body());
    }

    // ================================================================ 3. GET неіснуючого ресурсу (404)

    @ParameterizedTest(name = "GET {0} → 404 NOT_FOUND")
    @ValueSource(strings = {"/shipments/" + MISSING_ID, "/customers/" + MISSING_ID, "/parcels/" + MISSING_ID})
    @DisplayName("3. GET неіснуючого ресурсу: 404, перевірка полів code і message")
    void getMissingResource_returns404(String path) {
        assertError(get(path), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("3. Повідомлення 404 для відправлення — точний текст")
    void getMissingShipment_hasExpectedMessage() {
        Resp r = get("/shipments/" + MISSING_ID);
        assertEquals(404, r.status());
        assertEquals("NOT_FOUND", r.body().get("code").asText());
        assertEquals("Відправлення не знайдено", r.body().get("message").asText());
    }

    @Test
    @DisplayName("3. Action над неіснуючим відправленням: 404")
    void actionOnMissingShipment_returns404() {
        for (String a : List.of("dispatch", "deliver", "cancel")) {
            assertError(action(MISSING_ID, a), 404, "NOT_FOUND");
        }
    }

    // ================================================================ 4. Action endpoint (200)

    @Test
    @DisplayName("4. dispatch: 200 і новий стан Dispatched, суми незмінні")
    void dispatch_returns200AndDispatchedState() {
        Resp created = newShipment();

        Resp r = action(created.id(), "dispatch");

        assertEquals(200, r.status());
        assertEquals("Dispatched", r.body().get("status").asText());
        assertEquals(0, r.body().get("refund").asLong());
        assertEquals(created.body().get("total"), r.body().get("total"));
        assertEquals(created.id(), r.id());
    }

    @Test
    @DisplayName("4. deliver після dispatch: 200 і стан Delivered")
    void deliver_returns200AndDeliveredState() {
        Resp created = newShipment();
        assertEquals(200, action(created.id(), "dispatch").status());

        Resp r = action(created.id(), "deliver");

        assertEquals(200, r.status());
        assertEquals("Delivered", r.body().get("status").asText());
        assertEquals(0, r.body().get("refund").asLong());
    }

    @Test
    @DisplayName("4. cancel для Registered: 200, Cancelled, refund = total")
    void cancel_returns200AndRefundsWholeTotal() {
        Resp created = newShipment();

        Resp r = action(created.id(), "cancel");

        assertEquals(200, r.status());
        assertEquals("Cancelled", r.body().get("status").asText());
        assertEquals(7000, r.body().get("refund").asLong());
        assertEquals(7000, r.body().get("total").asLong());
        assertEquals(created.body().get("baseFee"), r.body().get("baseFee"));
        assertEquals(created.body().get("adjustment"), r.body().get("adjustment"));
    }

    // ================================================================ 5. Повторна / заборонена дія (409)

    static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(
                Arguments.of("dispatch повторно", List.of("dispatch"), "dispatch"),
                Arguments.of("deliver до dispatch", List.<String>of(), "deliver"),
                Arguments.of("cancel після dispatch", List.of("dispatch"), "cancel"),
                Arguments.of("deliver повторно", List.of("dispatch", "deliver"), "deliver"),
                Arguments.of("cancel після deliver", List.of("dispatch", "deliver"), "cancel"),
                Arguments.of("cancel повторно", List.of("cancel"), "cancel"),
                Arguments.of("dispatch після cancel", List.of("cancel"), "dispatch"),
                Arguments.of("deliver після cancel", List.of("cancel"), "deliver"));
    }

    @ParameterizedTest(name = "{0} → 409")
    @MethodSource("forbiddenTransitions")
    @DisplayName("5. Повторна або заборонена дія: 409 і коректний error response")
    void forbiddenTransition_returns409(String name, List<String> prelude, String forbidden) {
        long id = newShipment().id();
        prelude.forEach(a -> assertEquals(200, action(id, a).status(), "підготовка: " + a));

        Resp r = action(id, forbidden);

        assertError(r, 409, "SHIPMENT_CONFLICT");
    }

    // ================================================================ 6. Незмінність після відхилення

    @ParameterizedTest(name = "{0}: ресурс не змінився")
    @MethodSource("forbiddenTransitions")
    @DisplayName("6. Після 409 наступний GET підтверджує, що ресурс не змінився")
    void afterRejectedAction_resourceIsUnchanged(String name, List<String> prelude, String forbidden) {
        long id = newShipment().id();
        prelude.forEach(a -> assertEquals(200, action(id, a).status(), "підготовка: " + a));
        Resp before = get("/shipments/" + id);
        assertEquals(200, before.status());

        assertEquals(409, action(id, forbidden).status());

        Resp after = get("/shipments/" + id);
        assertEquals(200, after.status());
        assertEquals(before.body(), after.body(), "статус, суми та refund мають лишитися незмінними");
    }

    // ================================================================ 7. Персональне правило — пріоритет промокоду (параметризація)

    /**
     * Тариф = 5000 + 1000 × ceil(кг) + 10 × км (копійки). Знижка = floor(baseFee × %/100); промокод 15%, інакше regular 7%, інакше 0; не складаються.
     */
    @ParameterizedTest(name = "{0} г, {1} км, coupon={2}, regular={3} → base {4}, adj {5}, total {6}")
    @CsvSource({
            // weight, km, coupon, regular, baseFee, adjustment, total
            "1000, 100, false, false, 7000, 0, 7000",        // немає знижки
            "1000, 100, false, true,  7000, -490, 6510",     // тільки regular: 7%
            "1000, 100, true,  false, 7000, -1050, 5950",    // тільки промокод: 15%
            "1000, 100, true,  true,  7000, -1050, 5950",    // обидва: діє лише 15%, не 22%
            "1001, 101, false, true,  8010, -560, 7450",     // 7% з 8010 = 560,7 → донизу 560
            "1001, 101, true,  false, 8010, -1201, 6809",    // 15% з 8010 = 1201,5 → донизу 1201
            "1,    1,   false, true,  6010, -420, 5590",     // мінімальний тариф, 7% = 420,7 → 420
            "1,    1,   true,  true,  6010, -901, 5109",     // мінімальний тариф, 15% = 901,5 → 901
            "30000, 5000, false, false, 85000, 0, 85000",    // максимальний тариф
            "30000, 5000, false, true, 85000, -5950, 79050", // максимум, 7%
            "30000, 5000, true,  true, 85000, -12750, 72250" // максимум, 15%
    })
    @DisplayName("7. Персональне правило: пріоритет промокоду (15% / 7% / 0%), округлення донизу")
    void personalRule_priorityOfCouponOverRegular(int weight, int km, boolean coupon, boolean regular,
                                                  long baseFee, long adjustment, long total) {
        long customerId = newCustomerId(regular);
        long parcelId = newParcelId(weight);

        Resp created = createShipment(unique("S-"), customerId, parcelId, km, false, false, coupon);

        assertEquals(201, created.status());
        assertEquals(baseFee, created.body().get("baseFee").asLong(), "baseFee");
        assertEquals(adjustment, created.body().get("adjustment").asLong(), "adjustment");
        assertEquals(total, created.body().get("total").asLong(), "total");
        assertEquals(coupon, created.body().get("couponValid").asBoolean());
        assertEquals(regular, created.body().get("regular").asBoolean());
        // збережені суми підтверджує GET
        Resp stored = get("/shipments/" + created.id());
        assertEquals(total, stored.body().get("total").asLong());
        assertEquals(adjustment, stored.body().get("adjustment").asLong());
    }

    @ParameterizedTest(name = "coupon={0}, regular={1}: refund = total = {2}")
    @CsvSource({"false, false, 7000", "false, true, 6510", "true, false, 5950", "true, true, 5950"})
    @DisplayName("7. Скасування повертає весь total після знижки (refund = total)")
    void cancelRefundsDiscountedTotal(boolean coupon, boolean regular, long expectedTotal) {
        Resp created = createShipment(unique("S-"), newCustomerId(regular), newParcelId(1000), 100, false, false, coupon);
        assertEquals(expectedTotal, created.body().get("total").asLong());

        Resp cancelled = action(created.id(), "cancel");

        assertEquals(200, cancelled.status());
        assertEquals(expectedTotal, cancelled.body().get("refund").asLong());
        assertEquals(expectedTotal, get("/shipments/" + created.id()).body().get("refund").asLong());
    }

    @ParameterizedTest(name = "urgent={0}, pickup={1}, fragile={2} не впливають на ціну")
    @CsvSource({"true, false, false", "false, true, false", "false, false, true", "true, true, true"})
    @DisplayName("7. Ознаки urgent / pickup / fragile не впливають на суми")
    void otherFlagsDoNotAffectPrice(boolean urgent, boolean pickup, boolean fragile) {
        long customerId = newCustomerId(false);
        Resp parcel = createParcel(unique("P-"), 1000, 10_000, fragile);
        assertEquals(201, parcel.status());

        Resp r = createShipment(unique("S-"), customerId, parcel.id(), 100, urgent, pickup, false);

        assertEquals(201, r.status());
        assertEquals(7000, r.body().get("baseFee").asLong());
        assertEquals(0, r.body().get("adjustment").asLong());
        assertEquals(7000, r.body().get("total").asLong());
        assertEquals(fragile, r.body().get("fragile").asBoolean());
    }

    // ================================================================ 8. Workflow: POST → GET → action → GET

    @Test
    @DisplayName("8. Workflow: customer → parcel → shipment → GET → dispatch → GET → deliver → GET (id динамічно з response)")
    void workflow_postGetActionGet_dispatchDeliver() {
        Resp customer = createCustomer(unique("C-"), true);
        assertEquals(201, customer.status());
        Resp parcel = createParcel(unique("P-"), 1500, 20_000, false);
        assertEquals(201, parcel.status());
        Resp created = createShipment(unique("S-"), customer.id(), parcel.id(), 250, false, false, false);
        assertEquals(201, created.status());
        long shipmentId = created.id();
        // 5000 + 2*1000 + 250*10 = 9500; regular 7% = 665 → 8835
        assertEquals(9500, created.body().get("baseFee").asLong());
        assertEquals(8835, created.body().get("total").asLong());

        Resp afterCreate = get("/shipments/" + shipmentId);
        assertEquals(200, afterCreate.status());
        assertEquals("Registered", afterCreate.body().get("status").asText());

        assertEquals(200, action(shipmentId, "dispatch").status());
        Resp afterDispatch = get("/shipments/" + shipmentId);
        assertEquals(200, afterDispatch.status());
        assertEquals("Dispatched", afterDispatch.body().get("status").asText());
        assertEquals(8835, afterDispatch.body().get("total").asLong());

        assertEquals(200, action(shipmentId, "deliver").status());
        Resp afterDeliver = get("/shipments/" + shipmentId);
        assertEquals("Delivered", afterDeliver.body().get("status").asText());
        assertEquals(0, afterDeliver.body().get("refund").asLong());
    }

    @Test
    @DisplayName("8. Workflow: POST → GET → cancel → GET (refund = total)")
    void workflow_postGetCancelGet() {
        Resp created = createShipment(unique("S-"), newCustomerId(false), newParcelId(2000), 100, false, false, true);
        assertEquals(201, created.status());
        long id = created.id();
        // 5000 + 2000 + 1000 = 8000; промокод 15% = 1200 → 6800
        assertEquals(6800, created.body().get("total").asLong());

        assertEquals("Registered", get("/shipments/" + id).body().get("status").asText());
        assertEquals(200, action(id, "cancel").status());

        Resp stored = get("/shipments/" + id);
        assertEquals("Cancelled", stored.body().get("status").asText());
        assertEquals(6800, stored.body().get("refund").asLong());
        assertEquals(6800, stored.body().get("total").asLong());
    }

    // ================================================================ Додаткові негативні сценарії (коди 400 / 404 / 409)

    @ParameterizedTest(name = "distanceKm={0} → 400")
    @ValueSource(ints = {0, -1, 5001})
    @DisplayName("Відстань поза межами 1–5000: 400 INVALID_REQUEST")
    void shipmentWithInvalidDistance_returns400(int km) {
        Resp r = createShipment(unique("S-"), newCustomerId(false), newParcelId(1000), km, false, false, false);
        assertError(r, 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Порожній reference або довший за 40 символів: 400")
    void shipmentWithInvalidReference_returns400() {
        long c = newCustomerId(false);
        long p = newParcelId(1000);
        assertError(createShipment("   ", c, p, 100, false, false, false), 400, "INVALID_REQUEST");
        assertError(createShipment("R".repeat(41), c, p, 100, false, false, false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Некоректні customerId / parcelId (<= 0): 400")
    void shipmentWithNonPositiveIds_returns400() {
        long c = newCustomerId(false);
        long p = newParcelId(1000);
        assertError(createShipment(unique("S-"), 0, p, 100, false, false, false), 400, "INVALID_REQUEST");
        assertError(createShipment(unique("S-"), c, -5, 100, false, false, false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Неіснуючий клієнт або посилка при створенні відправлення: 404")
    void shipmentWithMissingCustomerOrParcel_returns404() {
        assertError(createShipment(unique("S-"), MISSING_ID, newParcelId(1000), 100, false, false, false), 404, "NOT_FOUND");
        assertError(createShipment(unique("S-"), newCustomerId(false), MISSING_ID, 100, false, false, false), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("Дубль reference: 409 DUPLICATE_RESOURCE, перше відправлення не змінюється")
    void duplicateReference_returns409() {
        String reference = unique("S-");
        long c = newCustomerId(false);
        long p = newParcelId(1000);
        Resp first = createShipment(reference, c, p, 100, false, false, false);
        assertEquals(201, first.status());

        Resp second = createShipment(reference, c, p, 200, false, false, false);

        assertError(second, 409, "DUPLICATE_RESOURCE");
        assertEquals(first.body(), get("/shipments/" + first.id()).body());
    }

    @Test
    @DisplayName("Дубль code клієнта та посилки: 409 DUPLICATE_RESOURCE")
    void duplicateCustomerAndParcelCode_returns409() {
        String customerCode = unique("C-");
        assertEquals(201, createCustomer(customerCode, false).status());
        assertError(createCustomer(customerCode, true), 409, "DUPLICATE_RESOURCE");

        String parcelCode = unique("P-");
        assertEquals(201, createParcel(parcelCode, 1000, 0, false).status());
        assertError(createParcel(parcelCode, 1000, 0, false), 409, "DUPLICATE_RESOURCE");
    }

    @ParameterizedTest(name = "вага {0} г → 400")
    @ValueSource(ints = {0, -1, 30001})
    @DisplayName("Посилка з вагою поза межами 1–30000: 400")
    void parcelWithInvalidWeight_returns400(int weight) {
        assertError(createParcel(unique("P-"), weight, 1000, false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Посилка з оголошеною вартістю поза 0–100 000 000: 400")
    void parcelWithInvalidDeclaredValue_returns400() {
        assertError(createParcel(unique("P-"), 1000, -1, false), 400, "INVALID_REQUEST");
        assertError(createParcel(unique("P-"), 1000, 100_000_001L, false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Порожній code клієнта: 400")
    void customerWithBlankCode_returns400() {
        assertError(createCustomer("  ", false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Код клієнта довший за 40 символів: 400 (специфікація: code до 40 символів)")
    void customerCodeLongerThan40_returns400() {
        assertError(createCustomer("C".repeat(41), false), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("Некоректний JSON і нечисловий id: 400")
    void malformedRequests_return400() {
        assertError(post("/shipments", "{not-json"), 400, "INVALID_REQUEST");
        assertError(get("/shipments/abc"), 400, "INVALID_REQUEST");
    }
}
