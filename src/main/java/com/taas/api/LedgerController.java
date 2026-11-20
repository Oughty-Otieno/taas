package com.taas.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taas.common.NotFoundException;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.Projector;
import com.taas.ledger.TokenLedger;

@RestController
@RequestMapping("/v1/ledger")
public class LedgerController {

    private static final int MAX_PAGE = 500;
    private static final int MAX_PROOF_LINKS = 5000;

    public record Page(List<ActionView> items, Long nextCursor) {
    }

    public record Link(long seq, String eventType, String prevHash, String hash, boolean valid) {
    }

    /**
     * Hash-chain inclusion proof: the event, its hash recomputed from its own content, and every link from it to
     * the current head. Anyone holding the head hash can check the event is in the history and unaltered.
     */
    public record Proof(ActionView event, String recomputedHash, boolean eventHashValid, List<Link> chainToHead,
            boolean chainValid, String headHash, long headSeq, boolean truncated) {
    }

    public record WipeReport(Map<String, Long> holdingsBefore, long eventsInActionLedger) {
    }

    private final ActionLedger actions;
    private final TokenLedger tokenLedger;
    private final Projector projector;
    private final EventStream stream;
    private final boolean demoEndpoints;

    public LedgerController(ActionLedger actions, TokenLedger tokenLedger, Projector projector, EventStream stream,
            @Value("${taas.demo-endpoints}") boolean demoEndpoints) {
        this.actions = actions;
        this.tokenLedger = tokenLedger;
        this.projector = projector;
        this.stream = stream;
        this.demoEndpoints = demoEndpoints;
    }

    /** Filter by token, actor, account, asset type, event type and time; cursor-paged by sequence number. */
    @GetMapping("/actions")
    public Page actions(@RequestParam(required = false) String tokenId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String related,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "newest") String order) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        boolean newestFirst = !"oldest".equals(order);
        List<String> types = type == null || type.isBlank() ? List.of()
                : Arrays.stream(type.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ActionLedger.Filter f = new ActionLedger.Filter(blank(tokenId), blank(actorId), blank(account),
                blank(assetType), types, from, to, blank(related));
        List<ActionView> items = actions.list(f, cursor, size, newestFirst).stream().map(ActionView::of).toList();
        Long next = items.size() == size ? items.get(items.size() - 1).seq() : null;
        return new Page(items, next);
    }

    @GetMapping("/actions/{id}")
    public ActionView action(@PathVariable UUID id) {
        return actions.find(id).map(ActionView::of).orElseThrow(() -> new NotFoundException("No action " + id));
    }

    @GetMapping("/actions/{id}/proof")
    public Proof proof(@PathVariable UUID id) {
        ActionEvent event = actions.find(id).orElseThrow(() -> new NotFoundException("No action " + id));
        String recomputed = event.expectedHash();
        List<ActionEvent> chain = actions.fromSeq(event.seq(), MAX_PROOF_LINKS + 1);
        boolean truncated = chain.size() > MAX_PROOF_LINKS;
        if (truncated) {
            chain = chain.subList(0, MAX_PROOF_LINKS);
        }
        List<Link> links = new ArrayList<>();
        boolean valid = true;
        String prev = event.prevHash();
        for (ActionEvent e : chain) {
            boolean ok = e.prevHash().equals(prev) && e.expectedHash().equals(e.hash());
            valid &= ok;
            links.add(new Link(e.seq(), e.type().name(), e.prevHash(), e.hash(), ok));
            prev = e.hash();
        }
        ActionEvent last = chain.get(chain.size() - 1);
        return new Proof(ActionView.of(event), recomputed, recomputed.equals(event.hash()), links, valid, last.hash(),
                last.seq(), truncated);
    }

    /** The raw movements behind any balance. */
    @GetMapping("/movements")
    public List<TokenLedger.Movement> movements(@RequestParam(required = false) String tokenId,
            @RequestParam(required = false) String accountId, @RequestParam(required = false) UUID actionId,
            @RequestParam(defaultValue = "100") int limit) {
        return tokenLedger.movements(blank(tokenId), blank(accountId), actionId, Math.max(1, Math.min(limit, 1000)));
    }

    /** Live committed events (SSE). */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        return stream.subscribe();
    }

    /** Hash chain, holdings vs movements, and zero-sum checks, without changing anything. */
    @GetMapping("/verify")
    public Projector.VerifyReport verify() {
        return projector.verify();
    }

    /** The replay test: drop every projection, rebuild from the Action Ledger, compare. */
    @PostMapping("/replay")
    public Projector.ReplayReport replay() {
        return projector.replay();
    }

    /** Demo: delete every balance table (keeping the Action Ledger) so the replay can be shown rebuilding them. */
    @PostMapping("/wipe")
    public WipeReport wipe() {
        if (!demoEndpoints) {
            throw new NotFoundException("Demo endpoints are turned off");
        }
        return new WipeReport(projector.wipeProjections(), actions.count());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
