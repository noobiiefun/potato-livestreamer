#!/usr/bin/env python3
"""
Potato Livestreamer — PC client.

This script does NOT talk to YouTube directly. It only:
  1. Checks that an Android phone is connected via USB (adb).
  2. Opens an `adb forward` tunnel over the USB cable.
  3. Captures this PC's own screen with FFmpeg and sends the encoded H.264
     stream as a TCP client into that tunnel.

The phone (running the Potato Livestreamer app, already in "Start Listening"
mode) receives that stream on the other end and pushes it to YouTube using
its own internet connection.

    PC screen --(ffmpeg encode)--> tcp://127.0.0.1:PORT --(adb forward, USB)--> phone

Requirements:
  - Python 3.8+
  - ffmpeg available on PATH
  - adb (Android Platform Tools) available on PATH
  - Phone connected via USB with USB debugging enabled, and the Potato
    Livestreamer app already running in "Start Listening" state.
"""

import argparse
import platform
import shutil
import subprocess
import sys
import time

# ---------------------------------------------------------------------------
# Quality settings — this PC does the encoding, so all quality/bitrate/FPS
# knobs live here. The phone only remuxes (copies), it doesn't re-encode.
# ---------------------------------------------------------------------------
VIDEO_SIZE = "1920x1080"
FRAMERATE = "30"
VIDEO_BITRATE = "4500k"
PRESET = "ultrafast"   # ultrafast/superfast/veryfast -> lower CPU, larger stream
TUNE = "zerolatency"

# TODO(audio): PC audio isn't captured in this starter script yet. To add it:
#   Windows : add `-f dshow -i audio="<your virtual audio device>"` and mux with
#             `-map 0:v -map 1:a` before the output.
#   Linux   : add `-f pulse -i default` similarly.
#   macOS   : add `-f avfoundation -i ":0"` (adjust device index) similarly.
#   Then push AAC audio (`-c:a aac -b:a 128k`) alongside `-c:v copy` on the
#   phone side (MainActivity.kt) so both streams get muxed into the FLV output.


def check_tool(name: str) -> bool:
    return shutil.which(name) is not None


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}")
    return subprocess.run(cmd, **kwargs)


def check_device_connected() -> bool:
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = [l for l in result.stdout.splitlines()[1:] if l.strip()]
    devices = [l for l in lines if l.split("\t")[-1].strip() == "device"]
    if not devices:
        print("❌ Tidak ada HP terdeteksi lewat adb. Cek:")
        print("   - Kabel USB data (bukan cable charging-only)")
        print("   - USB debugging aktif di HP")
        print("   - Mode USB di HP di-set ke 'File Transfer/MTP', bukan 'Charging only'")
        print("   - Popup 'Allow USB debugging?' di HP sudah di-Allow")
        return False
    print(f"✅ HP terdeteksi: {devices[0]}")
    return True


def setup_adb_forward(port: int):
    run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], check=True)
    print(f"✅ adb forward tcp:{port} tcp:{port} aktif (tunnel lewat USB siap).")


def build_capture_command(port: int) -> list[str]:
    system = platform.system()

    base = [
        "ffmpeg",
        "-y",
        "-framerate", FRAMERATE,
        "-video_size", VIDEO_SIZE,
    ]

    if system == "Windows":
        input_args = ["-f", "gdigrab", "-i", "desktop"]
    elif system == "Darwin":  # macOS
        # "1:none" = display index 1, no audio device. Adjust index with
        # `ffmpeg -f avfoundation -list_devices true -i ""` if needed.
        input_args = ["-f", "avfoundation", "-i", "1:none"]
    elif system == "Linux":
        input_args = ["-f", "x11grab", "-i", ":0.0"]
    else:
        print(f"❌ OS '{system}' belum didukung script ini. Sesuaikan build_capture_command().")
        sys.exit(1)

    encode_args = [
        "-c:v", "libx264",
        "-preset", PRESET,
        "-tune", TUNE,
        "-b:v", VIDEO_BITRATE,
        "-pix_fmt", "yuv420p",
        "-g", "60",
        "-f", "h264",
        f"tcp://127.0.0.1:{port}",
    ]

    return base + input_args + encode_args


def main():
    parser = argparse.ArgumentParser(description="Potato Livestreamer — PC client")
    parser.add_argument("--port", type=int, default=6000, help="Port lokal (harus sama dengan yang di-set di app HP)")
    args = parser.parse_args()

    print("🥔 Potato Livestreamer — PC client\n")

    if not check_tool("ffmpeg"):
        print("❌ ffmpeg tidak ditemukan di PATH. Install dulu: https://ffmpeg.org/download.html")
        sys.exit(1)
    if not check_tool("adb"):
        print("❌ adb tidak ditemukan di PATH. Install Android Platform Tools dulu.")
        sys.exit(1)

    if not check_device_connected():
        sys.exit(1)

    setup_adb_forward(args.port)

    print("\n⚠️  Pastikan app Potato Livestreamer di HP SUDAH ditekan 'Start Listening'")
    print("    sebelum lanjut, supaya FFmpeg di HP sudah menunggu koneksi.")
    input("    Tekan Enter untuk mulai capture & kirim layar PC...\n")

    cmd = build_capture_command(args.port)
    print("🎥 Memulai capture layar & mengirim ke HP...\n")

    try:
        run(cmd, check=True)
    except subprocess.CalledProcessError as e:
        print(f"\n❌ FFmpeg berhenti dengan error (exit code {e.returncode}).")
        print("   Cek apakah app di HP masih dalam status 'Listening' dan port cocok.")
    except KeyboardInterrupt:
        print("\n⏹️ Dihentikan oleh pengguna.")


if __name__ == "__main__":
    main()
