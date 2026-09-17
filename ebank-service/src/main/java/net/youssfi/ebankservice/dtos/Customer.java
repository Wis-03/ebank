package net.youssfi.ebankservice.dtos;

/**
 * Contrat consomme chez CUSTOMER-SERVICE. Copie locale volontaire : les
 * microservices ne partagent pas de module commun dans ce projet.
 */
public record Customer(String id, String name, String email) {
}
