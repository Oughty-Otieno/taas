package com.taas.api;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;

@RestController
@RequestMapping("/api/accounts/{account}")
public class AccountController {

    /** {@code available} is in the account; {@code reservedForSale} sits in escrow for open reservations. */
    public record AccountBalance(String token, long available, long reservedForSale) {
    }

    public record Statement(String account, String token, List<TokenLedger.StatementLine> lines,
            long closingBalance) {
    }

    private final TokenLedger tokenLedger;
    private final Registry registry;

    public AccountController(TokenLedger tokenLedger, Registry registry) {
        this.tokenLedger = tokenLedger;
        this.registry = registry;
    }

    @GetMapping("/balances")
    public List<AccountBalance> balances(@PathVariable String account) {
        Map<String, Long> available = tokenLedger.balancesOf(account);
        Map<String, Long> reserved = registry.reservedBySeller(account);
        TreeSet<String> tokens = new TreeSet<>(available.keySet());
        tokens.addAll(reserved.keySet());
        return tokens.stream()
                .map(t -> new AccountBalance(t, available.getOrDefault(t, 0L), reserved.getOrDefault(t, 0L)))
                .toList();
    }

    /** Every movement on the account with the running balance after it, like a bank statement. */
    @GetMapping("/statement")
    public Statement statement(@PathVariable String account, @RequestParam String token) {
        List<TokenLedger.StatementLine> lines = tokenLedger.statement(account, token);
        long closing = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).runningBalance();
        return new Statement(account, token, lines, closing);
    }
}
