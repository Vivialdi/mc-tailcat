package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServerTargetTest {

    private static final String ADDRESS =
            "tcEXAMPLEaddressForDocsAndTestsOnly_NotARealServer00000000";
    private static final String OTHER =
            "tcQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ_dKdnDRYQ8u";

    private static final String RELAY = "--derpmap-url=https://relay.example/derpmap.json";

    private static NetworkDescriptor published(String name, String address, String... lockedArgs) {
        return NetworkDescriptor.of(name, address, 25565, "",
                new NetworkDescriptor.ClientSettings(List.of(lockedArgs), null));
    }

    @Test
    void carriesTheFlagsAnOperatorLockedIn() {
        ServerTarget target = ServerTarget.of(published("Survival", ADDRESS, RELAY), "config");

        assertEquals(List.of(RELAY), target.effectiveArgs(List.of()));
    }

    @Test
    void putsTheOperatorsFlagsAfterThePlayersOwn() {
        ServerTarget target = ServerTarget.of(published("Survival", ADDRESS, RELAY), "config");

        // tailcat takes the last of a repeated flag, so a relay the operator
        // requires has to win over one the player happened to set.
        assertEquals(List.of("--verbose", RELAY), target.effectiveArgs(List.of("--verbose")));
    }

    @Test
    void doesNotRepeatAFlagThePlayerAlreadySet() {
        ServerTarget target = ServerTarget.of(published("Survival", ADDRESS, RELAY), "config");

        assertEquals(List.of(RELAY), target.effectiveArgs(List.of(RELAY)));
    }

    @Test
    void aPlayersOwnEntryCarriesNoLockedSettings() {
        ServerTarget target = ServerTarget.of(new ClientConfig.Entry("Survival", ADDRESS, 25565));

        assertEquals(List.of(), target.effectiveArgs(List.of()));
        assertEquals("Survival (Tailcat)", target.displayName(" (Tailcat)"));
    }

    @Test
    void anOperatorsSuffixWinsOverThePlayersDefault() {
        NetworkDescriptor descriptor = NetworkDescriptor.of("Survival", ADDRESS, 25565, "",
                new NetworkDescriptor.ClientSettings(List.of(), " [SMP]"));

        assertEquals("Survival [SMP]",
                ServerTarget.of(descriptor, "config").displayName(" (Tailcat)"));
    }

    @Test
    void oneServerFoundTwiceBecomesOneEntryKeepingTheFirstName() {
        ServerTarget mine = ServerTarget.of(new ClientConfig.Entry("My Server", ADDRESS, 25565));
        ServerTarget shipped = ServerTarget.of(published("Survival", ADDRESS, RELAY), "config");

        List<ServerTarget> resolved = ServerTarget.deduplicate(List.of(mine, shipped));

        assertEquals(1, resolved.size());
        // The player renamed it, so that name stays...
        assertEquals("My Server", resolved.get(0).name());
        // ...but the flags without which the server is unreachable still apply.
        assertEquals(List.of(RELAY), resolved.get(0).effectiveArgs(List.of()));
    }

    @Test
    void differentServersStaySeparate() {
        List<ServerTarget> resolved = ServerTarget.deduplicate(List.of(
                ServerTarget.of(published("Survival", ADDRESS), "a"),
                ServerTarget.of(published("Creative", OTHER), "b")));

        assertEquals(2, resolved.size());
    }

    @Test
    void rejectsTargetsThatCouldNotBeConnectedTo() {
        assertTrue(ServerTarget.of(published("Survival", ADDRESS), "config").isUsable());
        assertFalse(ServerTarget.of(new ClientConfig.Entry("n", "not-an-address", 25565)).isUsable());
        assertFalse(ServerTarget.of(new ClientConfig.Entry("n", ADDRESS, 0)).isUsable());
    }
}
