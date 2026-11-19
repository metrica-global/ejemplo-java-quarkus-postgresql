package com.zonacards.dbx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.kernel.LoteId;
import com.zonacards.testkit.PostgresFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbIT {

  private static final String SERVICE = "dbxtest";

  enum Estado {
    NUEVO,
    LISTO
  }

  record Thing(LoteId id, String name, Estado estado, Integer qty, Instant at) {}

  static PostgresFixture postgres;
  static LocalUnitOfWork uow;
  static Db db;

  @BeforeAll
  static void start() {
    postgres = PostgresFixture.shared().bootstrapService(SERVICE, 20);
    postgres.executeAsOwner(
        SERVICE,
        "CREATE TABLE IF NOT EXISTS thing (id uuid PRIMARY KEY, name text NOT NULL, estado text NOT NULL,"
            + " qty int, at timestamptz NOT NULL, CONSTRAINT thing_name_uk UNIQUE (name))");
    uow = new LocalUnitOfWork(postgres.appDataSource(SERVICE));
    db = new Db(uow);
  }

  @BeforeEach
  void clean() {
    uow.runInTx(() -> db.update("DELETE FROM thing"));
  }

  private static Thing map(Row row) throws java.sql.SQLException {
    return new Thing(
        LoteId.of(row.uuid("id").toString()),
        row.string("name"),
        row.enumValue("estado", Estado.class),
        row.integerOrNull("qty"),
        row.instant("at"));
  }

  private static int insert(LoteId id, String name, Integer qty, Instant at) {
    return db.update("INSERT INTO thing (id, name, estado, qty, at) VALUES (?, ?, ?, ?, ?)", id, name, Estado.NUEVO, qty, at);
  }

  @Test
  void roundTripsTypedIdsEnumsNullsAndInstants() {
    LoteId id = LoteId.newId();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    uow.runInTx(() -> assertEquals(1, insert(id, "a", null, at)));

    Optional<Thing> found = uow.inTx(() -> db.queryOne("SELECT * FROM thing WHERE id = ?", DbIT::map, id));
    assertEquals(new Thing(id, "a", Estado.NUEVO, null, at), found.orElseThrow());
    assertTrue(uow.inTx(() -> db.queryOne("SELECT * FROM thing WHERE id = ?", DbIT::map, LoteId.newId())).isEmpty());
    assertEquals(1, uow.inTx(() -> db.queryLong("SELECT count(*) FROM thing")));
  }

  @Test
  void batchAndArrayParameters() {
    Instant at = Instant.now();
    List<Object[]> rows = List.of(new Object[] {UUID.randomUUID(), "a", "NUEVO", 1, at}, new Object[] {UUID.randomUUID(), "b", "NUEVO", 2, at});
    int[] counts = uow.inTx(() -> db.batch("INSERT INTO thing (id, name, estado, qty, at) VALUES (?, ?, ?, ?, ?)", rows));
    assertArrayEquals(new int[] {1, 1}, counts);

    List<String> names =
        uow.inTx(
            () ->
                db.queryList(
                    "SELECT name FROM thing WHERE name = ANY(?) ORDER BY name",
                    row -> row.string("name"),
                    SqlArray.text(List.of("a", "b", "zzz"))));
    assertEquals(List.of("a", "b"), names);

    // Set-based insert: one statement, one round trip, duplicates skipped instead of failing.
    List<Integer> inserted =
        uow.inTx(
            () ->
                db.queryList(
                    "INSERT INTO thing (id, name, estado, qty, at)"
                        + " SELECT gen_random_uuid(), n, 'LISTO', q, now() FROM unnest(?, ?) AS t(n, q)"
                        + " ON CONFLICT (name) DO NOTHING RETURNING qty",
                    row -> row.integer("qty"),
                    SqlArray.text(List.of("b", "c", "d")),
                    SqlArray.integers(List.of(20, 30, 40))));
    assertEquals(List.of(30, 40), inserted.stream().sorted().toList());

    // Binary and uuid arrays, as used to store a fragment of gift cards in one statement.
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    List<String> hex =
        uow.inTx(
            () ->
                db.queryList(
                    "SELECT t.id::text || ':' || encode(t.blob, 'hex') AS v FROM unnest(?::uuid[], ?::bytea[]) AS t(id, blob) ORDER BY 1",
                    row -> row.string("v"),
                    SqlArray.uuid(List.of(first, second)),
                    SqlArray.bytes(List.of(new byte[] {0x01, (byte) 0xff}, new byte[] {0x00}))));
    assertEquals(java.util.stream.Stream.of(first + ":01ff", second + ":00").sorted().toList(), hex);
  }

  @Test
  void aUniqueViolationCarriesItsSqlStateAndConstraint() {
    uow.runInTx(() -> insert(LoteId.newId(), "dup", 1, Instant.now()));
    DbException error = assertThrows(DbException.class, () -> uow.runInTx(() -> insert(LoteId.newId(), "dup", 2, Instant.now())));
    assertTrue(error.isUniqueViolation());
    assertTrue(error.isUniqueViolation("thing_name_uk"));
    assertFalse(error.isUniqueViolation("other_uk"));
    assertFalse(error.isTransient());
  }

  @Test
  void commitsOnReturnAndRollsBackOnException() {
    uow.runInTx(() -> insert(LoteId.newId(), "kept", 1, Instant.now()));
    assertThrows(
        IllegalStateException.class,
        () ->
            uow.runInTx(
                () -> {
                  insert(LoteId.newId(), "lost", 1, Instant.now());
                  throw new IllegalStateException("boom");
                }));
    assertEquals(List.of("kept"), uow.inTx(() -> db.queryList("SELECT name FROM thing", row -> row.string("name"))));
  }

  @Test
  void nestedCallsJoinTheOuterTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            uow.runInTx(
                () -> {
                  uow.runInTx(() -> insert(LoteId.newId(), "inner", 1, Instant.now()));
                  throw new IllegalStateException("outer fails after inner returned");
                }));
    assertEquals(0, uow.inTx(() -> db.queryLong("SELECT count(*) FROM thing")), "inner work must roll back with the outer");
  }

  @Test
  void aSwallowedNestedFailureStillDoomsTheTransaction() {
    DbException error =
        assertThrows(
            DbException.class,
            () ->
                uow.runInTx(
                    () -> {
                      insert(LoteId.newId(), "outer", 1, Instant.now());
                      try {
                        uow.runInTx(
                            () -> {
                              throw new IllegalStateException("inner");
                            });
                      } catch (IllegalStateException swallowed) {
                        // The caller carries on, but the transaction must not commit.
                      }
                    }));
    assertTrue(error.getMessage().contains("rollback-only"));
    assertEquals(0, uow.inTx(() -> db.queryLong("SELECT count(*) FROM thing")));
  }

  @Test
  void jdbcOutsideAUnitOfWorkIsRefused() {
    DbException error = assertThrows(DbException.class, () -> db.queryLong("SELECT 1"));
    assertEquals(DbException.NO_ACTIVE_TRANSACTION, error.sqlState());
  }

  @Test
  void theRuntimeRoleCannotChangeTheSchemaNorSeeAnotherService() {
    DbException ddl = assertThrows(DbException.class, () -> uow.runInTx(() -> db.update("CREATE TABLE sneaky (id int)")));
    assertTrue(ddl.isInsufficientPrivilege(), "sqlstate was " + ddl.sqlState());

    PostgresFixture.shared().bootstrapService("dbxother", 5);
    postgres.executeAsOwner("dbxother", "CREATE TABLE IF NOT EXISTS secret (id int)");
    DbException cross = assertThrows(DbException.class, () -> uow.inTx(() -> db.queryLong("SELECT count(*) FROM dbxother.secret")));
    assertTrue(cross.isInsufficientPrivilege(), "sqlstate was " + cross.sqlState());
  }
}
