package net.youssfi.ebankservice.services;

import net.youssfi.ebankservice.entities.BankAccount;
import net.youssfi.ebankservice.repo.BankAccountRepository;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class BankAccountService {
    private BankAccountRepository accountRepository;

    public BankAccountService(BankAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @McpTool(description = "Get All Bank Accounts")
    public List<BankAccount> getAllAccounts(){
        return accountRepository.findAll();
    }
    @McpTool(description = "Get A Bank Account by Id")
    public BankAccount getBankAccountById(@McpArg(description = "the bank account id") String id){
        return accountRepository.findById(id)
                .orElseThrow(()->new RuntimeException("Account not found"));
    }
    @McpTool(description = "Add new Bank Account")
    public BankAccount saveAccount(@McpArg(description = "New Bank Account (id, createdAt, balance, type)") BankAccount bankAccount){
        bankAccount.setCreatedAt(new Date());
        bankAccount.setId(UUID.randomUUID().toString());
        return accountRepository.save(bankAccount);
    }
}
