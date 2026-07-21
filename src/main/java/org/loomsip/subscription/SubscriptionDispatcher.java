package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe UAS registry and parser boundary for SUBSCRIBE Event packages.
 *
 * <pre>{@code
 * SUBSCRIBE --> SubscriptionDispatcher --> Event package handler
 *                    |
 *                    +--> unknown package / malformed header
 * }</pre>
 */
public final class SubscriptionDispatcher {

    private final ConcurrentHashMap<String, SubscriptionHandler> handlers = new ConcurrentHashMap<>();

    /** Registers one handler for an Event package without an event-id discriminator. */
    public void register(EventHeaderValue event, SubscriptionHandler handler) {
        Objects.requireNonNull(event, "event");
        if (event.eventId().isPresent()) {
            throw new IllegalArgumentException("registered Event package must not contain event-id");
        }
        if (handlers.putIfAbsent(event.normalizedPackageName(), Objects.requireNonNull(handler, "handler")) != null) {
            throw new IllegalArgumentException("Event package already registered: " + event.packageName());
        }
    }

    /** Removes one registered Event package handler. */
    public void unregister(EventHeaderValue event) {
        Objects.requireNonNull(event, "event");
        handlers.remove(event.normalizedPackageName());
    }

    /** Parses one SUBSCRIBE and returns the selected handler with immutable request metadata. */
    public Optional<Dispatch> dispatch(SipRequest request) throws org.loomsip.message.header.SipHeaderValueException {
        Objects.requireNonNull(request, "request");
        if (!SipMethod.SUBSCRIBE.equals(request.method())) {
            throw new IllegalArgumentException("dispatcher accepts SUBSCRIBE only");
        }
        EventHeaderValue event = SipHeaderValues.event(request.headers());
        SubscriptionHandler handler = handlers.get(event.normalizedPackageName());
        return handler == null ? Optional.empty() : Optional.of(new Dispatch(
                handler, new SubscriptionEventRequest(request, event, SipHeaderValues.expires(request.headers()))
        ));
    }

    /** Immutable selected handler and parsed request pair. */
    public record Dispatch(SubscriptionHandler handler, SubscriptionEventRequest request) {
        /** Validates selected handler and parsed request. */
        public Dispatch {
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(request, "request");
        }
    }
}
