package io.quarkiverse.langchain4j.prototype.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DefaultPersistenceUnitTest {
    @Inject
    ChatMemoryStore store;

    @Test
    void cdiStoreRoundTripsRealMessages() {
        assertInstanceOf(HibernateChatMemoryStore.class, store);
        store.updateMessages("cdi", HibernateChatMemoryStoreTest.messages());
        assertEquals(HibernateChatMemoryStoreTest.messages(), store.getMessages("cdi"));
        store.deleteMessages("cdi");
        assertEquals(List.of(), store.getMessages("cdi"));
    }

    @Test
    void cdiStoreReplacesAndIsolatesIds() {
        store.updateMessages("cdi-1", List.of(UserMessage.from("old")));
        store.updateMessages("cdi-2", List.of(UserMessage.from("other")));
        store.updateMessages("cdi-1", List.of(UserMessage.from("new")));
        assertEquals(List.<ChatMessage> of(UserMessage.from("new")), store.getMessages("cdi-1"));
        assertEquals(List.<ChatMessage> of(UserMessage.from("other")), store.getMessages("cdi-2"));
    }
}
