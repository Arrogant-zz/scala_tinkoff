package task1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class HandlerImpl implements Handler {
    private static final long TIMEOUT = 15;

    private final Client client;

    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        CompletableFuture<Result> result =
                CompletableFuture.supplyAsync(() -> this.getResult(id));

        ApplicationStatusResponse response = null;
        try {
            response = map(result.get(TIMEOUT, TimeUnit.SECONDS));
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            response = new ApplicationStatusResponse.Failure(null, 0);
        }

        return response;
    }

    private ApplicationStatusResponse map(Result result) {
        if (result.response instanceof Response.Success successResponse) {
            return new ApplicationStatusResponse.Success(successResponse.applicationId(), successResponse.applicationStatus());
        } else {
            return new ApplicationStatusResponse.Failure(null, result.retries);
        }
    }

    private Result getResult(String id) {
        CompletableFuture<Result> service1Response =
                CompletableFuture.supplyAsync(retryRequest(() -> client.getApplicationStatus1(id)));

        CompletableFuture<Result> service2Response =
                CompletableFuture.supplyAsync(retryRequest(() -> client.getApplicationStatus1(id)));

        var result = CompletableFuture.anyOf(service1Response, service2Response)
                .thenApply(it -> (Result) it);

        try {
            return result.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private Supplier<Result> retryRequest(Supplier<Response> supplier) {
        return () -> {
            int retries = 1;
            try {
                var result = supplier.get();
                while (!(result instanceof Response.Success)) {
                    if (result instanceof Response.RetryAfter) {
                        Thread.sleep(((Response.RetryAfter) result).delay().toMillis());
                    }
                    retries++;
                    result = supplier.get();
                }
                return new Result(result, retries);
            } catch (Exception e) {
                return new Result(new Response.Failure(e), retries);
            }
        };
    }

    private record Result(Response response, int retries) {
    }
}
