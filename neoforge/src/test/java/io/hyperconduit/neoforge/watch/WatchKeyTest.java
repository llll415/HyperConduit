package io.hyperconduit.neoforge.watch;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WatchKeyTest {

    @Test
    void playerKeysAreEqualAcrossFreshValuesWithSameUuid() {
        UUID id = UUID.randomUUID();

        WatchKey first = WatchKey.player(id);
        WatchKey second = WatchKey.player(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits()));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void playerKeysDifferByUuid() {
        assertNotEquals(WatchKey.player(UUID.randomUUID()), WatchKey.player(UUID.randomUUID()));
    }

    @Test
    void sourceKeysUseIdentityInsteadOfEquals() {
        Object equalsEverything = new Object() {
            @Override
            public boolean equals(Object other) {
                return true;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        };
        Object anotherEqualsEverything = new Object() {
            @Override
            public boolean equals(Object other) {
                return true;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        };

        assertEquals(WatchKey.source(equalsEverything), WatchKey.source(equalsEverything));
        assertNotEquals(WatchKey.source(equalsEverything), WatchKey.source(anotherEqualsEverything));
    }

    @Test
    void playerAndSourceKeysNeverCollide() {
        UUID id = UUID.randomUUID();
        assertNotEquals(WatchKey.player(id), WatchKey.source(id));
    }

    @Test
    void keysRejectNullIdentity() {
        assertThrows(NullPointerException.class, () -> WatchKey.player(null));
        assertThrows(NullPointerException.class, () -> WatchKey.source(null));
    }
}
