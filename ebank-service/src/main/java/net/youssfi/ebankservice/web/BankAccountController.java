package net.youssfi.ebankservice.web;

import net.youssfi.ebankservice.clients.CustomerClient;
import net.youssfi.ebankservice.dtos.BankAccountWithCustomer;
import net.youssfi.ebankservice.dtos.Customer;
import net.youssfi.ebankservice.entities.BankAccount;
import net.youssfi.ebankservice.services.BankAccountService;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
public class BankAccountController {
    private BankAccountService bankAccountService;
    private CustomerClient customerClient;

    public BankAccountController(BankAccountService bankAccountService,
                                 CustomerClient customerClient) {
        this.bankAccountService = bankAccountService;
        this.customerClient = customerClient;
    }

    @GetMapping("/bankAccounts")
    public List<BankAccount> getAllAccounts(){
        return bankAccountService.getAllAccounts();
    }
    /**
     * Enrichi avec le proprietaire du compte, recupere chez CUSTOMER-SERVICE
     * via OpenFeign (resolution de l'instance par Eureka).
     */
    @GetMapping("/bankAccounts/{id}")
    public BankAccountWithCustomer getBankAccountById(@PathVariable String id){
        BankAccount bankAccount = bankAccountService.getBankAccountById(id);
        Customer customer = customerClient.getCustomerById(bankAccount.getCustomerId());
        return new BankAccountWithCustomer(bankAccount, customer);
    }
    @PostMapping("/bankAccounts")
    public BankAccount saveAccount(@RequestBody BankAccount bankAccount){
        return bankAccountService.saveAccount(bankAccount);
    }
}
