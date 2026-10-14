package io.quarkiverse.langchain4j.prototype.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NamedPersistenceUnitTest.Profile.class)
class NamedPersistenceUnitTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("prototype.memory.persistence-unit", "other");
        }
    }

    @Inject
    ChatMemoryStore store;

    @Inject
    SessionFactory defaultFactory;

    @Inject
    @PersistenceUnit("other")
    SessionFactory otherFactory;

    @Test
    void namedStoreWritesOnlySelectedDatabase() {
        store.updateMessages("named", HibernateChatMemoryStoreTest.messages());
        assertEquals(HibernateChatMemoryStoreTest.messages(), store.getMessages("named"));
        assertEquals(List.of(), new HibernateChatMemoryStore(defaultFactory).getMessages("named"));
        assertEquals(HibernateChatMemoryStoreTest.messages(),
                new HibernateChatMemoryStore(otherFactory).getMessages("named"));
        store.deleteMessages("named");
        assertEquals(List.of(), new HibernateChatMemoryStore(otherFactory).getMessages("named"));
    }
}
