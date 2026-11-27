package org.fuin.porelo.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * The person's running planner, called exactly as the window calls it: the address file read, the
 * listener on its port made to prove the token, every request signed in {@code Porelo-Signature}
 * and no answer taken whose {@code Porelo-Answer} does not hold. The token is held only in the
 * address read for a call and never sent.
 *
 * <p>The listener proves the token before every request: a planner that ended leaves its port to
 * whoever binds it next, and nothing else would say so. Each exchange, its answer's body included,
 * ends within its limit, so a listener that stops sending holds no call.
 */
final class PlannerClient implements Planner {

    /** How long one reading request may take, before the services factor. */
    static final Duration READ = Duration.ofSeconds(30);

    /** How long one writing request may take, before the services factor. */
    static final Duration WRITE = Duration.ofMinutes(2);

    /** How long a proof may take, before the services factor: a planner still starting is slow. */
    static final Duration PROOF = Duration.ofSeconds(60);

    /** The most an answer may hold, as the window takes. */
    static final int MOST = 16 * 1024 * 1024;

    private static final int PROOF_MOST = 1024;

    /** The most of a refused description's body its words show. */
    private static final int SHOWN = 512;

    private static final String HOST = "127.0.0.1";

    private static final String JSON = "application/json";

    private final Supplier<PlannerAddress> addresses;

    private final LimitFactors limits;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    private final HttpClient http;

    /**
     * Calls the planner the address file names, read anew for every call.
     *
     * @param addresses Reads the address, throwing {@link Planner.Unavailable} when it cannot.
     * @param limits The factors the limits follow.
     * @param clock The clock requests are signed by.
     */
    PlannerClient(Supplier<PlannerAddress> addresses, LimitFactors limits, Clock clock) {
        this.addresses = addresses;
        this.limits = limits;
        this.clock = clock;
        this.http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        // Straight to the loopback address, never through a proxy.
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .connectTimeout(limits.services(PROOF))
                        .build();
    }

    /**
     * Calls the planner of this account.
     *
     * @param environment The process's environment, for {@code XDG_RUNTIME_DIR}.
     * @param limits The factors the limits follow.
     * @return The client.
     */
    static PlannerClient of(Map<String, String> environment, LimitFactors limits) {
        return new PlannerClient(() -> PlannerAddress.read(environment), limits, Clock.systemUTC());
    }

    @Override
    public String openApi() {
        final Answer answer = call(new Request("GET", "/openapi", null));
        if (answer.status() != 200) {
            final String body = answer.body();
            throw new Unavailable(
                    Reason.NO_DESCRIPTION,
                    "the planner answered its description with "
                            + answer.status()
                            + ": "
                            + (body.length() > SHOWN ? body.substring(0, SHOWN) + "..." : body),
                    null);
        }
        return answer.body();
    }

    @Override
    public Answer call(Request request) {
        target(request.target());
        final PlannerAddress address = proven(addresses.get());
        final Received received = send(address, request);
        if (received.status() != 401) {
            return received.answer();
        }
        // Only a planner started anew under another port or token is asked once more.
        final PlannerAddress again = addresses.get();
        if (again.port() == address.port() && again.token().equals(address.token())) {
            throw refusedSigned(address);
        }
        final @Nullable Unavailable unproven = prove(again);
        if (unproven != null) {
            throw unproven;
        }
        final Received retried = send(again, request);
        if (retried.status() == 401) {
            throw refusedSigned(again);
        }
        return retried.answer();
    }

    /**
     * The address whose listener proved the token: on a failed proof the file is read again once,
     * since the planner may have started anew.
     */
    private PlannerAddress proven(PlannerAddress address) {
        final @Nullable Unavailable first = prove(address);
        if (first == null) {
            return address;
        }
        final PlannerAddress again = addresses.get();
        if (again.port() == address.port() && again.token().equals(address.token())) {
            throw first;
        }
        final @Nullable Unavailable second = prove(again);
        if (second != null) {
            throw second;
        }
        return again;
    }

    /** Asks the listener for its proof; {@code null} when it gave the one proof. */
    private @Nullable Unavailable prove(PlannerAddress address) {
        final byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        final String body = Json.write(Map.of("challenge", PlannerSignature.base64(challenge)));
        final Duration limit = limits.services(PROOF);
        final HttpRequest request =
                HttpRequest.newBuilder(uri(address, "/proof"))
                        .timeout(limit)
                        .header("Content-Type", JSON)
                        .header("Accept", JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        final HttpResponse<byte[]> response;
        try {
            response = exchange(request, limit, PROOF_MOST);
        } catch (ConnectException ex) {
            return new Unavailable(
                    Reason.NOT_RUNNING,
                    "no planner listens on port " + address.port() + ": " + ex.getMessage(),
                    ex);
        } catch (IOException | TimeoutException ex) {
            return notProven(address, "its answer failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unreachable(address, "the call was interrupted", ex);
        }
        if (response.statusCode() != 200) {
            return notProven(address, "it answered " + response.statusCode(), null);
        }
        final Object json;
        try {
            json = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (JsonException ex) {
            // The listener's words are left out: they are a stranger's.
            return notProven(address, "its answer is not JSON", null);
        }
        if (json instanceof Map<?, ?> fields
                && fields.size() == 1
                && fields.get("proof") instanceof String proof
                && PlannerSignature.same(
                        proof,
                        PlannerSignature.proof(address.token(), address.port(), challenge))) {
            return null;
        }
        return notProven(address, "its answer is not the proof", null);
    }

    /** One signed request and its answer, its proof checked. */
    private Received send(PlannerAddress address, Request request) {
        final byte[] body =
                request.body() == null
                        ? new byte[0]
                        : request.body().getBytes(StandardCharsets.UTF_8);
        final String type = request.body() == null ? "" : JSON;
        final byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        final String signature =
                PlannerSignature.request(
                        address.token(),
                        request.method(),
                        request.target(),
                        host(address.port()),
                        type,
                        body,
                        clock.instant().getEpochSecond(),
                        nonce);
        final Duration limit = limits.services(request.method().equals("GET") ? READ : WRITE);
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri(address, request.target()))
                        .timeout(limit)
                        .header("Accept", JSON)
                        .header(PlannerSignature.HEADER, signature)
                        .method(
                                request.method(),
                                request.body() == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (request.body() != null) {
            builder.header("Content-Type", type);
        }
        final HttpResponse<byte[]> response;
        try {
            response = exchange(builder.build(), limit, MOST);
        } catch (TimeoutException ex) {
            throw unreachable(address, "did not answer in time", ex);
        } catch (IOException ex) {
            throw unreachable(address, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unreachable(address, "the call was interrupted", ex);
        }
        if (response.statusCode() == 401) {
            // The planner signs no 401; a forged one only leads to a fresh proof.
            return new Received(401, new Answer(401, "", ""));
        }
        final List<String> proofs = response.headers().allValues(PlannerSignature.ANSWER_HEADER);
        if (proofs.size() != 1
                || !PlannerSignature.same(
                        proofs.getFirst(),
                        PlannerSignature.answer(
                                address.token(), nonce, response.statusCode(), response.body()))) {
            throw new Unavailable(
                    Reason.ANSWER_FORGED,
                    "the answer of the planner on port "
                            + address.port()
                            + " to "
                            + request.method()
                            + " "
                            + request.target()
                            + " carries no proof of its token",
                    null);
        }
        return new Received(
                response.statusCode(),
                new Answer(
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""),
                        new String(response.body(), StandardCharsets.UTF_8)));
    }

    /**
     * One exchange, its body bounded, ended within its limit whatever the listener does: the
     * client's own timeout ends with the answer's head.
     */
    private HttpResponse<byte[]> exchange(HttpRequest request, Duration limit, int most)
            throws IOException, InterruptedException, TimeoutException {
        final CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, info -> new Bounded(most));
        try {
            return pending.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            pending.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException(String.valueOf(ex.getCause()), ex.getCause());
        }
    }

    /** Refuses a target that is not one path and query on the planner's own address. */
    private static void target(String target) {
        if (!target.startsWith("/") || target.startsWith("//") || target.contains("#")) {
            throw new IllegalArgumentException("'" + target + "' is no request target");
        }
        final URI uri = uri(new PlannerAddress(1, "x"), target);
        if (!HOST.equals(uri.getHost()) || uri.getPort() != 1 || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("'" + target + "' leads away from the planner");
        }
    }

    /** The {@code Host} header the client sends, which leaves out HTTP's own port. */
    private static String host(int port) {
        return port == 80 ? HOST : HOST + ":" + port;
    }

    private static Unavailable notProven(
            PlannerAddress address, String why, @Nullable Exception cause) {
        return new Unavailable(
                Reason.PROOF_REFUSED,
                "the listener on port "
                        + address.port()
                        + " did not prove the planner's token: "
                        + why,
                cause);
    }

    private static Unavailable refusedSigned(PlannerAddress address) {
        return new Unavailable(
                Reason.NOT_ACCEPTED,
                "the planner on port " + address.port() + " refused a signed request",
                null);
    }

    private static Unavailable unreachable(
            PlannerAddress address, @Nullable String why, @Nullable Exception cause) {
        return new Unavailable(
                Reason.UNREACHABLE,
                "the planner on port " + address.port() + " " + (why == null ? "failed" : why),
                cause);
    }

    private static URI uri(PlannerAddress address, String target) {
        return URI.create("http://" + HOST + ":" + address.port() + target);
    }

    private record Received(int status, Answer answer) {}

    /** An answer's body larger than a planner sends. */
    static final class TooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        TooLarge(int most) {
            super("answered more than " + most + " bytes");
        }
    }

    /** Collects a body up to a bound, and cancels the exchange the moment it passes it. */
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {

        private final int most;

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private final CompletableFuture<byte[]> done = new CompletableFuture<>();

        private Flow.@Nullable Subscription subscription;

        Bounded(int most) {
            this.most = most;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return done;
        }

        @Override
        public void onSubscribe(Flow.Subscription given) {
            subscription = given;
            given.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (final ByteBuffer item : items) {
                if (bytes.size() + item.remaining() > most) {
                    final Flow.Subscription held = subscription;
                    if (held != null) {
                        held.cancel();
                    }
                    done.completeExceptionally(new TooLarge(most));
                    return;
                }
                final byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            done.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            done.complete(bytes.toByteArray());
        }
    }
}
