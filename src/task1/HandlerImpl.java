package task1;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class HandlerImpl implements Handler {
    private static final long TIMEOUT = 15;

    private final Client client;

    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        AtomicInteger retries = new AtomicInteger(0);
        AtomicLong duration = new AtomicLong(0);

        CompletableFuture<Response> result =
                CompletableFuture.supplyAsync(() -> this.getResult(id, retries, duration));

        ApplicationStatusResponse response = null;
        try {
            response = map(result.get(TIMEOUT, TimeUnit.SECONDS), retries, duration);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            response = new ApplicationStatusResponse.Failure(null, retries.get());
        }

        return response;
    }

    private ApplicationStatusResponse map(Response result, AtomicInteger retries, AtomicLong duration) {
        if (result instanceof Response.Success successResponse) {
            return new ApplicationStatusResponse.Success(successResponse.applicationId(), successResponse.applicationStatus());
        } else {
            return new ApplicationStatusResponse.Failure(Duration.ofNanos(duration.get()), retries.get());
        }
    }

    private Response getResult(String id, AtomicInteger retries, AtomicLong duration) {
        CompletableFuture<Response> service1Response =
                CompletableFuture.supplyAsync(retryRequest(() -> client.getApplicationStatus1(id), retries, duration));

        CompletableFuture<Response> service2Response =
                CompletableFuture.supplyAsync(retryRequest(() -> client.getApplicationStatus1(id), retries, duration));

        var result = CompletableFuture.anyOf(service1Response, service2Response)
                .thenApply(it -> (Response) it);

        try {
            return result.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private Supplier<Response> retryRequest(Supplier<Response> supplier, AtomicInteger retries, AtomicLong duration) {
        return () -> {
            try {
                retries.incrementAndGet();
                var start = System.nanoTime();
                var result = supplier.get();
                duration.set(System.nanoTime() - start);
                while (!(result instanceof Response.Success)) {
                    if (result instanceof Response.RetryAfter) {
                        Thread.sleep(((Response.RetryAfter) result).delay().toMillis());
                    }
                    retries.incrementAndGet();
                    start = System.nanoTime();
                    result = supplier.get();
                    duration.set(System.nanoTime() - start);
                }
                return result;
            } catch (Exception e) {
                return new Response.Failure(e);
            }
        };
    }
}
