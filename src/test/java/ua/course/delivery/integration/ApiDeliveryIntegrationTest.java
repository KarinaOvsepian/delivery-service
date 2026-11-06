package ua.course.delivery.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import ua.course.delivery.DeliveryApplication;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ApiDeliveryIntegrationTest {

    private ConfigurableApplicationContext springContext;
    private Long createdCustomerId;
    private Long createdParcelId;
    private Long createdShipmentId;

    @BeforeAll
    void setUp() {
        // 1. Вказуємо параметри для запущеного контейнера Postgres та WireMock
        System.setProperty("DB_HOST", "localhost");
        System.setProperty("DB_PORT", "5432");
        System.setProperty("DB_NAME", "delivery_variant12_test");
        System.setProperty("DB_USER", "delivery_student");
        System.setProperty("DB_PASSWORD", "");
        System.setProperty("SHIPPING_POLICY_URL", TestEnvironment.wireMockBaseUrl().toString());
        System.setProperty("API_PORT", "8080");

        // 2. Налаштовуємо WireMock
        TestEnvironment.wireMockClient().register(
                WireMock.post(WireMock.urlEqualTo("/shipping-policy"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"allowed\":true}"))
        );

        // 3. Запускаємо Spring Boot REST API
        springContext = SpringApplication.run(DeliveryApplication.class);

        // 4. Налаштовуємо RestAssured
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = 8080;

        // 5. Підготовка базових клієнта та посилки
        createdCustomerId = createCustomer("CST-INIT-" + UUID.randomUUID().toString().substring(0, 5), false);
        createdParcelId = createParcel("PCL-INIT-" + UUID.randomUUID().toString().substring(0, 5), 1000, 10000, false);
    }

    @AfterAll
    void tearDown() {
        if (springContext != null) {
            SpringApplication.exit(springContext);
        }
    }

    private Long createCustomer(String code, boolean regular) {
        String body = String.format("{\"code\":\"%s\",\"regular\":%b}", code, regular);
        return given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/customers")
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    private Long createParcel(String code, int weightGrams, long declaredValue, boolean fragile) {
        String body = String.format("{\"code\":\"%s\",\"weightGrams\":%d,\"declaredValue\":%d,\"fragile\":%b}",
                code, weightGrams, declaredValue, fragile);
        return given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/parcels")
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    @Test
    @Order(1)
    @DisplayName("1. POST /shipments — Успішне створення відправлення (201)")
    void createShipment_ShouldReturn201AndId() {
        String ref = "REF-" + UUID.randomUUID().toString().substring(0, 8);
        String requestBody = String.format("""
            {
                "reference": "%s",
                "customerId": %d,
                "parcelId": %d,
                "distanceKm": 100,
                "urgent": false,
                "pickup": false,
                "couponValid": false
            }
            """, ref, createdCustomerId, createdParcelId);

        createdShipmentId = given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/shipments")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("Registered"))
                .body("reference", equalTo(ref))
                .body("baseFee", equalTo(7000))
                .body("total", equalTo(7000))
                .extract()
                .path("id");

        Assertions.assertNotNull(createdShipmentId);
    }

    @Test
    @Order(2)
    @DisplayName("2. GET /shipments/{id} — Отримання відправлення за ID (200)")
    void getShipmentById_ShouldReturn200AndCorrectData() {
        given()
                .pathParam("id", createdShipmentId)
                .when()
                .get("/shipments/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(createdShipmentId.intValue()))
                .body("customerId", equalTo(createdCustomerId.intValue()))
                .body("parcelId", equalTo(createdParcelId.intValue()))
                .body("status", equalTo("Registered"));
    }

    @Test
    @Order(3)
    @DisplayName("3. GET /shipments/{id} — Запит неіснуючого ресурсу (404)")
    void getNonExistentShipment_ShouldReturn404() {
        given()
                .pathParam("id", 999999L)
                .when()
                .get("/shipments/{id}")
                .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", notNullValue());
    }

    @Test
    @Order(4)
    @DisplayName("4. POST /shipments/{id}/dispatch — Зміна стану ресурсу (200)")
    void actionDispatch_ShouldUpdateStatusToDispatched() {
        given()
                .pathParam("id", createdShipmentId)
                .when()
                .post("/shipments/{id}/dispatch")
                .then()
                .statusCode(200)
                .body("id", equalTo(createdShipmentId.intValue()))
                .body("status", equalTo("Dispatched"));
    }

    @Test
    @Order(5)
    @DisplayName("5. POST /shipments/{id}/dispatch — Повторна заборонена дія (409)")
    void repeatForbiddenAction_ShouldReturn409() {
        given()
                .pathParam("id", createdShipmentId)
                .when()
                .post("/shipments/{id}/dispatch")
                .then()
                .statusCode(409)
                .body("code", equalTo("SHIPMENT_CONFLICT"))
                .body("message", notNullValue());
    }

    @Test
    @Order(6)
    @DisplayName("6. GET /shipments/{id} — Підтвердження незмінності ресурсу після 409")
    void getShipment_ShouldConfirmImmutabilityAfterRejection() {
        given()
                .pathParam("id", createdShipmentId)
                .when()
                .get("/shipments/{id}")
                .then()
                .statusCode(200)
                .body("status", equalTo("Dispatched"));
    }

    @ParameterizedTest
    @CsvSource({
            "true,  false, -1050, 5950",
            "false, true,  -490,  6510",
            "false, false, 0,     7000"
    })
    @Order(7)
    @DisplayName("7. Параметризований тест персонального правила (Пріоритет промокоду)")
    void testBusinessRule_Discounts(boolean couponValid, boolean isRegular, long expectedAdjustment, long expectedTotal) {
        String custCode = "CST-BR-" + UUID.randomUUID().toString().substring(0, 5);
        Long custId = createCustomer(custCode, isRegular);

        String ref = "REF-BR-" + UUID.randomUUID().toString().substring(0, 5);
        String body = String.format("""
            {
                "reference": "%s",
                "customerId": %d,
                "parcelId": %d,
                "distanceKm": 100,
                "urgent": false,
                "pickup": false,
                "couponValid": %b
            }
            """, ref, custId, createdParcelId, couponValid);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/shipments")
                .then()
                .statusCode(201)
                .body("baseFee", equalTo(7000))
                .body("adjustment", equalTo((int) expectedAdjustment))
                .body("total", equalTo((int) expectedTotal));
    }

    @Test
    @Order(8)
    @DisplayName("8. Workflow: Повний цикл створення, перевірки, скасування та підтвердження")
    void fullWorkflowTest() {
        String ref = "REF-WF-" + UUID.randomUUID().toString().substring(0, 5);
        String createJson = String.format("""
            {
                "reference": "%s",
                "customerId": %d,
                "parcelId": %d,
                "distanceKm": 100,
                "urgent": false,
                "pickup": false,
                "couponValid": false
            }
            """, ref, createdCustomerId, createdParcelId);

        Integer id = given()
                .contentType(ContentType.JSON)
                .body(createJson)
                .when()
                .post("/shipments")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .pathParam("id", id)
                .when()
                .get("/shipments/{id}")
                .then()
                .statusCode(200)
                .body("status", equalTo("Registered"));

        given()
                .pathParam("id", id)
                .when()
                .post("/shipments/{id}/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("Cancelled"))
                .body("refund", equalTo(7000));

        given()
                .pathParam("id", id)
                .when()
                .get("/shipments/{id}")
                .then()
                .statusCode(200)
                .body("status", equalTo("Cancelled"))
                .body("refund", equalTo(7000));
    }
}