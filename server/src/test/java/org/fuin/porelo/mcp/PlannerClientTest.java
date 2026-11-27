package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * porelo-mcp calls the planner as the window does: only a listener that proved the token gets a
 * request, every request is signed, and no answer is taken without the planner's proof.
 */
class PlannerClientTest {

    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    private static final String OTHER = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8";

    private final List<StandInPlanner> planners = new ArrayList<>();

    @TempDir Path runtime;

    @AfterEach
    void stopThePlanners() {
        planners.forEach(StandInPlanner::close);
    }

    // Harm: the person's board read or changed wrongly, or a refusal of the planner's lost.
    @Test
    void aSignedCallGetsThePlannersAnswerWhateverItsStatus() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        final PlannerClient client = client(planner::address);

        final Planner.Answer moved =
                client.call(new Planner.Request("POST", "/projects/p/moves?x=1", "{\"a\":1}"));
        planner.status = 409;
        planner.body = "{\"title\":\"RunChanged\"}";
        final Planner.Answer refused = client.call(new Planner.Request("GET", "/projects/p", null));

        assertThat(moved.status()).as("the move's status").isEqualTo(200);
        assertThat(moved.body()).as("the move's answer").isEqualTo("{\"ok\":true}");
        assertThat(refused.status()).as("a refusal's status").isEqualTo(409);
        assertThat(refused.body()).as("a refusal's body").isEqualTo("{\"title\":\"RunChanged\"}");
        assertThat(planner.seen)
                .as("each request goes to a listener proved right before it")
                .extracting(line -> line.substring(0, line.indexOf(' ', 5)))
                .containsExactly(
                        "POST /proof",
                        "POST /projects/p/moves?x=1",
                        "POST /proof",
                        "GET /projects/p");
        assertNoToken(planner);
    }

    // Harm: the person's request, its body and signature, sent to a stranger on the port.
    @Test
    void aListenerThatDoesNotProveTheTokenGetsNoRequest() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        planner.proves = false;

        assertThat(reason(client(planner::address), new Planner.Request("GET", "/projects", null)))
                .as("a listener without the proof")
                .isEqualTo(Planner.Reason.PROOF_REFUSED);
        assertThat(planner.seen)
                .as("nothing but the proof reached it")
                .allMatch(line -> line.startsWith("POST /proof "));
    }

    // Harm: a stranger's answer taken for the planner's.
    @Test
    void anAnswerWithoutThePlannersProofIsNotTaken() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        planner.signsAnswers = false;

        assertThat(reason(client(planner::address), new Planner.Request("GET", "/projects", null)))
                .as("an answer with a wrong Porelo-Answer")
                .isEqualTo(Planner.Reason.ANSWER_FORGED);
    }

    // Harm: a planner that is not running reported as something else, or as tools that answer.
    @Test
    void noAddressFileAndNoListenerAreNamedAsNoPlannerRunning() throws Exception {
        final int free;
        try (ServerSocket socket = new ServerSocket(0)) {
            free = socket.getLocalPort();
        }
        final long own = SocketPlace.ownUid();

        assertThat(
                        reason(
                                client(() -> PlannerAddress.read(runtime.toString(), own)),
                                new Planner.Request("GET", "/projects", null)))
                .as("no planner.json")
                .isEqualTo(Planner.Reason.NOT_RUNNING);
        assertThat(
                        reason(
                                client(() -> new PlannerAddress(free, TOKEN)),
                                new Planner.Request("GET", "/projects", null)))
                .as("nobody listening on the port")
                .isEqualTo(Planner.Reason.NOT_RUNNING);
    }

    // Harm: calls lost after the planner started anew, or a refusing planner asked forever.
    @Test
    void aRefusalIsTriedOnceMoreOnlyWhenThePlannerStartedAnew() throws Exception {
        final StandInPlanner old = planner(TOKEN);
        old.refusesAll = true;
        final StandInPlanner restarted = planner(OTHER);
        final AtomicReference<PlannerAddress> file = new AtomicReference<>(old.address());

        assertThat(reason(client(file::get), new Planner.Request("GET", "/projects", null)))
                .as("401 from the planner the file still names")
                .isEqualTo(Planner.Reason.NOT_ACCEPTED);

        final PlannerClient client =
                client(
                        () -> {
                            final PlannerAddress now = file.get();
                            file.set(restarted.address());
                            return now;
                        });
        file.set(old.address());
        assertThat(client.call(new Planner.Request("GET", "/projects", null)).status())
                .as("the planner the file names after a 401 answers")
                .isEqualTo(200);
        assertNoToken(old);
        assertNoToken(restarted);
    }

    // Harm: a call held for good by a listener that sends the head of an answer and stops.
    @Test
    void aListenerThatStopsSendingItsAnswerEndsTheCallWithinItsLimit() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        planner.stalls = true;
        final PlannerClient client =
                new PlannerClient(
                        planner::address, LimitFactors.parse("services=0.1"), Clock.systemUTC());
        final long started = System.nanoTime();

        assertThat(reason(client, new Planner.Request("GET", "/projects", null)))
                .as("a body that never ends")
                .isEqualTo(Planner.Reason.UNREACHABLE);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                .as("ended near its 3 s limit, not held")
                .isLessThan(java.time.Duration.ofSeconds(20));
    }

    // Harm: the person's signed request sent to another host than the planner's.
    @Test
    void aTargetThatLeadsAwayFromThePlannerIsNeverSent() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        final PlannerClient client = client(planner::address);

        for (final String target :
                new String[] {"@elsewhere.example/x", "//elsewhere.example/x", "/x#y", "x"}) {
            assertThat(
                            catchThrowableOfType(
                                    IllegalArgumentException.class,
                                    () -> client.call(new Planner.Request("GET", target, null))))
                    .as("the target %s", target)
                    .isNotNull();
        }
        assertThat(planner.seen).as("nothing was sent, not even a proof").isEmpty();
    }

    // Harm: a call repeated without end against planners that refuse it.
    @Test
    void aSecondRefusalAfterThePlannerStartedAnewEndsTheCall() throws Exception {
        final StandInPlanner old = planner(TOKEN);
        old.refusesAll = true;
        final StandInPlanner restarted = planner(OTHER);
        restarted.refusesAll = true;
        final AtomicReference<PlannerAddress> file = new AtomicReference<>(old.address());

        assertThat(
                        reason(
                                client(
                                        () -> {
                                            final PlannerAddress now = file.get();
                                            file.set(restarted.address());
                                            return now;
                                        }),
                                new Planner.Request("GET", "/projects", null)))
                .as("401 from the planner started anew too")
                .isEqualTo(Planner.Reason.NOT_ACCEPTED);
        assertThat(restarted.seen)
                .as("the planner started anew asked once")
                .filteredOn(line -> line.startsWith("GET "))
                .hasSize(1);
    }

    // Harm: tools made from an answer that is no description.
    @Test
    void aDescriptionAnsweredWithAnotherStatusIsNoDescription() throws Exception {
        final StandInPlanner planner = planner(TOKEN);
        planner.status = 404;

        assertThat(
                        catchThrowableOfType(
                                Planner.Unavailable.class,
                                () -> client(planner::address).openApi()))
                .as("GET /openapi answered 404")
                .extracting(Planner.Unavailable::reason)
                .isEqualTo(Planner.Reason.NO_DESCRIPTION);
    }

    private StandInPlanner planner(String token) throws Exception {
        final StandInPlanner planner = new StandInPlanner(token);
        planners.add(planner);
        return planner;
    }

    private static PlannerClient client(Supplier<PlannerAddress> addresses) {
        return new PlannerClient(addresses, LimitFactors.DEFAULT, Clock.systemUTC());
    }

    private Planner.Reason reason(PlannerClient client, Planner.Request request) {
        final Planner.Unavailable failed =
                catchThrowableOfType(Planner.Unavailable.class, () -> client.call(request));
        assertThat(failed).as("the call fails").isNotNull();
        assertThat(failed.getMessage()).as("the words of a failure").doesNotContain(TOKEN, OTHER);
        return failed.reason();
    }

    private static void assertNoToken(StandInPlanner planner) {
        assertThat(planner.seen)
                .as("no request carries a token itself")
                .noneMatch(line -> line.contains(TOKEN) || line.contains(OTHER));
    }
}
