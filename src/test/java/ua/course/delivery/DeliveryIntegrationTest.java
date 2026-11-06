package ua.course.delivery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class DeliveryIntegrationTest {

    private Connection connection;
    private CustomerRepository customerRepo;
    private ParcelRepository parcelRepo;
    private ShipmentRepository shipmentRepo;
    private ShippingPolicy shippingPolicyMock;
    private ShippingService shippingService;

    @BeforeEach
    void setUp() throws SQLException {
        connection = Database.connect();
        customerRepo = new CustomerRepository(connection);
        parcelRepo = new ParcelRepository(connection);
        shipmentRepo = new ShipmentRepository(connection);
        shippingPolicyMock = Mockito.mock(ShippingPolicy.class);

        shippingService = new ShippingService(customerRepo, parcelRepo, shipmentRepo, shippingPolicyMock);

        cleanDatabase();
    }

    @AfterEach
    void tearDown() throws SQLException {
        cleanDatabase();
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private void cleanDatabase() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.executeUpdate("TRUNCATE TABLE shipments, parcels, customers RESTART IDENTITY CASCADE");
        }
    }

    @Test
    @DisplayName("Repository: збереження та отримання сутності Customer")
    void customerPersistenceAndRetrieval_repositoryState() throws SQLException {
        // 1. Створення сутності
        Customer created = customerRepo.create(new Customer(0, "CUST-REPO-01", false));
        assertTrue(created.id() > 0);

        // 2. Повторне отримання та перевірка мапінгу
        Customer found = customerRepo.findById(created.id());
        assertNotNull(found);
        assertEquals("CUST-REPO-01", found.code());
        assertFalse(found.regular());
    }

    @Test
    @DisplayName("Repository: збереження, читання та оновлення даних сутності Parcel")
    void parcelSaveAndModify_repositoryState() throws SQLException {
        // 1. Створення
        Parcel created = parcelRepo.create(new Parcel(0, "PARCEL-REPO-01", 1200, 50000, false));
        assertTrue(created.id() > 0);

        // 2. Зміна даних
        Parcel toUpdate = new Parcel(created.id(), "PARCEL-REPO-01-UPD", 2500, 80000, true);
        parcelRepo.update(toUpdate);

        // 3. Перевірка фактичного стану в БД
        Parcel updated = parcelRepo.findById(created.id());
        assertNotNull(updated);
        assertEquals("PARCEL-REPO-01-UPD", updated.code());
        assertEquals(2500, updated.weightGrams());
        assertEquals(80000, updated.declaredValue());
        assertTrue(updated.fragile());
    }

    @Test
    @DisplayName("Repository: збереження та видалення сутності Parcel")
    void parcelDeleteOperation_repositoryState() throws SQLException {
        Parcel created = parcelRepo.create(new Parcel(0, "PARCEL-DEL-01", 800, 10000, false));
        assertNotNull(parcelRepo.findById(created.id()));

        // Видалення
        parcelRepo.delete(created.id());

        // Перевірка фактичної відсутності в БД
        assertNull(parcelRepo.findById(created.id()));
    }

    @Test
    @DisplayName("Service: успішне створення відправлення зі знижкою 15% за валідний купон")
    void successfulShipmentCreationWithCoupon_serviceState() throws SQLException {
        // Arrange
        Customer customer = customerRepo.create(new Customer(0, "CUST-COUPON", false));
        // Вага 1800 г -> розрахункова вага = ceil(1800/1000)*1000 = 2000
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-COUPON", 1800, 20000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        int distanceKm = 100;
        // baseFee = 5000 + 2000 + 100 * 10 = 8000
        // discount (couponValid = true) = 8000 * 15% = 1200 -> adjustment = -1200
        // total = 8000 - 1200 = 6800
        var request = new ShipmentRequest("REF-COUPON-01", customer.id(), parcel.id(), distanceKm, false, false, true);

        // Act
        Shipment created = shippingService.createShipment(request);

        // Assert: перевірка результату операції
        assertNotNull(created);
        assertEquals(8000L, created.baseFee());
        assertEquals(-1200L, created.adjustment());
        assertEquals(6800L, created.total());
        assertEquals(ShipmentStatus.Registered, created.status());

        // Assert: перевірка фактичного стану в базі через репозиторій
        Shipment inDb = shipmentRepo.findById(created.id());
        assertNotNull(inDb);
        assertEquals("REF-COUPON-01", inDb.reference());
        assertEquals(customer.id(), inDb.customerId());
        assertEquals(parcel.id(), inDb.parcelId());
        assertEquals(6800L, inDb.total());
        assertEquals(0L, inDb.refund());
        assertEquals(ShipmentStatus.Registered, inDb.status());
    }

    @Test
    @DisplayName("Service: негативний сценарій — відхилення створення відправлення зовнішньою політикою")
    void rejectionByExternalShippingPolicy_serviceState() throws SQLException {
        // Arrange
        Customer customer = customerRepo.create(new Customer(0, "CUST-REJECT", false));
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-REJECT", 1000, 10000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(false);

        var request = new ShipmentRequest("REF-REJECT-01", customer.id(), parcel.id(), 50, false, false, false);

        // Act & Assert: перевірка винятку
        assertThrows(ShipmentRejectedException.class, () -> shippingService.createShipment(request));

        // Assert: перевірка фактичного стану БД — запис не повинен з'явитися в базі
        List<Shipment> list = shipmentRepo.findShipments(customer.id(), ShipmentStatus.Registered);
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("Service: персональне правило — знижка 7% для постійного клієнта без купона")
    void regularCustomerDiscountRule() throws SQLException {
        // Arrange: regular = true, couponValid = false
        Customer customer = customerRepo.create(new Customer(0, "CUST-REGULAR", true));
        // 2500 г -> 3000 г
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-REGULAR", 2500, 30000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        int distanceKm = 100;
        // baseFee = 5000 + 3000 + 100 * 10 = 9000
        // discount 7% = 9000 * 7% = 630 -> adjustment = -630, total = 8370
        var request = new ShipmentRequest("REF-REGULAR-01", customer.id(), parcel.id(), distanceKm, false, false, false);

        // Act
        Shipment created = shippingService.createShipment(request);

        // Assert: перевірка в БД
        Shipment inDb = shipmentRepo.findById(created.id());
        assertNotNull(inDb);
        assertEquals(9000L, inDb.baseFee());
        assertEquals(-630L, inDb.adjustment());
        assertEquals(8370L, inDb.total());
    }

    @Test
    @DisplayName("Service: повний життєвий цикл переходу статусів Registered -> Dispatched -> Delivered")
    void fullShipmentLifecycleTransitions() throws SQLException {
        // Arrange
        Customer customer = customerRepo.create(new Customer(0, "CUST-FLOW", false));
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-FLOW", 1000, 15000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        Shipment created = shippingService.createShipment(
                new ShipmentRequest("REF-FLOW-01", customer.id(), parcel.id(), 30, false, false, false)
        );

        // 1. Dispatch
        Shipment dispatched = shippingService.dispatch(created.id());
        assertEquals(ShipmentStatus.Dispatched, dispatched.status());
        assertEquals(ShipmentStatus.Dispatched, shipmentRepo.findById(created.id()).status());

        // 2. Deliver
        Shipment delivered = shippingService.deliver(created.id());
        assertEquals(ShipmentStatus.Delivered, delivered.status());
        assertEquals(ShipmentStatus.Delivered, shipmentRepo.findById(created.id()).status());
    }

    @Test
    @DisplayName("Service: скасування відправлення Registered з повним поверненням коштів (refund = total)")
    void cancelRegisteredShipmentWithFullRefund() throws SQLException {
        // Arrange
        Customer customer = customerRepo.create(new Customer(0, "CUST-CANCEL", false));
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-CANCEL", 1000, 10000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        Shipment created = shippingService.createShipment(
                new ShipmentRequest("REF-CANCEL-01", customer.id(), parcel.id(), 20, false, false, false)
        );

        // Act
        Shipment cancelled = shippingService.cancel(created.id());

        // Assert
        assertEquals(ShipmentStatus.Cancelled, cancelled.status());
        assertEquals(created.total(), cancelled.refund());

        // Верифікація оновленого запису в БД
        Shipment inDb = shipmentRepo.findById(created.id());
        assertEquals(ShipmentStatus.Cancelled, inDb.status());
        assertEquals(created.total(), inDb.refund());
    }

    @Test
    @DisplayName("Service: негативний сценарій — заборона скасування після статусу Dispatched")
    void cannotCancelAlreadyDispatchedShipment() throws SQLException {
        // Arrange
        Customer customer = customerRepo.create(new Customer(0, "CUST-DISP-CANCEL", false));
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-DISP-CANCEL", 1000, 10000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        Shipment created = shippingService.createShipment(
                new ShipmentRequest("REF-NO-CANCEL", customer.id(), parcel.id(), 15, false, false, false)
        );
        shippingService.dispatch(created.id());

        // Act & Assert
        assertThrows(ShipmentRejectedException.class, () -> shippingService.cancel(created.id()));

        // Переконуємось, що статус у базі не змінився на Cancelled
        Shipment inDb = shipmentRepo.findById(created.id());
        assertEquals(ShipmentStatus.Dispatched, inDb.status());
        assertEquals(0L, inDb.refund());
    }

    @Test
    @DisplayName("Service: граничний сценарій розрахунку тарифу на максимальній дистанції (5000 км)")
    void boundaryMaxDistanceCalculation() throws SQLException {
        // Arrange: вага 30 000 г (30 кг), дистанція 5000 км
        Customer customer = customerRepo.create(new Customer(0, "CUST-MAX", false));
        Parcel parcel = parcelRepo.create(new Parcel(0, "BOX-MAX", 30000, 5000000, false));
        when(shippingPolicyMock.isAllowed(any(), any(), anyInt())).thenReturn(true);

        int maxDistanceKm = 5000;
        // baseFee = 5000 + 30000 + 5000 * 10 = 85000 (верхня межа за специфікацією варіанта 12)
        // regular = false, coupon = false -> adjustment = 0, total = 85000
        var request = new ShipmentRequest("REF-MAX-01", customer.id(), parcel.id(), maxDistanceKm, false, false, false);

        // Act
        Shipment created = shippingService.createShipment(request);

        // Assert
        assertEquals(85000L, created.baseFee());
        assertEquals(0L, created.adjustment());
        assertEquals(85000L, created.total());

        Shipment inDb = shipmentRepo.findById(created.id());
        assertNotNull(inDb);
        assertEquals(85000L, inDb.total());
    }
}