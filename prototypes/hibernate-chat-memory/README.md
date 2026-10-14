# Hibernate chat-memory experiment

Executable storage and CDI fixture for [#2971](https://github.com/quarkiverse/quarkus-langchain4j/issues/2971).
This standalone project is outside the repository's production module graph. It is not a released extension.

## Run

With JDK 17 and Maven 3.9+, from the repository root:

```sh
mvn -B -ntp -f prototypes/hibernate-chat-memory/pom.xml clean verify
```

No model credentials, Docker or external database are needed. Versions match the inspected main branch:
Quarkus 3.40.0, Hibernate ORM 7.4.9.Final (BOM-managed), LangChain4j Core 1.21.0, H2 2.4.240.
Byte Buddy is test-scoped for plain Hibernate bootstrap; Quarkus supplies its own build-time integration.

## Contract exercised

`HibernateChatMemoryStore` uses a caller-owned `SessionFactory` and a short transaction for each CRUD
operation. It never closes the factory. Updates replace an entire ordered message snapshot; deletion
really removes it. Reads return an empty list for an unknown ID and an immutable list otherwise.

The entity has a string primary key, a dialect-resolved `LONG32VARCHAR` JSON payload and an optimistic
version. The prototype converts IDs with `toString()`, matching the existing Redis/MongoDB convention.
This is not a type-injective mapping: integer `1` and string `"1"` intentionally map to the same key.
Null, empty and over-255-UTF-16-unit keys are rejected before database mutation.

The application CDI producer selects the default or a named persistence unit through
`prototype.memory.persistence-unit`. The test application explicitly assigns the entity package to
both units and uses separate H2 databases. A production extension would instead need build-time entity
registration, configuration and a recorder/synthetic bean following the existing extensions.

Fourteen tests cover:

- Ordered system/user/AI/tool-call/tool-result round trips, Unicode, thinking, multimodal tool results
  and the tool-result error flag, using the actual LangChain4j message codec.
- Snapshot replacement, conversation isolation, missing/empty reads, idempotent explicit deletion,
  UUID/string ID interoperability and a large Unicode payload.
- Recovery from a file database after closing and rebuilding the Hibernate factory; explicit deletion
  remains effective after another rebuild. Schema creation happens only in the first factory; subsequent
  factories validate the existing schema.
- Codec failure and real database-constraint failure preserving the previously committed snapshot.
- Distinct-ID concurrent writes and barrier-controlled same-row overlapping transactions. The latter
  must reject one stale writer; removing `@Version` makes that test fail because both writes succeed.
- Real Quarkus boot/CDI/ORM transactions for the default and named persistence units, including proving
  that selecting the named unit does not write into the default database.

## Decisions before an extension PR

Persistence is separate from conversation lifecycle. The factory-recreation test is **not** an
AI-service shutdown/restart test. Existing `QuarkusAiServiceContext.close()` can clear memory; that policy
is already being addressed in [#2452](https://github.com/quarkiverse/quarkus-langchain4j/pull/2452).
Disabling `deleteMessages()` to avoid shutdown deletion would break explicit conversation removal.
This store does not recover chat scopes or complete agentic context for #2832/#2833.

The default codec here is LangChain4j's serializer/deserializer, behind an injectable `MessageCodec`
boundary. A real extension should use the project's Quarkus JSON codec and verify its complete message
round trips, rather than assume both codecs behave identically. Native-image serialization is untested.

Optimistic locking protects overlapping database transactions on an existing snapshot. It does not
serialize whole agent turns or detect a stale message list computed before the transaction began.
Concurrent first inserts may conflict on the primary key; no retry or merge policy is implemented.
Callers must serialize turns for one conversation if they need that stronger guarantee.

The fixture is blocking. Invocation from event-loop threads, ambient JTA transaction propagation,
production schema migrations and PostgreSQL/MySQL/Oracle/native-image compatibility still need an
extension-level contract and tests. `drop-and-create` in application.properties is disposable fixture
configuration, not a recommended production schema policy.

The useful first scope would be a dedicated Hibernate memory-store extension selecting one persistence
unit, with lifecycle changes remaining in #2452. Whether to reuse the vector extension's entity-mapping
infrastructure or introduce a separate mapping should be decided with maintainers.
