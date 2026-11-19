package com.taas.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.NotFoundException;
import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;
import com.taas.workflow.Commands;
import com.taas.workflow.WorkflowEngine;

/**
 * The common token operations. A new transaction answers 201; repeating a txId with the same request
 * answers 200 with the original event and {@code Idempotent-Replay: true}, and moves nothing.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    public record TokenView(Registry.TokenDef token, TokenLedger.Supply supply) {
    }

    private final WorkflowEngine engine;
    private final Registry registry;
    private final TokenLedger tokenLedger;

    public TokenController(WorkflowEngine engine, Registry registry, TokenLedger tokenLedger) {
        this.engine = engine;
        this.registry = registry;
        this.tokenLedger = tokenLedger;
    }

    @PostMapping
    public ResponseEntity<EventView> create(@Valid @RequestBody Commands.CreateToken body) {
        return respond(engine.createToken(body));
    }

    @GetMapping
    public List<Registry.TokenDef> list() {
        return registry.tokens();
    }

    @GetMapping("/{code}")
    public TokenView get(@PathVariable String code) {
        Registry.TokenDef token = registry.token(code)
                .orElseThrow(() -> new NotFoundException("Token " + code + " not found"));
        return new TokenView(token, tokenLedger.supply(code));
    }

    @GetMapping("/{code}/holders")
    public List<TokenLedger.Holder> holders(@PathVariable String code) {
        return tokenLedger.holders(code);
    }

    @PostMapping("/{code}/issue")
    public ResponseEntity<EventView> issue(@PathVariable String code, @Valid @RequestBody Commands.Issue body) {
        return respond(engine.issue(code, body));
    }

    @PostMapping("/{code}/earn")
    public ResponseEntity<EventView> earn(@PathVariable String code, @Valid @RequestBody Commands.Earn body) {
        return respond(engine.earn(code, body));
    }

    @PostMapping("/{code}/transfer")
    public ResponseEntity<EventView> transfer(@PathVariable String code,
            @Valid @RequestBody Commands.Transfer body) {
        return respond(engine.transfer(code, body));
    }

    @PostMapping("/{code}/reserve")
    public ResponseEntity<EventView> reserve(@PathVariable String code, @Valid @RequestBody Commands.Reserve body) {
        return respond(engine.reserve(code, body));
    }

    @GetMapping("/{code}/reservations/{id}")
    public Registry.Reservation reservation(@PathVariable String code, @PathVariable UUID id) {
        return registry.reservation(id)
                .filter(r -> r.tokenCode().equals(code))
                .orElseThrow(() -> new NotFoundException("Reservation " + id + " not found for token " + code));
    }

    @PostMapping("/{code}/reservations/{id}/purchase")
    public ResponseEntity<EventView> purchase(@PathVariable String code, @PathVariable UUID id,
            @Valid @RequestBody Commands.Purchase body) {
        return respond(engine.purchase(code, id, body));
    }

    @PostMapping("/{code}/reservations/{id}/release")
    public ResponseEntity<EventView> release(@PathVariable String code, @PathVariable UUID id,
            @Valid @RequestBody Commands.Release body) {
        return respond(engine.release(code, id, body));
    }

    @PostMapping("/{code}/redeem")
    public ResponseEntity<EventView> redeem(@PathVariable String code, @Valid @RequestBody Commands.Redeem body) {
        return respond(engine.redeem(code, body));
    }

    private static ResponseEntity<EventView> respond(WorkflowEngine.Result result) {
        EventView view = EventView.of(result.event());
        if (result.duplicate()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(view);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}
