package ua.course.delivery.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

public final class TestEnvironment {

    private static WireMockServer wireMockServer;

    static {
        startWireMock();
        applyMigrations();
    }

    private TestEnvironment() {
    }

    public static synchronized void startWireMock() {
        if (wireMockServer == null) {
            wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            wireMockServer.start();
        }
    }

    public static Connection newConnection() throws SQLException {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String dbName = System.getenv().getOrDefault("DB_NAME", "delivery_variant12_test");
        String user = System.getenv().getOrDefault("DB_USER", "delivery_student");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");

        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + dbName, user, password
        );
    }

    private static void applyMigrations() {
        Path dir = Path.of("migrations");
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir);
             Connection c = newConnection()) {
            List<Path> scripts = files
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted((a, b) -> Integer.compare(version(a), version(b)))
                    .toList();
            for (Path script : scripts) {
                try (var st = c.createStatement()) {
                    st.execute(Files.readString(script, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | SQLException ignored) {
            // Міграції вже підготовлені або застосовані
        }
    }

    private static int version(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    public static URI wireMockBaseUrl() {
        return URI.create("http://localhost:" + wireMockServer.port());
    }

    public static WireMock wireMockClient() {
        return new WireMock("localhost", wireMockServer.port());
    }
}