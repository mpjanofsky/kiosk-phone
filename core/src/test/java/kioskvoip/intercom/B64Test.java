// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

class B64Test {
    @Test
    void matchesJdkForAllLengths() {
        Random r = new Random(1);
        for (int len = 0; len < 70; len++) {
            byte[] d = new byte[len];
            r.nextBytes(d);
            assertEquals(Base64.getEncoder().encodeToString(d), B64.encode(d));
            assertEquals(Base64.getUrlEncoder().encodeToString(d), B64.encodeUrl(d));
            assertArrayEquals(d, B64.decodeUrl(B64.encodeUrl(d)));
            assertArrayEquals(d, B64.decodeUrl(Base64.getUrlEncoder().withoutPadding().encodeToString(d)));
        }
    }
}
