package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.app.listener.OutboxConsumerNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxConsumerNamesTest {

    @Test
    void unique_joinsPrefixHostPid() {
        assertEquals("relay-box-42", OutboxConsumerNames.unique("relay", "box", 42L));
    }

    @Test
    void unique_blankHost_usesUnknown() {
        assertEquals("relay-unknown-1", OutboxConsumerNames.unique("relay", "  ", 1L));
        assertEquals("relay-unknown-1", OutboxConsumerNames.unique("relay", null, 1L));
    }

    @Test
    void unique_differentPid_differentName() {
        assertNotEquals(
                OutboxConsumerNames.unique("relay", "box", 1L),
                OutboxConsumerNames.unique("relay", "box", 2L));
    }

    @Test
    void forThisProcess_startsWithPrefixDash() {
        String name = OutboxConsumerNames.forThisProcess("relay");
        assertTrue(name.startsWith("relay-"));
        assertTrue(name.length() > "relay-".length());
    }
}
