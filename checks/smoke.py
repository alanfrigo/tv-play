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


def publisher(url, size="1280x720", bitrate="2M"):
    return command("ffmpeg", url) + [
        "-re", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=30",
        "-re", "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
        "-preset", "ultrafast", "-tune", "zerolatency", "-bf", "0",
        "-g", "30", "-b:v", bitrate, "-minrate", bitrate,
        "-maxrate", bitrate, "-bufsize", bitrate, "-x264-params", "nal-hrd=cbr",
        "-c:a", "libopus", "-ar", "48000", "-ac", "2", "-b:a", "96k",
        "-f", "rtsp", "-rtsp_transport", "tcp", "-rw_timeout", "5000000", url,
    ]


def probe(url):
    return subprocess.run(
        ["ffprobe", "-v", "error", "-rtsp_transport", "tcp",
         "-rw_timeout", "5000000", "-show_entries", "stream=codec_name,codec_type,width,height",
         "-of", "json", url],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=12,
    )


def decode(url, size, seconds, rtsp=True):
    input_options = ["-rtsp_transport", "tcp"] if rtsp else []
    result = subprocess.run(
        command("ffmpeg", url) + input_options + [
            "-i", url, "-map", "0:v:0", "-map", "0:a:0", "-t", str(seconds),
            "-fps_mode", "passthrough", "-c:v", "rawvideo", "-c:a", "pcm_s16le",
            "-f", "framemd5", "-",
        ],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=seconds + 18,
    )
    assert result.returncode == 0, (
        f"viewer could not decode {size} {'RTSP' if rtsp else 'HLS'}: "
        + result.stderr.decode(errors="replace").replace(url, "[stream URL]")[-500:]
    )
    lines = result.stdout.decode("ascii", errors="replace").splitlines()
    assert f"#dimensions 0: {size}" in lines, "viewer decoded wrong video dimensions"
    timebase = next((line.split(": ", 1)[1] for line in lines if line.startswith("#tb 0: ")), None)
    assert timebase, "viewer returned no video timebase"
    numerator, denominator = map(int, timebase.split("/"))
    frames = []
    audio = 0
    for line in lines:
        if line.startswith("#"):
            continue
        fields = [field.strip() for field in line.split(",")]
        if len(fields) < 6:
            continue
        if fields[0] == "0":
            frames.append((int(fields[1]) * numerator / denominator, fields[5]))
        elif fields[0] == "1":
            audio += 1
    assert audio and len(frames) >= 30 * (seconds - 1), "viewer lost video frames or audio"
    timestamps = [frame[0] for frame in frames]
    gaps = [after - before for before, after in zip(timestamps, timestamps[1:])]
    assert all(0.02 <= gap <= 0.1 for gap in gaps), (
        f"{size} {'RTSP' if rtsp else 'HLS'} video gap out of range: "
        f"min={min(gaps):.3f}s max={max(gaps):.3f}s"
    )
    assert timestamps[-1] - timestamps[0] >= seconds - 1, "viewer video stopped early"
    assert len({frame[1] for frame in frames}) >= len(frames) * 0.9, "viewer video froze"


def video_bitrate(url):
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-rw_timeout", "5000000",
         "-rtsp_transport", "tcp", "-read_intervals", "%+5",
         "-select_streams", "v:0", "-show_packets",
         "-show_entries", "packet=size", "-of", "json", url],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=22,
    )
    assert result.returncode == 0, "viewer could not inspect received video packets"
    packets = json.loads(result.stdout)["packets"]
    assert len(packets) >= 120, "viewer received too few compressed frames"
    # One H.264 access unit per demuxed packet; 30 fps source sets sample duration.
    bitrate = 8 * sum(int(packet["size"]) for packet in packets) * 30 / len(packets)
    assert 3_000_000 <= bitrate <= 5_000_000, (
        f"received video bitrate outside 4 Mbps range: {bitrate / 1e6:.2f} Mbps"
    )


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
    hls_page = f"http://{ip}:8888/tv/"
    def hls_status(user=None, password=None):
        headers = {}
        if user is not None:
            credentials = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
            headers["Authorization"] = "Basic " + credentials
        try:
            with opener.open(urllib.request.Request(hls_page, headers=headers), timeout=5) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    assert hls_status() == 401, "anonymous HLS viewer was not rejected"
    assert hls_status("tvviewer", view_password + "__wrong") == 401, "bad HLS password was accepted"
    assert hls_status("tvviewer", view_password) == 200, "viewer cannot open HLS player"
    assert hls_status("tvpublisher", publish_password) == 401, "publisher can open HLS player"
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

    hls_url = f"http://tvviewer:{urllib.parse.quote(view_password, safe='')}@{ip}:8888/tv/index.m3u8"
    for size, bitrate in (("1280x720", "2M"), ("1920x1080", "4M")):
        source = subprocess.Popen(publisher(publisher_url, size, bitrate), stdin=subprocess.DEVNULL,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 20
            while True:
                assert source.poll() is None, "synthetic publisher stopped before media became readable"
                media = probe(viewer_url)
                if media.returncode == 0:
                    streams = json.loads(media.stdout)["streams"]
                    codecs = {(stream["codec_type"], stream["codec_name"]) for stream in streams}
                    assert ("video", "h264") in codecs and ("audio", "opus") in codecs, (
                        "viewer sees wrong codecs; expected H.264 and Opus"
                    )
                    assert any(stream.get("width") == int(size.split("x")[0]) and
                               stream.get("height") == int(size.split("x")[1]) for stream in streams), (
                        "viewer sees wrong published video resolution"
                    )
                    break
                assert time.monotonic() < deadline, "synthetic publisher never became readable"
                time.sleep(0.5)

            if size == "1920x1080":
                video_bitrate(viewer_url)
            decode(viewer_url, size, 5)
            decode(hls_url, size, 5, rtsp=False)
            conflict = subprocess.run(
                publisher(publisher_url, size, bitrate), stdin=subprocess.DEVNULL,
                capture_output=True, timeout=15,
            )
            assert conflict.returncode != 0, "second publisher was not rejected"
            assert source.poll() is None, "first publisher stopped after conflict"
            decode(viewer_url, size, 3)
        finally:
            stop(source)

        deadline = time.monotonic() + 12
        while True:
            empty = probe(viewer_url)
            if empty.returncode != 0 and re.search(rb"\b404\b", empty.stderr):
                break
            assert time.monotonic() < deadline, "source stayed present after publisher stopped"
            time.sleep(0.5)
    print("PASS: auth, 720p/1080p30 RTSP/HLS frames, received 4 Mbps, conflict, publisher restart")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(f"FAIL: smoke could not complete ({type(error).__name__})", file=sys.stderr)
        sys.exit(1)
