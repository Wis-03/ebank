package net.youssfi.ebankservice.dtos;

import net.youssfi.ebankservice.entities.BankAccount;

public record BankAccountWithCustomer(BankAccount bankAccount, Customer customer) {
}
