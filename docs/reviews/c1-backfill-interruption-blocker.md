# Backfill implementation and interruption blocker

CatalogIndexSchema.backfill now creates mapping/FTS/triggers and fills existing records within caller transaction, with SQLite progress handler and 30s wall check. Not wired to startup or reads. Single-record backfill test passed.

Fresh full run docs/reviews/c1-backfill-green.log failed: 387 tests, one failure in SqliteProcessLockCrossProcessTest (READY file existed but content empty). No successful package claim. This needs test protocol diagnosis, not blind retry.

Added 1000-record interruption case and ran CatalogBackfillTest,CatalogIndexSchemaTest. docs/reviews/c1-backfill-focused.log: 6 tests, one error. SQLITE_INTERRUPT occurred as requested; following owner operation failed commit: cannot commit - no transaction is active; rollback likewise failed. This is a real connection recovery blocker. Do not activate backfill/migration or assume owner reusable after interruption. Investigate native auto-rollback versus JDBC transaction bookkeeping; do not suppress error or blindly BEGIN if transaction state unknown.

Latest edits not full-green. Prior386 green predates backfill. No server or protected checkpoint writes. Next work: dedicated owner interruption regression and safe recovery/close contract, plus READY-file publication race. Backfill readiness and production routing remain absent.
