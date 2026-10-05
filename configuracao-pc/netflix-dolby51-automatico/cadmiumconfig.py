"""Configure only Netflix's cadmiumconfig cookie in a closed Edge profile.

Dry run is the default. No cookie decryption is performed and no other cookie
values are selected, backed up, printed, changed, or restored.
"""
from __future__ import annotations

import argparse
import base64
import csv
import datetime as dt
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys


SETTINGS = {
    "enableDDPlus51": "true",
    "enableDDPlusAtmos": "false",
    "audioCapabilityDetectorType": "0",
    "spatialRenderingForDolbyAudio": "false",
    "enableMediaCapabilities": "true",
    "audioProfiles": "heaac-2-dash|heaac-2hq-dash|xheaac-dash|ddplus-5.1-dash|ddplus-5.1hq-dash",
}
HOSTS = ("www.netflix.com", ".netflix.com", "netflix.com")
COOKIE_NAME = "cadmiumconfig"
WEBKIT_EPOCH = dt.datetime(1601, 1, 1, tzinfo=dt.timezone.utc)
KNOWN_COLUMNS = {
    "creation_utc": "INTEGER", "host_key": "TEXT",
    "top_frame_site_key": "TEXT", "name": "TEXT", "value": "TEXT",
    "encrypted_value": "BLOB", "path": "TEXT", "expires_utc": "INTEGER",
    "is_secure": "INTEGER", "is_httponly": "INTEGER",
    "last_access_utc": "INTEGER", "has_expires": "INTEGER",
    "is_persistent": "INTEGER", "priority": "INTEGER", "samesite": "INTEGER",
    "source_scheme": "INTEGER", "source_port": "INTEGER",
    "last_update_utc": "INTEGER", "source_type": "INTEGER",
    "has_cross_site_ancestor": "INTEGER",
}
EDGE_EXTRA_COLUMNS = {"is_edgelegacycookie": "INTEGER", "browser_provenance": "INTEGER"}
IDENTITY_COLUMNS = {
    "host_key", "top_frame_site_key", "has_cross_site_ancestor", "name",
    "path", "source_scheme", "source_port",
}
VOLATILE_COLUMNS = {"last_access_utc", "last_update_utc"}
SELECT_TARGET = "name=? AND host_key IN (?,?,?) AND path=?"
TARGET_PARAMS = (COOKIE_NAME, *HOSTS, "/")


class SafetyError(RuntimeError):
    pass


def identifier(value: str) -> str:
    # Names originate from validated schema, never from cookie contents.
    return '"' + value.replace('"', '""') + '"'


def edge_running() -> bool:
    if os.name != "nt":
        return False
    completed = subprocess.run(
        ["tasklist.exe", "/FI", "IMAGENAME eq msedge.exe", "/FO", "CSV", "/NH"],
        capture_output=True, check=True, creationflags=subprocess.CREATE_NO_WINDOW,
    )
    for row in csv.reader(io.StringIO(completed.stdout.decode("mbcs", errors="replace"))):
        if row and row[0].casefold() == "msedge.exe":
            return True
    return False


def require_edge_closed() -> None:
    if edge_running():
        raise SafetyError("Feche completamente o Edge e o app Netflix antes de continuar.")


def connect(path: Path, writable: bool = False) -> sqlite3.Connection:
    if not path.is_file():
        raise SafetyError("Banco Cookies não encontrado no perfil indicado.")
    if writable:
        # Windows can keep the browser's file handle alive briefly after exit.
        # The caller may retry this operation; no database bytes are changed.
        with path.open("r+b"):
            pass
    uri = path.resolve().as_uri() + ("?mode=rw" if writable else "?mode=ro")
    connection = sqlite3.connect(uri, uri=True, timeout=2.0)
    connection.row_factory = sqlite3.Row
    return connection


def inspect_schema(connection: sqlite3.Connection) -> dict:
    try:
        version = connection.execute("SELECT value FROM meta WHERE key='version'").fetchall()
    except sqlite3.DatabaseError as error:
        raise SafetyError("Metadados Chromium ausentes ou desconhecidos.") from error
    if len(version) != 1 or str(version[0]["value"]) != "24":
        raise SafetyError("Somente o schema Chromium versão 24 é suportado.")
    info = connection.execute("PRAGMA table_info(cookies)").fetchall()
    if not info:
        raise SafetyError("O banco não tem a tabela cookies esperada.")
    columns = [row["name"] for row in info]
    if set(columns) not in (set(KNOWN_COLUMNS), set(KNOWN_COLUMNS) | set(EDGE_EXTRA_COLUMNS)):
        raise SafetyError("Schema de cookies desconhecido; nenhuma alteração foi feita.")
    expected_types = {**KNOWN_COLUMNS, **EDGE_EXTRA_COLUMNS}
    for row in info:
        if row["type"].strip().upper() != expected_types[row["name"]]:
            raise SafetyError("Tipos de colunas diferentes do schema Chromium esperado.")
        if row["name"] in EDGE_EXTRA_COLUMNS and (row["notnull"] != 0 or row["dflt_value"] != "0"):
            raise SafetyError("Metadados de colunas extras Edge diferentes do schema verificado.")
    indexes = connection.execute("PRAGMA index_list(cookies)").fetchall()
    keys = []
    for index in indexes:
        if not index["unique"] or index["partial"]:
            continue
        entries = connection.execute(
            f"PRAGMA index_info({identifier(index['name'])})"
        ).fetchall()
        names = [entry["name"] for entry in entries]
        if (set(names).issubset(IDENTITY_COLUMNS)
                and {"host_key", "name", "path"}.issubset(names)
                and None not in names):
            keys.append(names)
    if len(keys) != 1:
        raise SafetyError("Identidade única dos cookies desconhecida ou ambígua.")
    # A rowid makes mutation unambiguous within the same transaction. Never
    # reuse that rowid for a later restore, after the browser has written data.
    try:
        connection.execute("SELECT rowid FROM cookies LIMIT 0")
    except sqlite3.DatabaseError as error:
        raise SafetyError("Tabela cookies sem rowid não é suportada.") from error
    return {"columns": columns, "types": {r["name"]: r["type"] for r in info},
            "keyColumns": keys[0]}


def target_rows(connection: sqlite3.Connection, schema: dict) -> list[dict]:
    names = ",".join(identifier(name) for name in schema["columns"])
    rows = connection.execute(
        f"SELECT rowid AS __rowid__,{names} FROM cookies WHERE {SELECT_TARGET}",
        TARGET_PARAMS,
    ).fetchall()
    result = [dict(row) for row in rows]
    for row in result:
        if row["encrypted_value"] not in (b"", None):
            raise SafetyError("Existe cadmiumconfig criptografado; abortado sem descriptografar.")
        if row.get("top_frame_site_key", ""):
            raise SafetyError("cadmiumconfig particionado não é suportado nesta operação.")
        merge_settings(row["value"])
    return result


def merge_settings(raw: str) -> str:
    if not isinstance(raw, str) or not raw.isascii():
        raise SafetyError("cadmiumconfig precisa ser CSV ASCII em texto simples.")
    if any(character in raw for character in "\r\n\x00"):
        raise SafetyError("cadmiumconfig contém caracteres inválidos.")
    configured = {key.casefold() for key in SETTINGS}
    preserved = []
    if raw:
        for entry in raw.split(","):
            key, separator, value = entry.partition("=")
            if not separator or not key.strip():
                raise SafetyError("Formato de cadmiumconfig desconhecido; nenhuma alteração feita.")
            if key.strip().casefold() not in configured:
                preserved.append(entry)
    # Deliberately raw CSV: no percent encoding, URI escaping, or JSON encoding.
    return ",".join([*preserved, *(f"{key}={value}" for key, value in SETTINGS.items())])


def webkit_time(now: dt.datetime) -> int:
    delta = now.astimezone(dt.timezone.utc) - WEBKIT_EPOCH
    return (delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds


def make_plan(schema: dict, before: list[dict], now: dt.datetime | None = None) -> dict:
    now = now or dt.datetime.now(dt.timezone.utc)
    stamp = webkit_time(now)
    expiry = webkit_time(now + dt.timedelta(days=365))
    after = []
    for previous in before:
        row = {key: value for key, value in previous.items() if key != "__rowid__"}
        row.update({"value": merge_settings(row["value"]), "expires_utc": expiry,
                    "is_secure": 1, "is_httponly": 0, "has_expires": 1,
                    "is_persistent": 1, "samesite": 1, "last_access_utc": stamp})
        if "last_update_utc" in row:
            row["last_update_utc"] = stamp
        # Preserve existing identity/domain/source fields; changing identity can
        # collide with another cadmiumconfig row in Chromium's unique index.
        after.append(row)
    inserted = not before
    if inserted:
        row = {name: ("" if kind == "TEXT" else b"" if kind == "BLOB" else 0)
               for name, kind in {**KNOWN_COLUMNS, **EDGE_EXTRA_COLUMNS}.items()
               if name in schema["columns"]}
        values = {
            "creation_utc": stamp, "host_key": "www.netflix.com", "name": COOKIE_NAME,
            "value": merge_settings(""), "encrypted_value": b"", "path": "/",
            "expires_utc": expiry, "is_secure": 1, "is_httponly": 0,
            "last_access_utc": stamp, "has_expires": 1, "is_persistent": 1,
            "priority": 1, "samesite": 1, "source_scheme": 2, "source_port": 443,
            "last_update_utc": stamp, "source_type": 2,
            "top_frame_site_key": "", "has_cross_site_ancestor": 1,
        }
        row.update({key: value for key, value in values.items() if key in row})
        after.append(row)
    return {"before": [{k: v for k, v in row.items() if k != "__rowid__"}
                       for row in before], "after": after, "inserted": inserted}


def encode_rows(rows: list[dict]) -> list[dict]:
    return [{key: {"bytes": base64.b64encode(value).decode("ascii")}
             if isinstance(value, bytes) else value for key, value in row.items()}
            for row in rows]


def decode_rows(rows: list[dict]) -> list[dict]:
    result = []
    for row in rows:
        decoded = {}
        for key, value in row.items():
            if isinstance(value, dict):
                if set(value) != {"bytes"}:
                    raise SafetyError("Backup inválido.")
                value = base64.b64decode(value["bytes"], validate=True)
            decoded[key] = value
        result.append(decoded)
    return result


def write_backup(path: Path, database: Path, schema: dict, plan: dict) -> None:
    if path.exists():
        raise SafetyError("O arquivo de backup já existe; ele não será sobrescrito.")
    document = {
        "formatVersion": 1, "createdUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "database": str(database.resolve()), "cookie": COOKIE_NAME,
        "schema": schema, "inserted": plan["inserted"],
        "before": encode_rows(plan["before"]), "after": encode_rows(plan["after"]),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create is intentional: a pre-existing backup is never replaced.
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())


def apply(database: Path, backup: Path, *, check_browser: bool = True) -> dict:
    if check_browser:
        require_edge_closed()
    connection = connect(database, writable=True)
    try:
        connection.execute("BEGIN IMMEDIATE")
        schema = inspect_schema(connection)
        before = target_rows(connection, schema)
        plan = make_plan(schema, before)
        # Backup contains only plaintext cadmiumconfig rows and precedes writes.
        write_backup(backup, database, schema, plan)
        names = schema["columns"]
        if plan["inserted"]:
            connection.execute(
                f"INSERT INTO cookies ({','.join(map(identifier, names))}) "
                f"VALUES ({','.join('?' for _ in names)})",
                [plan["after"][0][name] for name in names],
            )
        else:
            assignments = ",".join(f"{identifier(name)}=?" for name in names)
            for old, updated in zip(before, plan["after"]):
                cursor = connection.execute(
                    f"UPDATE cookies SET {assignments} WHERE rowid=? AND {SELECT_TARGET}",
                    [*(updated[name] for name in names), old["__rowid__"], *TARGET_PARAMS],
                )
                if cursor.rowcount != 1:
                    raise SafetyError("A linha alvo mudou; transação cancelada.")
        if check_browser:
            require_edge_closed()
        connection.commit()
        return {"mode": "applied", "cookie": COOKIE_NAME,
                "rowsUpdated": len(before), "rowsInserted": int(plan["inserted"]),
                "backup": str(backup.resolve())}
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


def validate_backup(document: dict, database: Path, schema: dict) -> dict:
    if (document.get("formatVersion") != 1 or document.get("cookie") != COOKIE_NAME
            or Path(document.get("database", "")).resolve() != database.resolve()
            or document.get("schema") != schema
            or not isinstance(document.get("inserted"), bool)):
        raise SafetyError("O backup não corresponde ao banco/schema indicado.")
    before = decode_rows(document.get("before", []))
    after = decode_rows(document.get("after", []))
    if not after or (document["inserted"] and (before or len(after) != 1)):
        raise SafetyError("Plano do backup inválido.")
    if not document["inserted"] and len(before) != len(after):
        raise SafetyError("Plano de restauração incompleto.")
    for row in [*before, *after]:
        if (set(row) != set(schema["columns"]) or row["name"] != COOKIE_NAME
                or row["host_key"] not in HOSTS or row["path"] != "/"
                or row["encrypted_value"] not in (b"", None)
                or row.get("top_frame_site_key", "")):
            raise SafetyError("Backup contém dados fora do escopo cadmiumconfig.")
        merge_settings(row["value"])
    return {"before": before, "after": after, "inserted": document["inserted"]}


def same_configuration(current: dict, expected: dict, columns: list[str]) -> bool:
    return all(current[key] == expected[key] for key in columns if key not in VOLATILE_COLUMNS)


def restore(database: Path, backup: Path, *, check_browser: bool = True) -> dict:
    if check_browser:
        require_edge_closed()
    with backup.open(encoding="utf-8") as handle:
        document = json.load(handle)
    connection = connect(database, writable=True)
    try:
        connection.execute("BEGIN IMMEDIATE")
        schema = inspect_schema(connection)
        plan = validate_backup(document, database, schema)
        key_names = schema["keyColumns"]
        where = " AND ".join(f"{identifier(key)}=?" for key in key_names)
        names = schema["columns"]
        assignments = ",".join(f"{identifier(name)}=?" for name in names)
        for index, expected in enumerate(plan["after"]):
            key_values = [expected[key] for key in key_names]
            found = connection.execute(
                f"SELECT {','.join(map(identifier, names))} FROM cookies "
                f"WHERE {where} AND {SELECT_TARGET}",
                [*key_values, *TARGET_PARAMS],
            ).fetchall()
            if len(found) != 1 or not same_configuration(dict(found[0]), expected, names):
                raise SafetyError("cadmiumconfig mudou desde o ajuste; restauração cancelada.")
            if plan["inserted"]:
                cursor = connection.execute(
                    f"DELETE FROM cookies WHERE {where} AND {SELECT_TARGET}",
                    [*key_values, *TARGET_PARAMS],
                )
            else:
                original = plan["before"][index]
                cursor = connection.execute(
                    f"UPDATE cookies SET {assignments} WHERE {where} AND {SELECT_TARGET}",
                    [*(original[name] for name in names), *key_values, *TARGET_PARAMS],
                )
            if cursor.rowcount != 1:
                raise SafetyError("Identidade do cookie mudou; restauração cancelada.")
        if check_browser:
            require_edge_closed()
        connection.commit()
        return {"mode": "restored", "cookie": COOKIE_NAME,
                "rowsRestored": len(plan["before"]),
                "rowsRemoved": int(plan["inserted"])}
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


def dry_run(database: Path) -> dict:
    require_edge_closed()
    connection = connect(database)
    try:
        schema = inspect_schema(connection)
        before = target_rows(connection, schema)
        plan = make_plan(schema, before)
        return {"mode": "dry-run", "cookie": COOKIE_NAME,
                "rowsToUpdate": len(before), "rowsToInsert": int(plan["inserted"]),
                "settings": SETTINGS, "backupContainsOnly": COOKIE_NAME,
                "changed": False}
    finally:
        connection.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cookie-db", type=Path, default=
                        Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
                        / "Microsoft/Edge/User Data/Default/Network/Cookies")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--apply", action="store_true")
    modes.add_argument("--restore", type=Path, metavar="BACKUP_JSON")
    parser.add_argument("--backup", type=Path, help="Novo arquivo JSON, somente com --apply.")
    args = parser.parse_args(argv)
    if args.backup and not args.apply:
        parser.error("--backup exige --apply")
    try:
        if args.apply:
            backup = args.backup or Path(__file__).with_name(
                "backup-cadmiumconfig-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json")
            result = apply(args.cookie_db, backup)
        elif args.restore:
            result = restore(args.cookie_db, args.restore)
        else:
            result = dry_run(args.cookie_db)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (SafetyError, sqlite3.Error, OSError, ValueError, KeyError, TypeError,
            subprocess.SubprocessError) as error:
        # Do not print SQLite error messages, row values, or arbitrary file data.
        message = str(error) if isinstance(error, SafetyError) else type(error).__name__
        print(json.dumps({"mode": "error", "changed": False, "error": message}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
