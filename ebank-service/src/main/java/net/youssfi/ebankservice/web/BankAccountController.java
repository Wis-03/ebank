package net.youssfi.ebankservice.web;

import net.youssfi.ebankservice.entities.BankAccount;
import net.youssfi.ebankservice.services.BankAccountService;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
public class BankAccountController {
    private BankAccountService bankAccountService;

    public BankAccountController(BankAccountService bankAccountService) {
        this.bankAccountService = bankAccountService;
    }

    @GetMapping("/bankAccounts")
    public List<BankAccount> getAllAccounts(){
        return bankAccountService.getAllAccounts();
    }
    @GetMapping("/bankAccounts/{id}")
    public BankAccount getBankAccountById(@PathVariable String id){
        return bankAccountService.getBankAccountById(id);
    }
    @PostMapping("/bankAccounts")
    public BankAccount saveAccount(@RequestBody BankAccount bankAccount){
        return bankAccountService.saveAccount(bankAccount);
    }
}
