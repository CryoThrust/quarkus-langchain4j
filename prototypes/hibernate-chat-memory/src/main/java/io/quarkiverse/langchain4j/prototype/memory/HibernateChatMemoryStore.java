package io.quarkiverse.langchain4j.prototype.memory;

import java.util.List;
import java.util.Objects;

import org.hibernate.SessionFactory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkiverse.langchain4j.prototype.memory.model.MemorySnapshot;

/**
 * Blocking ORM prototype. Owns each short transaction, but never owns or closes the supplied factory.
 * Update replaces a complete snapshot, as ChatMemoryStore requires; it is not an append/event-log API.
 */
public class HibernateChatMemoryStore implements ChatMemoryStore {
    public interface MessageCodec {
        String encode(List<ChatMessage> messages);

        List<ChatMessage> decode(String payload);
    }

    public static final MessageCodec LANGCHAIN4J_CODEC = new MessageCodec() {
        @Override
        public String encode(List<ChatMessage> messages) {
            return ChatMessageSerializer.messagesToJson(messages);
        }

        @Override
        public List<ChatMessage> decode(String payload) {
            return ChatMessageDeserializer.messagesFromJson(payload);
        }
    };

    private final SessionFactory factory;
    private final MessageCodec codec;

    public HibernateChatMemoryStore(SessionFactory factory) {
        this(factory, LANGCHAIN4J_CODEC);
    }

    public HibernateChatMemoryStore(SessionFactory factory, MessageCodec codec) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = memoryKey(memoryId);
        String payload = factory.fromTransaction(session -> {
            MemorySnapshot snapshot = session.find(MemorySnapshot.class, key);
            return snapshot == null ? null : snapshot.messagesJson();
        });
        return payload == null ? List.of() : List.copyOf(codec.decode(payload));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = memoryKey(memoryId);
        String payload = Objects.requireNonNull(codec.encode(List.copyOf(messages)), "encoded messages");
        factory.inTransaction(session -> {
            MemorySnapshot snapshot = session.find(MemorySnapshot.class, key);
            if (snapshot == null) {
                session.persist(new MemorySnapshot(key, payload));
            } else {
                snapshot.messagesJson(payload);
            }
        });
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = memoryKey(memoryId);
        factory.inTransaction(session -> {
            MemorySnapshot snapshot = session.find(MemorySnapshot.class, key);
            if (snapshot != null) {
                session.remove(snapshot);
            }
        });
    }

    private static String memoryKey(Object id) {
        String key = Objects.requireNonNull(Objects.requireNonNull(id, "memoryId").toString(), "memoryId.toString()");
        if (key.isEmpty() || key.length() > 255) {
            throw new IllegalArgumentException("memoryId must map to a nonempty string of at most 255 UTF-16 code units");
        }
        return key;
    }
}
