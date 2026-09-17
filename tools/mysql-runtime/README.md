# Controlled MySQL fixture

A disposable MySQL 8.4.6 server for the Premium backend work
(`docs/design/2026-09-16-premium-mysql-contract.md`, step M1). No service, no admin
rights, no machine-wide configuration, one datadir, one pinned port.

    python tools/mysql-runtime/mysql_fixture.py start            # init if needed, start, provision
    python tools/mysql-runtime/mysql_fixture.py status
    python tools/mysql-runtime/mysql_fixture.py reset-database   # drop and recreate the test schema
    python tools/mysql-runtime/mysql_fixture.py stop             # graceful, then proves it is gone

The gate that uses it does all four steps itself and always stops it:

    python scripts/run_mysql_schema_gate.py                      # -> run/mysql-schema-gate-<stamp>.json

| | |
|---|---|
| Host and port | `127.0.0.1:33316` |
| Database | `itemguard_premium` (`utf8mb4_0900_bin`) |
| User | `itemguard` / `itemguard_test_password` — a throwaway local account, not a secret |
| Server | MySQL Community Server 8.4.6, official `winx64` ZIP archive |
| Binaries, datadir, archive | under this directory, all git-ignored |
| Receipt | `run/mysql-fixture-receipt.json` |

## Rules this fixture is built around

- **The agent owns the stop.** `stop` asks the server to shut down, then verifies the port is
  closed *and* the process is gone, and prints both as evidence. If a graceful shutdown cannot
  be reached (for example the account cannot connect), it terminates **only** the PID whose own
  command line names this datadir, and says so rather than calling it graceful.
- **One heavier server at a time.** Start it for a gate, stop it when the gate is done. Do not
  leave it running to "be ready".
- **Nothing touches production.** This server holds only generated test rows; the datadir can be
  deleted at any time without losing anything that is not reproducible by `start`.

## Notes learned the hard way

- `--skip-name-resolve` makes provisioning impossible: a TCP connection from `127.0.0.1` is not
  the account `root`@`localhost`, so the fixture answers `ERROR 1130` and cannot create its own
  database. Name resolution stays on.
- `innodb_flush_log_at_trx_commit` is a **global** variable in 8.4; `SET SESSION` is rejected.
  That is why the plugin reads and verifies it instead of setting it, and why the refusal path
  is proven with a stubbed connection (`MySqlSchemaSessionGuardTest`) rather than by changing
  this server's global configuration.
