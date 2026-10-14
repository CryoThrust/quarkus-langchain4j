package io.quarkiverse.langchain4j.prototype.memory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One ordered message snapshot; ORM manages schema and optimistic transaction conflicts. */
@Entity(name = "PrototypeMemorySnapshot")
@Table(name = "prototype_chat_memory")
public class MemorySnapshot {
    @Id
    @Column(name = "memory_id", length = 255, nullable = false)
    private String memoryId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "messages_json", nullable = false)
    private String messagesJson;

    @Version
    private long version;

    protected MemorySnapshot() {
    }

    public MemorySnapshot(String memoryId, String messagesJson) {
        this.memoryId = memoryId;
        this.messagesJson = messagesJson;
    }

    public String messagesJson() {
        return messagesJson;
    }

    public void messagesJson(String messagesJson) {
        this.messagesJson = messagesJson;
    }
}
