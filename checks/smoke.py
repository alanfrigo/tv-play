#!/usr/bin/env python3
"""Check MediaMTX auth and synthetic RTSP media; not browser or TV capture."""

import base64
import ipaddress
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


REQUIRED = ("NAS_IP", "TV_PUBLISH_PASSWORD", "TV_VIEW_PASSWORD")


def command(program, url):
    return [program, "-hide_banner", "-loglevel", "error", "-nostdin"]


def publisher(url):
    return command("ffmpeg", url) + [
        "-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
        "-re", "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
        "-preset", "ultrafast", "-tune", "zerolatency", "-bf", "0",
        "-g", "30", "-b:v", "2M", "-c:a", "libopus", "-ar", "48000",
        "-ac", "2", "-b:a", "96k", "-f", "rtsp", "-rtsp_transport", "tcp",
        "-rw_timeout", "5000000", url,
    ]


def probe(url):
    return subprocess.run(
        ["ffprobe", "-v", "error", "-rtsp_transport", "tcp",
         "-rw_timeout", "5000000", "-show_entries", "stream=codec_name,codec_type",
         "-of", "json", url],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=12,
    )


def decode(url):
    result = subprocess.run(
        command("ffmpeg", url) + [
            "-rtsp_transport", "tcp", "-i", url,
            "-map", "0:v:0", "-map", "0:a:0", "-t", "2",
            "-c:v", "rawvideo", "-c:a", "pcm_s16le", "-f", "framemd5", "-",
        ],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=16,
    )
    assert result.returncode == 0, "viewer could not decode both media streams"
    counts = {"0": 0, "1": 0}
    for line in result.stdout.decode("ascii", errors="replace").splitlines():
        if not line.startswith("#"):
            fields = line.split(",", 1)
            if len(fields) == 2 and fields[0].strip() in counts:
                counts[fields[0].strip()] += 1
    assert all(counts.values()), "viewer decoded no video frames or audio samples"


def stop(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def main():
    missing = [name for name in REQUIRED if not os.environ.get(name)]
    assert not missing, "required environment variables missing: " + ", ".join(missing)
    ip = str(ipaddress.IPv4Address(os.environ["NAS_IP"]))
    publish_password = os.environ["TV_PUBLISH_PASSWORD"]
    view_password = os.environ["TV_VIEW_PASSWORD"]
    assert publish_password != view_password, "publisher and viewer passwords must differ"
    base = f"http://{ip}:8889/tv/"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def http_status(user=None, password=None):
        headers = {}
        if user is not None:
            credentials = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
            headers["Authorization"] = "Basic " + credentials
        try:
            with opener.open(urllib.request.Request(base, headers=headers), timeout=5) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    assert http_status() == 401, "anonymous HTTP viewer was not rejected"
    assert http_status("tvviewer", view_password + "__wrong") == 401, "bad viewer password was accepted"
    assert http_status("tvviewer", view_password) == 200, "viewer cannot open player"
    assert http_status("tvpublisher", publish_password) == 401, "publisher can open player"

    def rtsp(user, password):
        encoded = urllib.parse.quote(password, safe="")
        return f"rtsp://{user}:{encoded}@{ip}:8554/tv"

    viewer_url = rtsp("tvviewer", view_password)
    publisher_url = rtsp("tvpublisher", publish_password)
    empty = probe(viewer_url)
    # ponytail: RTSP 404 proves absence before any synthetic publisher; browser/WebRTC needs real browser check.
    assert empty.returncode != 0 and re.search(rb"\b404\b", empty.stderr), (
        "path is not demonstrably empty; stop existing publisher before smoke"
    )
    denied = subprocess.run(
        publisher(viewer_url), stdin=subprocess.DEVNULL, capture_output=True, timeout=15,
    )
    assert denied.returncode != 0 and re.search(rb"\b401\b", denied.stderr), (
        "viewer publish was not explicitly denied by RTSP authorization"
    )

    source = subprocess.Popen(publisher(publisher_url), stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 35
        while True:
            assert source.poll() is None, "synthetic publisher stopped before media became readable"
            media = probe(viewer_url)
            if media.returncode == 0:
                streams = json.loads(media.stdout)["streams"]
                codecs = {(stream["codec_type"], stream["codec_name"]) for stream in streams}
                assert ("video", "h264") in codecs and ("audio", "opus") in codecs, (
                    "viewer sees wrong codecs; expected H.264 and Opus"
                )
                break
            assert time.monotonic() < deadline, "synthetic publisher never became readable"
            time.sleep(0.5)

        decode(viewer_url)
        conflict = subprocess.run(
            publisher(publisher_url), stdin=subprocess.DEVNULL, capture_output=True, timeout=15,
        )
        assert conflict.returncode != 0, "second publisher was not rejected"
        assert source.poll() is None, "first publisher stopped after conflict"
        decode(viewer_url)
    finally:
        stop(source)
    print("PASS: HTTP auth, empty-path publish auth, H.264/Opus decode, publisher conflict")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(f"FAIL: smoke could not complete ({type(error).__name__})", file=sys.stderr)
        sys.exit(1)
