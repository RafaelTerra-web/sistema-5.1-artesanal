"""Isolated SQLite fixtures only. Does not open a real browser profile."""
import datetime as dt
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import cadmiumconfig as subject


class CookieBackendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "Cookies"
        self.backup = Path(self.temp.name) / "backup.json"
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("CREATE TABLE meta (key TEXT NOT NULL UNIQUE, value TEXT)")
            connection.execute("INSERT INTO meta (key,value) VALUES ('version','24')")
            fields = ",".join(f'"{name}" {kind} NOT NULL'
                              for name, kind in subject.KNOWN_COLUMNS.items())
            connection.execute(f"CREATE TABLE cookies ({fields})")
            connection.execute("CREATE UNIQUE INDEX cookies_unique_index ON cookies "
                               "(host_key,top_frame_site_key,has_cross_site_ancestor,"
                               "name,path,source_scheme,source_port)")

    def schema(self):
        with closing(subject.connect(self.database)) as connection:
            return subject.inspect_schema(connection)

    def insert_config(self, value, host="www.netflix.com", encrypted=b"", path="/"):
        row = subject.make_plan(self.schema(), [], dt.datetime(2026, 10, 3, tzinfo=dt.timezone.utc))["after"][0]
        row.update(value=value, host_key=host, encrypted_value=encrypted, path=path)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            names = list(row)
            connection.execute("INSERT INTO cookies (" + ",".join(names) + ") VALUES ("
                               + ",".join("?" for _ in names) + ")", list(row.values()))

    def read_configs(self):
        with closing(subject.connect(self.database)) as connection:
            return subject.target_rows(connection, subject.inspect_schema(connection))

    def test_raw_csv_preserves_extras_and_replaces_case_variants(self):
        merged = subject.merge_settings("otherFlag=abc|123,ENABLEDDPLUS51=false,custom=value=stillvalue,enableDDPlus51=false")
        self.assertTrue(merged.startswith("otherFlag=abc|123,custom=value=stillvalue,"))
        self.assertEqual(merged.count("enableDDPlus51="), 1)
        self.assertNotIn("%", merged)
        for key, value in subject.SETTINGS.items():
            self.assertIn(f"{key}={value}", merged.split(","))

    def test_create_and_restore_only_target_cookie(self):
        # Fixture deliberately contains no authentication cookie or account data.
        before = self.database.read_bytes()
        result = subject.apply(self.database, self.backup, check_browser=False)
        self.assertEqual(result["rowsInserted"], 1)
        row = self.read_configs()[0]
        self.assertEqual(row["host_key"], "www.netflix.com")
        self.assertEqual(row["has_cross_site_ancestor"], 1)
        self.assertEqual(row["samesite"], 1)
        self.assertEqual(row["source_type"], 2)
        self.assertEqual(row["encrypted_value"], b"")
        self.assertEqual(row["expires_utc"] - row["creation_utc"], 365 * 86400 * 1_000_000)
        backup = json.loads(self.backup.read_text(encoding="utf-8"))
        self.assertEqual(backup["before"], [])
        self.assertEqual([r["name"] for r in backup["after"]], ["cadmiumconfig"])
        restored = subject.restore(self.database, self.backup, check_browser=False)
        self.assertEqual(restored["rowsRemoved"], 1)
        self.assertEqual(self.read_configs(), [])

    def test_merge_three_host_variants_without_duplicate_rows(self):
        for host in subject.HOSTS:
            self.insert_config("extra=" + host + ",enableDDPlus51=false", host)
        original = [{k: v for k, v in r.items() if k != "__rowid__"} for r in self.read_configs()]
        result = subject.apply(self.database, self.backup, check_browser=False)
        self.assertEqual(result["rowsInserted"], 0)
        self.assertEqual(result["rowsUpdated"], 3)
        for row in self.read_configs():
            self.assertTrue(row["value"].startswith("extra=" + row["host_key"] + ","))
        subject.restore(self.database, self.backup, check_browser=False)
        restored = [{k: v for k, v in r.items() if k != "__rowid__"} for r in self.read_configs()]
        self.assertEqual(restored, original)

    def test_encrypted_config_aborts_before_backup(self):
        self.insert_config("", encrypted=b"v20-fixture-not-a-secret")
        snapshot = self.database.read_bytes()
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())
        self.assertEqual(self.database.read_bytes(), snapshot)

    def test_unknown_schema_aborts_before_backup(self):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("ALTER TABLE cookies ADD COLUMN unknown_future_field TEXT")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_missing_chromium_metadata_aborts_before_backup(self):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("DROP TABLE meta")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_other_chromium_version_aborts_before_backup(self):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("UPDATE meta SET value='23' WHERE key='version'")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_missing_known_column_aborts_before_backup(self):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("ALTER TABLE cookies DROP COLUMN source_type")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def add_edge_columns(self, kind="INTEGER", default="0"):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            for name in subject.EDGE_EXTRA_COLUMNS:
                suffix = "" if default is None else " DEFAULT " + default
                connection.execute(f"ALTER TABLE cookies ADD COLUMN {name} {kind}{suffix}")

    def test_edge_22_columns_create_and_restore(self):
        self.add_edge_columns()
        subject.apply(self.database, self.backup, check_browser=False)
        row = self.read_configs()[0]
        self.assertEqual(row["is_edgelegacycookie"], 0)
        self.assertEqual(row["browser_provenance"], 0)
        subject.restore(self.database, self.backup, check_browser=False)
        self.assertEqual(self.read_configs(), [])

    def test_edge_extra_columns_wrong_type_aborts(self):
        self.add_edge_columns(kind="TEXT")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_edge_extra_columns_wrong_default_aborts(self):
        self.add_edge_columns(default="1")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_edge_extra_columns_missing_default_aborts(self):
        self.add_edge_columns(default=None)
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_partitioned_config_aborts(self):
        self.insert_config("extra=fixture")
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("UPDATE cookies SET top_frame_site_key='https://example.invalid'")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertFalse(self.backup.exists())

    def test_restore_detects_user_change_and_does_not_overwrite(self):
        subject.apply(self.database, self.backup, check_browser=False)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("UPDATE cookies SET value='otherSetting=changed'")
        with self.assertRaises(subject.SafetyError):
            subject.restore(self.database, self.backup, check_browser=False)
        self.assertEqual(self.read_configs()[0]["value"], "otherSetting=changed")

    def test_access_timestamp_change_does_not_block_restore(self):
        subject.apply(self.database, self.backup, check_browser=False)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("UPDATE cookies SET last_access_utc=last_access_utc+12345")
        subject.restore(self.database, self.backup, check_browser=False)
        self.assertEqual(self.read_configs(), [])

    def test_backup_not_overwritten(self):
        self.backup.write_text("fixture-existing-backup", encoding="utf-8")
        with self.assertRaises(subject.SafetyError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertEqual(self.read_configs(), [])
        self.assertEqual(self.backup.read_text(encoding="utf-8"), "fixture-existing-backup")

    def test_rollback_after_write_error(self):
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("CREATE TRIGGER test_block BEFORE INSERT ON cookies "
                               "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            subject.apply(self.database, self.backup, check_browser=False)
        self.assertEqual(self.read_configs(), [])
        self.assertTrue(self.backup.exists())

    def test_browser_open_blocks_all_modes(self):
        with patch.object(subject, "edge_running", return_value=True):
            for action in [lambda: subject.dry_run(self.database),
                           lambda: subject.apply(self.database, self.backup),
                           lambda: subject.restore(self.database, self.backup)]:
                with self.assertRaises(subject.SafetyError):
                    action()
        self.assertFalse(self.backup.exists())

    def test_default_cli_is_dry_run_and_does_not_write(self):
        snapshot = self.database.read_bytes()
        with patch.object(subject, "edge_running", return_value=False), patch("sys.stdout", new=__import__("io").StringIO()) as output:
            self.assertEqual(subject.main(["--cookie-db", str(self.database)]), 0)
            result = json.loads(output.getvalue())
        self.assertEqual(result["mode"], "dry-run")
        self.assertFalse(result["changed"])
        self.assertEqual(self.database.read_bytes(), snapshot)
        self.assertFalse(self.backup.exists())

    def test_scope_is_name_three_hosts_and_root_path(self):
        traced = []
        with closing(subject.connect(self.database)) as connection:
            schema = subject.inspect_schema(connection)
            connection.set_trace_callback(traced.append)
            subject.target_rows(connection, schema)
        queries = [sql for sql in traced if sql.startswith("SELECT")]
        self.assertEqual(len(queries), 1)
        self.assertIn("name='cadmiumconfig'", queries[0])
        self.assertIn("path='/'", queries[0])
        for host in subject.HOSTS:
            self.assertIn("'" + host + "'", queries[0])


if __name__ == "__main__":
    unittest.main()
