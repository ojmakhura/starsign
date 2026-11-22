#!/usr/bin/env python3
"""Create or update the Novu workflows StarSign triggers (safe to re-run).

StarSign renders every message itself and sends it in the trigger payload, so each workflow
just forwards it: email -> {{payload.subject}} / {{payload.body}} (HTML), sms -> {{payload.body}}.
Workflow ids are NOVU_WORKFLOW_PREFIX + the event type in kebab case (NovuNotifier.java).

Usage (from docker/compose):
    python3 novu/setup-workflows.py            # reads NOVU_API_KEY and NOVU_API_PORT from .env
    NOVU_URL=http://localhost:23000 NOVU_API_KEY=... python3 novu/setup-workflows.py
"""
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

PREFIX = os.environ.get("NOVU_WORKFLOW_PREFIX", "starsign-")

# NotificationEventType (common/.../notification/NotificationEventType.java) plus the gateway ids
EVENTS = [
    ("signing-invitation", "Signing invitation"),
    ("signing-reminder", "Signing reminder"),
    ("signature-completed", "Signature completed"),
    ("next-signatory-activated", "Next signatory activated"),
    ("document-declined", "Document declined"),
    ("workflow-completed", "Workflow completed"),
    ("workflow-cancelled", "Workflow cancelled"),
    ("workflow-expiring", "Workflow expiring"),
    ("workflow-expired", "Workflow expired"),
    ("user-invitation", "User invitation"),
    ("verification-code", "Verification code"),
    ("escalation", "Escalation"),
    ("completed-copy", "Completed copy"),
    ("organisation-status", "Organisation status"),
    ("provider-test", "Provider test"),
]
EMAIL_ONLY = [("email", "Email (no workflow hint)")]
SMS_ONLY = [("sms", "SMS (no workflow hint)")]


def env_file():
    values = {}
    path = Path(__file__).resolve().parent.parent / ".env"
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"')
    return values


def email_step():
    return {
        "name": "Email",
        "stepId": "email",
        "type": "email",
        "controlValues": {
            "subject": "{{payload.subject}}",
            "body": "{{payload.body}}",
            "editorType": "html",
            # The body is StarSign's own rendered HTML: keep it as sent
            "disableOutputSanitization": True,
        },
    }


def sms_step():
    # Without an SMS integration in Novu this step is marked failed for every trigger; the
    # email step is unaffected. (Step "skip" conditions set via the API were ignored by Novu 3.20.)
    return {"name": "SMS", "stepId": "sms", "type": "sms", "controlValues": {"body": "{{payload.body}}"}}


def workflow(slug, name, steps):
    return {
        "workflowId": PREFIX + slug,
        "name": f"StarSign - {name}",
        "description": "Managed by docker/compose/novu/setup-workflows.py; content comes from StarSign's payload",
        "tags": ["starsign"],
        "active": True,
        "validatePayload": False,
        "steps": steps,
    }


def call(base, key, method, path, body=None):
    req = urllib.request.Request(
        base + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"ApiKey {key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    conf = env_file()
    key = os.environ.get("NOVU_API_KEY") or conf.get("NOVU_API_KEY")
    base = os.environ.get("NOVU_URL") or f"http://localhost:{conf.get('NOVU_API_PORT', '23000')}"
    if not key:
        sys.exit("NOVU_API_KEY is empty: copy the secret key from the Novu dashboard (API Keys) into .env")

    wanted = (
        [workflow(s, n, [email_step(), sms_step()]) for s, n in EVENTS]
        + [workflow(s, n, [email_step()]) for s, n in EMAIL_ONLY]
        + [workflow(s, n, [sms_step()]) for s, n in SMS_ONLY]
    )
    failed = 0
    for wf in wanted:
        wid = wf["workflowId"]
        status, _ = call(base, key, "GET", f"/v2/workflows/{wid}")
        if status == 401:
            sys.exit("Novu rejected NOVU_API_KEY (401): use the secret key of the environment you are in")
        if status == 200:
            status, res = call(base, key, "PUT", f"/v2/workflows/{wid}", {k: v for k, v in wf.items() if k != "workflowId"} | {"workflowId": wid})
            action = "updated"
        else:
            status, res = call(base, key, "POST", "/v2/workflows", wf)
            action = "created"
        if status in (200, 201):
            print(f"{action:8} {wid}")
        else:
            failed += 1
            print(f"FAILED   {wid}: HTTP {status} {json.dumps(res)[:300]}", file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
