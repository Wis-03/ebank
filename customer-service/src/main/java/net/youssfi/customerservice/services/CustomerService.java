package net.youssfi.customerservice.services;

import net.youssfi.customerservice.entities.Customer;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {
    private final List<Customer> customers = List.of(
            new Customer("C1", "Yassine", "yassine@gmail.com"),
            new Customer("C2", "Imane", "imane@gmail.com"),
            new Customer("C3", "Mohamed", "mohamed@gmail.com")
    );

    public List<Customer> getAllCustomers() {
        return customers;
    }

    @McpTool(name = "getCustomerById",
            description = "Get the customer (owner) information by customer id")
    public Customer getCustomerById(
            @McpArg(description = "the customer id", required = true) String id) {
        return customers.stream()
                .filter(customer -> customer.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Customer not found"));
    }
}
