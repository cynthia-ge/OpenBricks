# OpenBricks architecture

OpenBricks uses Java 25, Spring Boot 4.1.2 and SQLite JDBC. Maven builds one executable JAR with an embedded Tomcat server. Each product remains independently buildable and deployable.

`src/main/java/ge/cynthia/openbricks/Core.java` owns local bearer identities, strict JSON/parameter validation, tenant-bound storage and request transactions. `ApiController.java` implements the documented HTTP contracts and product permissions. Credentials are validated and snapshotted at startup; callers cannot supply their own tenant identity.

Each authenticated request opens a separate JDBC connection and an immediate SQLite transaction. Successful responses commit; errors roll back and close the connection. SQL keys include tenant and entity kind. The existing `records` and `counters` schema and persisted JSON shapes are preserved, so existing database files remain readable. Tests exercise persisted rows, restart and rollback with real temporary database files.

This remains a local evaluation architecture: reads also acquire the SQLite write lock, collections may be materialized before filtering, and versioned schema upgrades, asynchronous workers and distributed execution are not implemented. Back up the database before replacing a deployment. There is no browser login or UI in this API foundation.

Docker builds the JAR with Java 25 and runs it as UID 10001 with a writable `/data` volume. Compose binds to loopback. `pom.xml` pins the Spring Boot dependency platform and direct libraries; the Maven distribution has a pinned checksum. `./mvnw clean verify` instruments all production Java without exclusions. Contract fixtures are local behavior regression evidence, not vendor conformance captures.
