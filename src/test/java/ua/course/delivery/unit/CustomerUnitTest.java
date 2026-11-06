package ua.course.delivery.unit;

import org.junit.jupiter.api.Test;
import ua.course.delivery.Customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CustomerUnitTest {

    @Test
    void testCustomerCreation() {
        Customer customer = new Customer(1L, "Іван Петренко", true);

        assertNotNull(customer);
        assertEquals("Іван Петренко", customer.code());
    }
}