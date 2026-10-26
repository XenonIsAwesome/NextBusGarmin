#!/usr/bin/env python3
"""Gzip an index.db and write the manifest.json the Android app's IndexUpdater reads.

    make_manifest.py index.db OUT_DIR BASE_URL

Writes OUT_DIR/index.db.gz and OUT_DIR/manifest.json; the manifest's url is BASE_URL/index.db.gz.
Fields match app.nextbus.core.IndexManifest (builtAt, schemaVersion, sha256 of the .gz, size).
"""
import gzip, hashlib, json, os, shutil, sqlite3, sys


def main(db, out_dir, base_url):
    meta = dict(sqlite3.connect(db).execute("SELECT key, value FROM meta"))
    os.makedirs(out_dir, exist_ok=True)
    gz_path = os.path.join(out_dir, "index.db.gz")
    with open(db, "rb") as src, gzip.GzipFile(gz_path, "wb", mtime=0) as dst:
        shutil.copyfileobj(src, dst)
    data = open(gz_path, "rb").read()
    manifest = {
        "builtAt": meta["built_at"],
        "schemaVersion": int(meta["schema_version"]),
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
        "url": base_url.rstrip("/") + "/index.db.gz",
    }
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
