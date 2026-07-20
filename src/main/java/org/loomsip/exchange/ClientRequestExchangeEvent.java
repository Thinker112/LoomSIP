package org.loomsip.exchange;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Marker for immutable events serialized by one client request exchange. */
sealed interface ClientRequestExchangeEvent<T> permits
        ExchangeStart,
        ExchangeRetry,
        ExchangeComplete,
        ExchangeFailed,
        ExchangeClose {
}

record ExchangeStart<T>(CompletableFuture<RequestAttempt<T>> result)
        implements ClientRequestExchangeEvent<T> {

    ExchangeStart {
        Objects.requireNonNull(result, "result");
    }
}

record ExchangeRetry<T>(SipRequest request, CompletableFuture<RequestAttempt<T>> result)
        implements ClientRequestExchangeEvent<T> {

    ExchangeRetry {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
    }
}

record ExchangeComplete<T>(SipResponse response, CompletableFuture<Void> result)
        implements ClientRequestExchangeEvent<T> {

    ExchangeComplete {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(result, "result");
    }
}

record ExchangeFailed<T>(Throwable cause, CompletableFuture<Void> result)
        implements ClientRequestExchangeEvent<T> {

    ExchangeFailed {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(result, "result");
    }
}

record ExchangeClose<T>() implements ClientRequestExchangeEvent<T> {
}
