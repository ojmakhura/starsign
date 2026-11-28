#!/usr/bin/env python3
"""Create or update the Novu workflows StarSign triggers (safe to re-run).

StarSign renders every message itself and sends it in the trigger payload, so each workflow
just forwards it: email -> {{payload.subject}} / {{payload.body}} (HTML), sms -> {{payload.body}}.
Workflow ids are NOVU_WORKFLOW_PREFIX + the event type in kebab case (NovuNotifier.java).

Each event's workflow has an email and an in-app step (the portal's inbox), plus an SMS step
when NOVU_SMS_STEPS=true (there is no SMS integration in Novu yet, so they are left out and
the SMS-only workflow is removed). StarSign triggers the workflow once per channel with
payload.channel = EMAIL / SMS / IN_APP, and each step's condition runs it only for its own
channel, so one trigger sends one message. A trigger without payload.channel (a webservice
older than the in-app inbox) runs the email step (and the SMS step) as before.
The in-app step's data carries StarSign's notification id and portal path, which the portal uses
to mark its copy read and to navigate.

Usage (from docker/compose):
    python3 novu/setup-workflows.py            # reads NOVU_API_KEY and NOVU_API_PORT from .env
    NOVU_URL=http://localhost:23000 NOVU_API_KEY=... python3 novu/setup-workflows.py
    NOVU_SMS_STEPS=true python3 novu/setup-workflows.py   # once Novu has an SMS integration
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
# Every event may go to an internal user's inbox (WorkflowSupport.notifyOwner / notifySignatory),
# except the ones only ever sent to an address
IN_APP = {s for s, _ in EVENTS if s not in ("user-invitation", "verification-code", "provider-test")}
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


def sms_enabled(conf):
    return (os.environ.get("NOVU_SMS_STEPS") or conf.get("NOVU_SMS_STEPS", "")).lower() == "true"


def runs_for(channel, legacy=False):
    """Step condition (JSON Logic, run the step when true) on the trigger's payload.channel."""
    match = {"==": [{"var": "payload.channel"}, channel]}
    return {"or": [match, {"!": {"var": "payload.channel"}}]} if legacy else match


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
            "skip": runs_for("EMAIL", legacy=True),
        },
    }


def sms_step():
    # Needs an SMS integration in Novu (NOVU_SMS_STEPS=true)
    return {
        "name": "SMS",
        "stepId": "sms",
        "type": "sms",
        "controlValues": {"body": "{{payload.body}}", "skip": runs_for("SMS", legacy=True)},
    }


def in_app_step():
    return {
        "name": "In-app",
        "stepId": "in-app",
        "type": "in_app",
        "controlValues": {
            "subject": "{{payload.subject}}",
            "body": "{{payload.body}}",
            # Portal-relative path: the portal routes to it itself
            "redirect": {"url": "{{payload.linkPath}}", "target": "_self"},
            "data": {
                "notificationId": "{{payload.notificationId}}",
                "eventType": "{{payload.eventType}}",
                "linkPath": "{{payload.linkPath}}",
            },
            "skip": runs_for("IN_APP"),
        },
    }


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

    sms = sms_enabled(conf)
    wanted = (
        [workflow(s, n, [email_step()] + ([sms_step()] if sms else []) + ([in_app_step()] if s in IN_APP else []))
         for s, n in EVENTS]
        + [workflow(s, n, [email_step()]) for s, n in EMAIL_ONLY]
        + ([workflow(s, n, [sms_step()]) for s, n in SMS_ONLY] if sms else [])
    )
    # Workflows no longer wanted: the separate in-app ones from before the steps were combined,
    # and the SMS-only one while SMS is off
    obsolete = [PREFIX + s + "-in-app" for s in sorted(IN_APP)] + ([] if sms else [PREFIX + s for s, _ in SMS_ONLY])
    failed = 0
    for wf in wanted:
        wid = wf["workflowId"]
        status, current = call(base, key, "GET", f"/v2/workflows/{wid}")
        if status == 401:
            sys.exit("Novu rejected NOVU_API_KEY (401): use the secret key of the environment you are in")
        if status == 200:
            # An update must repeat the workflow's origin (novu-cloud for workflows made through the API)
            origin = current.get("data", current).get("origin") or "novu-cloud"
            status, res = call(base, key, "PUT", f"/v2/workflows/{wid}", wf | {"origin": origin})
            action = "updated"
        else:
            status, res = call(base, key, "POST", "/v2/workflows", wf)
            action = "created"
        if status in (200, 201):
            print(f"{action:8} {wid}")
        else:
            failed += 1
            print(f"FAILED   {wid}: HTTP {status} {json.dumps(res)[:300]}", file=sys.stderr)
    for wid in obsolete:
        if call(base, key, "GET", f"/v2/workflows/{wid}")[0] == 200:
            status, res = call(base, key, "DELETE", f"/v2/workflows/{wid}")
            if status in (200, 204):
                print(f"deleted  {wid}")
            else:
                failed += 1
                print(f"FAILED   delete {wid}: HTTP {status} {json.dumps(res)[:300]}", file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
