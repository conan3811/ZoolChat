#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path

EXPECTED_PACKAGE = "com.zoolchat.app"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_json(path: Path, label: str) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail(f"{label} file not found: {path}")
    except json.JSONDecodeError as exc:
        fail(f"{label} is not valid JSON: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate ZoolChat Firebase client/admin configuration without printing secrets.")
    parser.add_argument("--google-services", required=True, type=Path)
    parser.add_argument("--service-account", required=True, type=Path)
    parser.add_argument("--package", default=EXPECTED_PACKAGE)
    args = parser.parse_args()

    gs = load_json(args.google_services, "google-services.json")
    sa = load_json(args.service_account, "Firebase service account")

    project_info = gs.get("project_info") or {}
    project_id = project_info.get("project_id")
    project_number = project_info.get("project_number")
    if not project_id or not project_number:
        fail("google-services.json is missing project_id or project_number")

    clients = gs.get("client") or []
    android_clients = []
    for client in clients:
        info = (client.get("client_info") or {}).get("android_client_info") or {}
        if info.get("package_name") == args.package:
            android_clients.append(client)
    if not android_clients:
        fail(f"google-services.json does not contain Android package {args.package}")

    app_id = ((android_clients[0].get("client_info") or {}).get("mobilesdk_app_id"))
    if not app_id:
        fail("Android client is missing mobilesdk_app_id")

    service_project_id = sa.get("project_id")
    if service_project_id != project_id:
        fail(f"project mismatch: google-services project={project_id!r}, service account project={service_project_id!r}")

    required_sa = ["client_email", "private_key", "token_uri"]
    missing = [key for key in required_sa if not sa.get(key)]
    if missing:
        fail("service account JSON is missing required fields: " + ", ".join(missing))

    print("Firebase configuration is internally consistent.")
    print(f"package={args.package}")
    print(f"project_id={project_id}")
    print(f"project_number={project_number}")
    print(f"mobilesdk_app_id={app_id}")
    print(f"service_account={sa.get('client_email')}")
    print("No private key or API key was printed.")


if __name__ == "__main__":
    main()
