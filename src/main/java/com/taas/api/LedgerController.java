package com.taas.api;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.NotFoundException;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.Projector;

@RestController
@RequestMapping("/api")
public class LedgerController {

    private static final int MAX_PAGE = 500;

    public record WipeReport(Map<String, Long> balancesBefore, long eventsInActionLedger) {
    }

    private final ActionLedger actions;
    private final Projector projector;
    private final boolean demoEndpoints;

    public LedgerController(ActionLedger actions, Projector projector,
            @Value("${taas.demo-endpoints}") boolean demoEndpoints) {
        this.actions = actions;
        this.projector = projector;
        this.demoEndpoints = demoEndpoints;
    }

    /** The Action Ledger, in order. Page with {@code afterSeq} = the last seq you saw. */
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(required = false) String token,
            @RequestParam(defaultValue = "0") long afterSeq, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean newestFirst) {
        int pageSize = Math.max(1, Math.min(limit, MAX_PAGE));
        return actions.list(token, afterSeq, pageSize, newestFirst).stream().map(EventView::of).toList();
    }

    @GetMapping("/events/{txId}")
    public EventView event(@PathVariable String txId) {
        return actions.findByTxId(txId).map(EventView::of)
                .orElseThrow(() -> new NotFoundException("No event with txId " + txId));
    }

    /** The replay test: drop all projections, rebuild them from the Action Ledger, compare. */
    @PostMapping("/admin/replay")
    public Projector.ReplayReport replay() {
        return projector.replay();
    }

    /**
     * Demo only: delete every balance table, keeping the Action Ledger, so the replay can be shown
     * rebuilding them. Turned off with {@code TAAS_DEMO_ENDPOINTS=false}.
     */
    @PostMapping("/admin/projections/wipe")
    public WipeReport wipeProjections() {
        if (!demoEndpoints) {
            throw new NotFoundException("Demo endpoints are turned off");
        }
        return new WipeReport(projector.wipeProjections(), actions.count());
    }

    /** Hash chain, balance table vs postings, and double-entry checks, without changing anything. */
    @GetMapping("/admin/verify")
    public Projector.VerifyReport verify() {
        return projector.verify();
    }
}
