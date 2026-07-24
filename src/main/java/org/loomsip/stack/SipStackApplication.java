package org.loomsip.stack;

import org.loomsip.info.InfoHandler;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.refer.ReferHandler;
import org.loomsip.subscription.SubscriptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Immutable application capability registry frozen before Stack startup. */
public final class SipStackApplication {
    private final TuHandlerRegistry requests;
    private final Map<String, InfoHandler> infoHandlers;
    private final Map<EventHeaderValue, SubscriptionHandler> subscriptionHandlers;
    private final ReferHandler referHandler;
    private final org.loomsip.refer.ReferSubscriptionListener referSubscriptionListener;
    private final Consumer<Throwable> errorListener;

    private SipStackApplication(Builder builder) {
        requests = builder.requests.build();
        infoHandlers = Map.copyOf(builder.infoHandlers);
        subscriptionHandlers = Map.copyOf(builder.subscriptionHandlers);
        referHandler = builder.referHandler;
        referSubscriptionListener = builder.referSubscriptionListener;
        errorListener = builder.errorListener;
    }
    /** @return application registry builder */
    public static Builder builder() { return new Builder(); }
    TuHandlerRegistry requests() { return requests; }
    /** @return registered INFO package handlers by normalized package name */
    public Map<String, InfoHandler> infoHandlers() { return infoHandlers; }
    /** @return registered subscription Event handlers */
    public Map<EventHeaderValue, SubscriptionHandler> subscriptionHandlers() { return subscriptionHandlers; }
    /** @return optional RFC 3515 handler */
    public Optional<ReferHandler> referHandler() { return Optional.ofNullable(referHandler); }
    /** @return observer for accepted implicit refer-subscriptions */
    public org.loomsip.refer.ReferSubscriptionListener referSubscriptionListener() { return referSubscriptionListener; }
    /** @return isolated application error listener */
    public Consumer<Throwable> errorListener() { return errorListener; }

    /** Mutable startup-only capability registration builder. */
    public static final class Builder {
        private final TuHandlerRegistry.Builder requests = TuHandlerRegistry.builder();
        private final Map<String, InfoHandler> infoHandlers = new LinkedHashMap<>();
        private final Map<EventHeaderValue, SubscriptionHandler> subscriptionHandlers = new LinkedHashMap<>();
        private ReferHandler referHandler;
        private org.loomsip.refer.ReferSubscriptionListener referSubscriptionListener = org.loomsip.refer.ReferSubscriptionListener.noop();
        private Consumer<Throwable> errorListener = ignored -> { };
        public Builder inviteHandler(IncomingRequestHandler handler) { requests.inviteHandler(handler); return this; }
        public Builder requestHandler(IncomingRequestHandler handler) { requests.requestHandler(handler); return this; }
        public Builder infoPackage(String name, InfoHandler handler) {
            String key = Objects.requireNonNull(name, "name").toLowerCase(java.util.Locale.ROOT);
            if (key.isBlank() || infoHandlers.putIfAbsent(key, Objects.requireNonNull(handler, "handler")) != null) throw new IllegalArgumentException("duplicate INFO package: " + name);
            return this;
        }
        public Builder subscriptionPackage(EventHeaderValue event, SubscriptionHandler handler) {
            if (subscriptionHandlers.putIfAbsent(Objects.requireNonNull(event, "event"), Objects.requireNonNull(handler, "handler")) != null) throw new IllegalArgumentException("duplicate Event: " + event.wireValue());
            return this;
        }
        public Builder referHandler(ReferHandler handler) { referHandler = Objects.requireNonNull(handler, "handler"); return this; }
        public Builder referSubscriptionListener(org.loomsip.refer.ReferSubscriptionListener listener) { referSubscriptionListener = Objects.requireNonNull(listener, "listener"); return this; }
        public Builder errorListener(Consumer<Throwable> listener) { errorListener = Objects.requireNonNull(listener, "listener"); return this; }
        public SipStackApplication build() { return new SipStackApplication(this); }
    }
}
