#!/usr/bin/env python3
"""Verify a private initial admin credential on the loopback formal service, without printing it."""
import http.cookiejar
import json
import os
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

credential = Path(sys.argv[1])
assert credential.stat().st_mode & 0o777 == 0o600, "Credential file must be 0600"
values = dict(line.split("=", 1) for line in credential.read_text().splitlines())
opener = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile="/etc/campus-buddy/tls.crt")),
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
)
csrf = ""

def call(route, method="GET", body=None, expected=200):
    request = urllib.request.Request("https://localhost:8444/api/v1" + route,
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={"Content-Type": "application/json", "X-CSRF-Token": csrf})
    try:
        response = opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, f"Unexpected status for {route}: {response.status}"
    return json.load(response)

csrf = call("/auth/session")["data"]["csrf"]
started = time.monotonic()
call("/auth/login", "POST", {"username": values["USERNAME"], "password": values["TEMPORARY_PASSWORD"]})
print(f"Admin login verified ({int((time.monotonic()-started)*1000)} ms); credentials not printed.")
state = call("/auth/session")["data"]
csrf = state["csrf"]
assert state["demo"] is False and state["user"]["role"] == "ADMIN"
assert state["user"]["passwordChangeRequired"] is True
assert call("/admin/reports", expected=403)["code"] == "PASSWORD_CHANGE_REQUIRED"
if "--change-test-password" in sys.argv:
    # Only enable against the throwaway release-check database. Keep production first-login requirement.
    assert os.environ["DB_URL"].endswith("/campus_buddy_release_check")
    new_password = secrets.token_urlsafe(32)
    call("/auth/password", "POST", {"currentPassword": values["TEMPORARY_PASSWORD"], "newPassword": new_password})
    csrf = call("/auth/session")["data"]["csrf"]
    call("/auth/login", "POST", {"username": values["USERNAME"], "password": new_password})
    state = call("/auth/session")["data"]
    csrf = state["csrf"]
    assert state["user"]["passwordChangeRequired"] is False
    call("/admin/reports")
    print("Test administrator password change and protected admin access verified.")
call("/auth/logout", "POST", {})
print("Initial-password restriction, logout and private file permissions verified.")
