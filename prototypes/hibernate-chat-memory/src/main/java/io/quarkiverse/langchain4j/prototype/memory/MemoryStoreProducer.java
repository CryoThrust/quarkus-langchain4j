package io.quarkiverse.langchain4j.prototype.memory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.SessionFactory;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.DefaultBean;
import io.quarkus.hibernate.orm.PersistenceUnit;

/** Application fixture for default/named PU selection; not a production extension recorder. */
@ApplicationScoped
public class MemoryStoreProducer {
    @Inject
    @Any
    Instance<SessionFactory> factories;

    @ConfigProperty(name = "prototype.memory.persistence-unit", defaultValue = "<default>")
    String persistenceUnit;

    @Produces
    @Singleton
    @DefaultBean
    ChatMemoryStore memoryStore() {
        SessionFactory factory = "<default>".equals(persistenceUnit)
                ? factories.select(Default.Literal.INSTANCE).get()
                : factories.select(new PersistenceUnit.PersistenceUnitLiteral(persistenceUnit)).get();
        return new HibernateChatMemoryStore(factory);
    }
}
