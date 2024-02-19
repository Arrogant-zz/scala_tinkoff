package task2;

import task1.Response;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class HandlerImpl implements Handler {
    private final Client client;

    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public Duration timeout() {
        return null;
    }

    @Override
    public void performOperation() {
        while(true) {
            var event = client.readData();

            var result = event.recipients()
                    .parallelStream()
                    .map(it ->
                            CompletableFuture.supplyAsync(retryRequest(() -> client.sendData(it, event.payload())))
                    )
                    .toList();

            CompletableFuture.allOf(result.toArray(new CompletableFuture[result.size()])).join();
        }
    }

    private Supplier<Result> retryRequest(Supplier<Result> supplier) {
        return () -> {
            try {
                var result = supplier.get();
                while (result == Result.REJECTED) {
                    Thread.sleep(timeout().toMillis());
                    result = supplier.get();
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
