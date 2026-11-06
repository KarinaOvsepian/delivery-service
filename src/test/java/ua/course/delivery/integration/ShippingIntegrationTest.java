package ua.course.delivery.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.course.delivery.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.Random.class)
@DisplayName("SUT B: integration tests (Testcontainers PostgreSQL + WireMock)")
class ShippingIntegrationTest {

    private static final String POLICY_PATH = "/shipping-policy";
    private static final String ALLOWED = "{\"allowed\":true}";
    private static final String DENIED = "{\"allowed\":false}";

    private Connection connection;
    private Connection verifier;
    private WireMock wireMock;
    private CustomerRepository customers;
    private ParcelRepository parcels;
    private ShipmentRepository shipments;
    private ShippingService service;

    private Customer customer;          // initial data: звичайний клієнт
    private Parcel parcel;              // initial data: 1000 г, tarif при 100 км = 7000 коп.

    @BeforeEach
    void setUp() throws SQLException {
        connection = TestEnvironment.newConnection();
        verifier = TestEnvironment.newConnection();
        wireMock = TestEnvironment.wireMockClient();

        try (var st = verifier.createStatement()) {
            st.executeUpdate("TRUNCATE TABLE shipments, parcels, customers RESTART IDENTITY CASCADE");
        }
        wireMock.resetMappings();
        wireMock.resetRequests();

        customers = new CustomerRepository(connection);
        parcels = new ParcelRepository(connection);
        shipments = new ShipmentRepository(connection);
        service = serviceWithTimeout(Duration.ofSeconds(2));

        customer = customers.create(new Customer(0, "CUST-1", false));
        parcel = parcels.create(new Parcel(0, "PARCEL-1", 1000, 10_000, false));
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
        verifier.close();
    }

    @Test
    @DisplayName("Середовище: міграції застосовані автоматично, тест стартує з чистого стану")
    void environmentIsMigratedAndClean() throws SQLException {
        try (var st = verifier.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM information_schema.tables "
                     + "WHERE table_schema='public' AND table_name IN ('customers','parcels','shipments')")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
        assertEquals(0, countShipments());
        assertEquals(List.of(), wireMock.find(anyRequestedFor(anyUrl())), "journal WireMock має бути порожнім");
    }

    @Test
    @DisplayName("Успіх: WireMock allowed=true → запис Registered у PostgreSQL, 1 HTTP-запит з правильним тілом")
    void allowedByExternalPolicy_persistsShipment() throws SQLException {
        stubPolicy(200, ALLOWED);

        Shipment created = service.createShipment(
                new ShipmentRequest("REF-OK", customer.id(), parcel.id(), 100, true, true, false));

        assertEquals(ShipmentStatus.Registered, created.status());

        assertEquals(1, countShipments());
        try (var st = verifier.prepareStatement(
                "SELECT reference,customer_id,parcel_id,distance_km,urgent,pickup,regular,coupon_valid,fragile,"
                        + "weight_grams,declared_value,base_fee,adjustment,total,refund,status FROM shipments WHERE id=?")) {
            st.setLong(1, created.id());
            try (var rs = st.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REF-OK", rs.getString("reference"));
                assertEquals(customer.id(), rs.getLong("customer_id"));
                assertEquals(parcel.id(), rs.getLong("parcel_id"));
                assertEquals(100, rs.getInt("distance_km"));
                assertTrue(rs.getBoolean("urgent"));
                assertTrue(rs.getBoolean("pickup"));
                assertFalse(rs.getBoolean("regular"));
                assertFalse(rs.getBoolean("coupon_valid"));
                assertFalse(rs.getBoolean("fragile"));
                assertEquals(1000, rs.getInt("weight_grams"));
                assertEquals(10_000L, rs.getLong("declared_value"));
                // 5000 + 1*1000 + 100*10 = 7000; знижки немає
                assertEquals(7000L, rs.getLong("base_fee"));
                assertEquals(0L, rs.getLong("adjustment"));
                assertEquals(7000L, rs.getLong("total"));
                assertEquals(0L, rs.getLong("refund"));
                assertEquals("Registered", rs.getString("status"));
            }
        }

        assertEquals(created, shipments.findById(created.id()));

        assertSinglePolicyCall(customer.id(), parcel.id(), 100);
    }

    @ParameterizedTest(name = "coupon={0}, regular={1} → знижка {2} коп.")
    @DisplayName("Персональне правило: промокод 15% > постійний клієнт 7% > 0%, знижки не додаються")
    @CsvSource({
            "true,  true,  1050",
            "true,  false, 1050",
            "false, true,  490",
            "false, false, 0"
    })
    void couponPriorityDiscountIsPersisted(boolean coupon, boolean regular, long discount) throws SQLException {
        Customer c = customers.create(new Customer(0, "CUST-RULE", regular));
        stubPolicy(200, ALLOWED);

        Shipment created = service.createShipment(
                new ShipmentRequest("REF-RULE", c.id(), parcel.id(), 100, false, false, coupon));

        Shipment inDb = shipments.findById(created.id());
        assertNotNull(inDb);
        assertEquals(7000L, inDb.baseFee());
        assertEquals(-discount, inDb.adjustment());
        assertEquals(7000L - discount, inDb.total());
        assertEquals(coupon, inDb.couponValid());
        assertEquals(regular, inDb.regular());
        assertEquals(0L, inDb.refund());
        assertSinglePolicyCall(c.id(), parcel.id(), 100);
    }

    @Test
    @DisplayName("Персональне правило: відсоток округлюється донизу один раз (7330 коп. → 1099 і 513)")
    void discountIsRoundedDown() throws SQLException {
        Parcel p = parcels.create(new Parcel(0, "PARCEL-ROUND", 1500, 10_000, false)); // 2 розпочаті кг
        Customer regular = customers.create(new Customer(0, "CUST-ROUND", true));
        stubPolicy(200, ALLOWED);

        // base = 5000 + 2000 + 33*10 = 7330
        Shipment withCoupon = service.createShipment(
                new ShipmentRequest("REF-ROUND-1", regular.id(), p.id(), 33, false, false, true));
        Shipment withoutCoupon = service.createShipment(
                new ShipmentRequest("REF-ROUND-2", regular.id(), p.id(), 33, false, false, false));

        assertEquals(-1099L, shipments.findById(withCoupon.id()).adjustment());
        assertEquals(6231L, shipments.findById(withCoupon.id()).total());
        assertEquals(-513L, shipments.findById(withoutCoupon.id()).adjustment());
        assertEquals(6817L, shipments.findById(withoutCoupon.id()).total());
        assertEquals(2, requestsToPolicy().size());
    }

    @Test
    @DisplayName("Зовнішня відмова: allowed=false → ShipmentRejectedException, запису немає, HTTP = 1")
    void deniedByExternalPolicy_noRecord() throws SQLException {
        stubPolicy(200, DENIED);

        assertThrows(ShipmentRejectedException.class, () -> service.createShipment(
                new ShipmentRequest("REF-DENIED", customer.id(), parcel.id(), 50, false, false, false)));

        assertEquals(0, countShipments());
        assertTrue(shipments.findShipments(customer.id(), ShipmentStatus.Registered).isEmpty());
        assertSinglePolicyCall(customer.id(), parcel.id(), 50);
    }

    @ParameterizedTest(name = "HTTP {0}, body={1} → IllegalStateException")
    @DisplayName("Недоступність/некоректна відповідь зовнішнього сервісу → IllegalStateException, запису немає, HTTP = 1")
    @CsvSource(delimiter = '|', value = {
            "503 | ",                         // Service Unavailable
            "500 | {\"error\":\"boom\"}",     // Internal Server Error
            "404 | ",                         // endpoint не знайдено
            "200 | not-a-json",               // некоректний JSON
            "200 | {}",                       // відсутнє поле allowed
            "200 | {\"allowed\":\"true\"}"    // нелогічне поле allowed
    })
    void externalServiceFailure_noRecord(int status, String body) throws SQLException {
        stubPolicy(status, body == null ? "" : body);

        assertThrows(IllegalStateException.class, () -> service.createShipment(
                new ShipmentRequest("REF-FAIL", customer.id(), parcel.id(), 70, false, false, false)));

        assertEquals(0, countShipments());
        assertTrue(shipments.findShipments(customer.id(), ShipmentStatus.Registered).isEmpty());
        assertSinglePolicyCall(customer.id(), parcel.id(), 70);
    }

    @Test
    @DisplayName("Таймаут: відповідь WireMock повільніша за таймаут → IllegalStateException, запису немає, HTTP = 1")
    void externalServiceTimeout_noRecord() throws SQLException {
        wireMock.register(post(urlEqualTo(POLICY_PATH)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(ALLOWED).withFixedDelay(3_000)));
        ShippingService fast = serviceWithTimeout(Duration.ofSeconds(1));

        assertThrows(IllegalStateException.class, () -> fast.createShipment(
                new ShipmentRequest("REF-SLOW", customer.id(), parcel.id(), 70, false, false, false)));

        assertEquals(0, countShipments());
        awaitPolicyRequests(1);
        assertEquals(1, requestsToPolicy().size());
    }

    @Test
    @DisplayName("Локальна відмова: відстань поза межами → IllegalArgumentException, запису немає, HTTP = 0")
    void localRejection_invalidDistance_noHttp() throws SQLException {
        stubPolicy(200, ALLOWED);

        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("REF-DIST", customer.id(), parcel.id(), 5001, false, false, false)));
        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("REF-DIST", customer.id(), parcel.id(), 0, false, false, false)));

        assertEquals(0, countShipments());
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Локальна відмова: клієнта/посилки не існує → IllegalArgumentException, запису немає, HTTP = 0")
    void localRejection_missingCustomerOrParcel_noHttp() throws SQLException {
        stubPolicy(200, ALLOWED);

        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("REF-NOCUST", 9_999, parcel.id(), 10, false, false, false)));
        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("REF-NOPARCEL", customer.id(), 9_999, 10, false, false, false)));

        assertEquals(0, countShipments());
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Локальна відмова: порожній або задовгий reference → IllegalArgumentException, HTTP = 0")
    void localRejection_invalidReference_noHttp() throws SQLException {
        stubPolicy(200, ALLOWED);

        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("   ", customer.id(), parcel.id(), 10, false, false, false)));
        assertThrows(IllegalArgumentException.class, () -> service.createShipment(
                new ShipmentRequest("R".repeat(41), customer.id(), parcel.id(), 10, false, false, false)));

        assertEquals(0, countShipments());
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("БД: повторний reference → SQLException, у PostgreSQL лишається один запис (дозвіл запитувався двічі)")
    void duplicateReference_isRejectedByDatabase() throws SQLException {
        stubPolicy(200, ALLOWED);
        Shipment first = service.createShipment(
                new ShipmentRequest("REF-DUP", customer.id(), parcel.id(), 10, false, false, false));

        assertThrows(SQLException.class, () -> service.createShipment(
                new ShipmentRequest("REF-DUP", customer.id(), parcel.id(), 20, false, false, false)));

        assertEquals(1, countShipments());
        assertEquals(first, shipments.findById(first.id()));
        assertEquals(2, requestsToPolicy().size());
    }

    @Test
    @DisplayName("Статуси: Registered → Dispatched → Delivered зберігаються в БД, HTTP = 0")
    void statusTransitions_persist_noHttp() throws SQLException {
        Shipment s = registeredShipmentDirectly("REF-FLOW");

        service.dispatch(s.id());
        assertEquals("Dispatched", rawStatus(s.id()));
        service.deliver(s.id());
        assertEquals("Delivered", rawStatus(s.id()));
        assertEquals(0L, shipments.findById(s.id()).refund());

        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Статуси: cancel Registered повертає весь total, у БД refund = total, HTTP = 0")
    void cancelRegistered_fullRefundPersisted_noHttp() throws SQLException {
        Shipment s = registeredShipmentDirectly("REF-CANCEL");

        service.cancel(s.id());

        Shipment inDb = shipments.findById(s.id());
        assertEquals(ShipmentStatus.Cancelled, inDb.status());
        assertEquals(7000L, inDb.refund());
        assertEquals(7000L, inDb.total());
        assertEquals(7000L, inDb.baseFee());
        assertEquals(0L, inDb.adjustment());
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Статуси: cancel після Dispatched заборонений, стан БД не змінюється, HTTP = 0")
    void cancelDispatched_rejected_dbUnchanged() throws SQLException {
        Shipment s = registeredShipmentDirectly("REF-NOCANCEL");
        service.dispatch(s.id());

        assertThrows(ShipmentRejectedException.class, () -> service.cancel(s.id()));
        assertThrows(ShipmentRejectedException.class, () -> service.dispatch(s.id())); // повторний перехід

        Shipment inDb = shipments.findById(s.id());
        assertEquals(ShipmentStatus.Dispatched, inDb.status());
        assertEquals(0L, inDb.refund());
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Статуси: deliver до dispatch заборонений, стан БД не змінюється, HTTP = 0")
    void deliverBeforeDispatch_rejected_dbUnchanged() throws SQLException {
        Shipment s = registeredShipmentDirectly("REF-EARLY");

        assertThrows(ShipmentRejectedException.class, () -> service.deliver(s.id()));

        assertEquals("Registered", rawStatus(s.id()));
        assertNoHttpRequests();
    }

    @Test
    @DisplayName("Ізоляція (1/2): reference REF-SHARED вільний, бо попередні дані очищено")
    void isolationFirst() throws SQLException {
        createSharedReferenceShipment();
    }

    @Test
    @DisplayName("Ізоляція (2/2): той самий reference знову вільний, незалежно від порядку тестів")
    void isolationSecond() throws SQLException {
        createSharedReferenceShipment();
    }

    private void createSharedReferenceShipment() throws SQLException {
        stubPolicy(200, ALLOWED);
        service.createShipment(new ShipmentRequest("REF-SHARED", customer.id(), parcel.id(), 10, false, false, false));
        assertEquals(1, countShipments());
        assertEquals(1, requestsToPolicy().size());
    }

    private ShippingService serviceWithTimeout(Duration timeout) {
        var policy = new HttpShippingPolicy(TestEnvironment.wireMockBaseUrl(), timeout);
        return new ShippingService(customers, parcels, shipments, policy);
    }

    private void stubPolicy(int status, String body) {
        wireMock.register(post(urlEqualTo(POLICY_PATH)).willReturn(
                aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private List<LoggedRequest> requestsToPolicy() {
        return wireMock.find(postRequestedFor(urlEqualTo(POLICY_PATH)));
    }

    private void awaitPolicyRequests(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (requestsToPolicy().size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void assertNoHttpRequests() {
        assertEquals(List.of(), wireMock.find(anyRequestedFor(anyUrl())),
                "зовнішній HTTP-сервіс не мав викликатися");
    }

    private void assertSinglePolicyCall(long customerId, long parcelId, int distanceKm) {
        assertEquals(1, wireMock.find(anyRequestedFor(anyUrl())).size(), "очікувався рівно 1 запит до WireMock");
        wireMock.verifyThat(1, postRequestedFor(urlEqualTo(POLICY_PATH))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(String.format(
                        "{\"customerId\":%d,\"parcelId\":%d,\"distanceKm\":%d}", customerId, parcelId, distanceKm))));
    }

    private int countShipments() throws SQLException {
        try (var st = verifier.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM shipments")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String rawStatus(long id) throws SQLException {
        try (var st = verifier.prepareStatement("SELECT status FROM shipments WHERE id=?")) {
            st.setLong(1, id);
            try (var rs = st.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private Shipment registeredShipmentDirectly(String reference) throws SQLException {
        return shipments.create(new Shipment(0, reference, customer.id(), parcel.id(), 100, false, false,
                false, false, false, 1000, 10_000, 7000, 0, 7000, 0, ShipmentStatus.Registered));
    }
}
