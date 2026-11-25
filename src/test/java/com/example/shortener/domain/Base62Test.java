package com.example.shortener.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base62Test {

    @Test
    void encodesKnownValues() {
        assertEquals("0", Base62.encode(0));
        assertEquals("z", Base62.encode(61));
        assertEquals("10", Base62.encode(62));
        // 62^5 is the first id issued by the sequence, so the first code is 6 characters.
        assertEquals("100000", Base62.encode(916_132_832L));
    }

    @Test
    void roundTrips() {
        for (long id : new long[]{1, 61, 62, 12_345, 916_132_832L, 3_521_614_606_207L, Long.MAX_VALUE}) {
            assertEquals(id, Base62.decode(Base62.encode(id)));
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("ab-c"));
    }
}
