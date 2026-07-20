package org.loomsip.exchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ClientRequestExchangeTest {

    @Test
    void startsRetriesAndCompletesOneLogicalRequest() {
        SipRequest initial = request(1, "initial");
        List<RequestAttemptContext> contexts = new ArrayList<>();
        ClientRequestExchange<String> exchange = new ClientRequestExchange<>(
                initial,
                context -> {
                    contexts.add(context);
                    return "transaction-" + context.attemptNumber();
                },
                new RequestRetryPolicy(3),
                Runnable::run,
                failure -> {
                },
                8
        );

        RequestAttempt<String> first = await(exchange.start());
        SipRequest retryRequest = request(2, "retry");
        RequestAttempt<String> second = await(exchange.retry(retryRequest));
        SipResponse response = SipResponses.createResponse(retryRequest, 200, "OK", "server-tag");
        await(exchange.complete(response));

        assertEquals(1, first.attemptNumber());
        assertEquals("transaction-1", first.handle());
        assertEquals(2, second.attemptNumber());
        assertEquals("transaction-2", second.handle());
        assertTrue(contexts.getFirst().previousRequest().isEmpty());
        assertEquals(initial, contexts.getLast().previousRequest().orElseThrow());
        assertEquals(2, exchange.attemptCount());
        assertEquals(second, exchange.currentAttempt().orElseThrow());
        assertEquals(ClientRequestExchangeState.COMPLETED, exchange.state());
        assertEquals(response, await(exchange.completion()));
        await(exchange.closed());
    }

    @Test
    void attemptLimitFailsExchangeAndCompletion() {
        SipRequest initial = request(1, "initial");
        ClientRequestExchange<String> exchange = new ClientRequestExchange<>(
                initial,
                context -> "transaction-" + context.attemptNumber(),
                new RequestRetryPolicy(1),
                Runnable::run,
                failure -> {
                },
                8
        );
        await(exchange.start());

        CompletionException retryFailure = assertThrows(
                CompletionException.class,
                () -> await(exchange.retry(request(2, "retry")))
        );
        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> await(exchange.completion())
        );

        assertInstanceOf(RequestAttemptLimitException.class, retryFailure.getCause());
        assertInstanceOf(RequestAttemptLimitException.class, completionFailure.getCause());
        assertEquals(ClientRequestExchangeState.FAILED, exchange.state());
        assertEquals(1, exchange.attemptCount());
    }

    @Test
    void factoryFailureTerminatesExchangeAndReportsCause() {
        IllegalStateException expected = new IllegalStateException("cannot create transaction");
        List<Throwable> failures = new ArrayList<>();
        ClientRequestExchange<String> exchange = new ClientRequestExchange<>(
                request(1, "initial"),
                context -> {
                    throw expected;
                },
                RequestRetryPolicy.DEFAULT,
                Runnable::run,
                failures::add,
                8
        );

        CompletionException startFailure = assertThrows(
                CompletionException.class,
                () -> await(exchange.start())
        );

        assertEquals(expected, startFailure.getCause());
        assertEquals(List.of(expected), failures);
        assertEquals(ClientRequestExchangeState.FAILED, exchange.state());
        assertEquals(0, exchange.attemptCount());
    }

    @Test
    void concurrentRetriesAreSerializedWithMonotonicAttemptNumbers() throws Exception {
        List<Integer> attempts = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService exchangeExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory()
        ); ExecutorService callers = Executors.newFixedThreadPool(8)) {
            ClientRequestExchange<Integer> exchange = new ClientRequestExchange<>(
                    request(1, "initial"),
                    context -> {
                        attempts.add(context.attemptNumber());
                        return context.attemptNumber();
                    },
                    new RequestRetryPolicy(16),
                    exchangeExecutor,
                    failure -> {
                    },
                    32
            );
            await(exchange.start());

            List<CompletableFuture<RequestAttempt<Integer>>> retries = new ArrayList<>();
            for (int index = 2; index <= 16; index++) {
                int cseq = index;
                retries.add(CompletableFuture.supplyAsync(
                        () -> await(exchange.retry(request(cseq, "retry-" + cseq))),
                        callers
                ));
            }
            CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(16, exchange.attemptCount());
            assertEquals(
                    java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList(),
                    attempts
            );
            exchange.close();
            await(exchange.closed());
            assertEquals(ClientRequestExchangeState.CLOSED, exchange.state());
        }
    }

    @Test
    void closeFailsUnfinishedLogicalCompletion() {
        ClientRequestExchange<String> exchange = new ClientRequestExchange<>(
                request(1, "initial"),
                context -> "transaction",
                Runnable::run
        );
        await(exchange.start());

        exchange.close();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> await(exchange.completion())
        );
        assertInstanceOf(ClientRequestExchangeClosedException.class, failure.getCause());
        assertEquals(ClientRequestExchangeState.CLOSED, exchange.state());
        await(exchange.closed());
    }

    private static SipRequest request(long cseq, String branch) {
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-" + branch)
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "exchange@example.com")
                        .add("CSeq", cseq + " OPTIONS")
                        .build()
        );
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
