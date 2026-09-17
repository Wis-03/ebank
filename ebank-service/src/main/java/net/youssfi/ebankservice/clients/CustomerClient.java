package net.youssfi.ebankservice.clients;

import net.youssfi.ebankservice.dtos.Customer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Le name correspond au nom d'enregistrement Eureka du Customer Service.
 * Aucune URL ni aucun port n'est code en dur : l'instance est resolue
 * dynamiquement via Eureka et spring-cloud-loadbalancer.
 */
@FeignClient(name = "CUSTOMER-SERVICE")
public interface CustomerClient {

    @GetMapping("/customers/{id}")
    Customer getCustomerById(@PathVariable("id") String id);
}
