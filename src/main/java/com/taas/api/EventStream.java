package com.taas.api;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taas.ledger.ActionEvent;
import com.taas.outbox.OutboxSubscriber;

/** Server-sent events for the live explorer, fed from the outbox: a client only ever sees committed events. */
@Component
public class EventStream implements OutboxSubscriber {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("{\"connected\":true}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    @Override
    public void onEvent(ActionEvent event) {
        ActionView view = ActionView.of(event);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name("action").data(view,
                        MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }
}
