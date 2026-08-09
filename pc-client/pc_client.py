#!/usr/bin/env python3
"""
Potato Livestreamer — PC client.

What this script does:
  1. Asks for Stream URL + Stream Key (split fields, same as YouTube Studio's
     "Stream setup help" dialog) ONCE, and saves them locally in config.json
     so you don't have to re-enter them every time.
  2. Lets you choose what to capture: full screen, a specific screen region
     (useful for a second monitor), or a specific window (Windows only) —
     e.g. your Zoom / Google Meet window — plus which audio device to include.
  3. Opens the USB tunnel (adb forward) for both a video port and a small
     control port.
  4. Sends the Stream URL + Stream Key to the phone app over the control
     port (the phone shows "Go LIVE" once it receives this — you don't type
     any RTMP info on the phone).
  5. Captures your PC screen (+ audio, if configured) with FFmpeg and sends
     it to the phone over the video port.
  6. If the connection drops (USB hiccup, phone app restarted, etc.), this
     script automatically retries instead of just quitting — matching the
     phone app's own auto-reconnect behaviour, so a brief interruption
     doesn't require you to manually restart everything.

    PC screen [+ audio] --(ffmpeg encode, mpegts)--> tcp://127.0.0.1:VIDEO_PORT
                                    --(adb forward, USB cable)-->
                                        phone (remux only) --> RTMP --> YouTube

Requirements:
  - Python 3.8+
  - ffmpeg available on PATH
  - adb (Android Platform Tools) available on PATH
  - Phone connected via USB with USB debugging enabled, Potato Livestreamer
    app open and "Tunggu Koneksi PC" already pressed.
"""

import argparse
import json
import platform
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config.json"

# ---------------------------------------------------------------------------
# Default quality settings — the PC does the encoding, so all quality/bitrate
# /FPS knobs live here. The phone only remuxes (copies), it doesn't re-encode.
# ---------------------------------------------------------------------------
DEFAULT_VIDEO_SIZE = "1920x1080"
FRAMERATE = "30"
VIDEO_BITRATE = "4500k"
PRESET = "ultrafast"   # ultrafast/superfast/veryfast -> lower CPU, larger stream
TUNE = "zerolatency"


# ---------------------------------------------------------------------------
# Config persistence
# ---------------------------------------------------------------------------

def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text())
        except Exception:
            print("⚠️ config.json rusak/tidak terbaca, akan dibuat ulang.")
    return {}


def save_config(config: dict):
    CONFIG_PATH.write_text(json.dumps(config, indent=2))
    print(f"💾 Konfigurasi disimpan di {CONFIG_PATH.name}")


def prompt_stream_config(existing: dict) -> dict:
    print("\n--- Konfigurasi YouTube (sama seperti YouTube Studio > Go Live > Stream) ---")
    default_url = existing.get("stream_url", "rtmp://a.rtmp.youtube.com/live2")
    default_key_display = "(tersimpan, kosongkan untuk pakai yang lama)" if existing.get("stream_key") else "(kosong)"

    stream_url = input(f"Stream URL [{default_url}]: ").strip() or default_url
    stream_key = input(f"Stream key {default_key_display}: ").strip() or existing.get("stream_key", "")

    if not stream_key:
        print("❌ Stream key wajib diisi.")
        sys.exit(1)

    result = dict(existing)
    result["stream_url"] = stream_url
    result["stream_key"] = stream_key
    return result


def list_audio_devices():
    system = platform.system()
    print("\n(Daftar perangkat audio yang terdeteksi FFmpeg/OS di bawah ini)")
    try:
        if system == "Windows":
            subprocess.run(["ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"])
        elif system == "Darwin":
            subprocess.run(["ffmpeg", "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""])
        elif system == "Linux":
            subprocess.run(["pactl", "list", "short", "sources"])
    except Exception as e:
        print(f"(gagal menampilkan daftar device: {e})")


def prompt_capture_config(existing: dict) -> dict:
    system = platform.system()
    config = dict(existing)

    print("\n--- Sumber Capture di PC ---")
    print("  1. Seluruh layar / area tertentu (mis. monitor kedua)")
    print("  2. Jendela aplikasi tertentu, mis. Zoom / Google Meet  [Windows only]")
    default_mode = existing.get("capture_mode", "1")
    mode = input(f"Pilih [1/2] (default {default_mode}): ").strip() or default_mode
    config["capture_mode"] = mode

    if mode == "2":
        if system != "Windows":
            print("⚠️ Capture per-jendela pada starter ini hanya didukung di Windows (via gdigrab).")
            print("   Fallback ke capture seluruh layar. Di Linux/macOS, gunakan opsi 'area tertentu'")
            print("   dan posisikan window Zoom/Meet di area tersebut.")
            config["capture_mode"] = "1"
        else:
            print("\n⚠️ Catatan: capture per-jendela via gdigrab kadang gagal (layar hitam) untuk")
            print("   aplikasi yang pakai GPU rendering (termasuk beberapa versi Zoom/Chrome).")
            print("   Kalau itu terjadi, pakai opsi 'area tertentu' sebagai alternatif yang lebih stabil.")
            default_title = existing.get("window_title", "")
            window_title = input(f"Judul jendela persis (lihat title bar) [{default_title}]: ").strip() or default_title
            config["window_title"] = window_title

    if config["capture_mode"] == "1":
        use_region = input("Capture area/monitor tertentu saja, bukan seluruh layar utama? (y/N): ").strip().lower() == "y"
        if use_region:
            config["offset_x"] = input(f"Offset X [{existing.get('offset_x', 0)}]: ").strip() or str(existing.get("offset_x", 0))
            config["offset_y"] = input(f"Offset Y [{existing.get('offset_y', 0)}]: ").strip() or str(existing.get("offset_y", 0))
            config["capture_width"] = input(f"Lebar [{existing.get('capture_width', 1920)}]: ").strip() or str(existing.get("capture_width", 1920))
            config["capture_height"] = input(f"Tinggi [{existing.get('capture_height', 1080)}]: ").strip() or str(existing.get("capture_height", 1080))
        else:
            config.pop("offset_x", None)
            config.pop("offset_y", None)
            config.pop("capture_width", None)
            config.pop("capture_height", None)

    print("\n--- Audio ---")
    print("  Audio bersifat opsional. Kosongkan kalau tidak ingin ada suara di livestream.")
    show_devices = input("Tampilkan daftar perangkat audio yang terdeteksi? (y/N): ").strip().lower() == "y"
    if show_devices:
        list_audio_devices()
    default_audio = existing.get("audio_device", "")
    audio_device = input(f"Nama/index perangkat audio [{default_audio or '(tanpa audio)'}]: ").strip()
    config["audio_device"] = audio_device or default_audio

    return config


# ---------------------------------------------------------------------------
# adb / device helpers
# ---------------------------------------------------------------------------

def check_tool(name: str) -> bool:
    return shutil.which(name) is not None


def check_device_connected() -> bool:
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = [l for l in result.stdout.splitlines()[1:] if l.strip()]
    devices = [l for l in lines if l.split("\t")[-1].strip() == "device"]
    if not devices:
        print("❌ Tidak ada HP terdeteksi lewat adb. Cek:")
        print("   - Kabel USB data (bukan kabel charging-only)")
        print("   - USB debugging aktif di HP")
        print("   - Mode USB di HP di-set ke 'File Transfer/MTP', bukan 'Charging only'")
        print("   - Popup 'Allow USB debugging?' di HP sudah di-Allow")
        return False
    print(f"✅ HP terdeteksi: {devices[0]}")
    return True


def setup_adb_forward(port: int):
    subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], check=True)
    print(f"✅ adb forward tcp:{port} tcp:{port} aktif.")


# ---------------------------------------------------------------------------
# Control channel — sends Stream URL + Stream Key to the phone app
# ---------------------------------------------------------------------------

def send_control_config(control_port: int, stream_url: str, stream_key: str, retries: int = 30) -> bool:
    payload = json.dumps({"streamUrl": stream_url, "streamKey": stream_key}).encode() + b"\n"
    for attempt in range(1, retries + 1):
        try:
            with socket.create_connection(("127.0.0.1", control_port), timeout=3) as s:
                s.sendall(payload)
                ack = s.recv(16)
                if ack.strip() == b"OK":
                    print("✅ Konfigurasi terkirim ke HP.")
                    return True
        except (ConnectionRefusedError, socket.timeout, OSError):
            pass
        print(f"⏳ Menunggu app HP siap menerima konfigurasi... ({attempt}/{retries})")
        time.sleep(2)
    print("❌ Gagal mengirim konfigurasi ke HP.")
    print("   Pastikan app Potato Livestreamer sudah menekan 'Tunggu Koneksi PC'.")
    return False


# ---------------------------------------------------------------------------
# FFmpeg command building
# ---------------------------------------------------------------------------

def build_video_input_args(config: dict) -> list:
    system = platform.system()
    mode = config.get("capture_mode", "1")

    if mode == "2" and system == "Windows" and config.get("window_title"):
        return ["-f", "gdigrab", "-i", f"title={config['window_title']}"]

    if system == "Windows":
        return ["-f", "gdigrab", "-i", "desktop"]
    elif system == "Darwin":
        # Adjust device index with:
        # ffmpeg -f avfoundation -list_devices true -i ""
        return ["-f", "avfoundation", "-i", "1:none"]
    elif system == "Linux":
        display = ":0.0"
        if config.get("offset_x") is not None and config.get("offset_y") is not None:
            display = f":0.0+{config['offset_x']},{config['offset_y']}"
        return ["-f", "x11grab", "-i", display]
    else:
        print(f"❌ OS '{system}' belum didukung script ini.")
        sys.exit(1)


def build_capture_command(config: dict, port: int) -> list:
    system = platform.system()
    cmd = ["ffmpeg", "-y", "-framerate", FRAMERATE]

    if config.get("capture_mode") != "2":
        width = config.get("capture_width") or DEFAULT_VIDEO_SIZE.split("x")[0]
        height = config.get("capture_height") or DEFAULT_VIDEO_SIZE.split("x")[1]
        cmd += ["-video_size", f"{width}x{height}"]
        if system == "Windows" and config.get("offset_x") is not None and config.get("offset_y") is not None:
            cmd += ["-offset_x", str(config["offset_x"]), "-offset_y", str(config["offset_y"])]

    cmd += build_video_input_args(config)

    audio_device = (config.get("audio_device") or "").strip()
    has_audio = bool(audio_device)
    if has_audio:
        if system == "Windows":
            cmd += ["-f", "dshow", "-i", f"audio={audio_device}"]
        elif system == "Darwin":
            cmd += ["-f", "avfoundation", "-i", f":{audio_device}"]
        elif system == "Linux":
            cmd += ["-f", "pulse", "-i", audio_device]

    cmd += [
        "-c:v", "libx264", "-preset", PRESET, "-tune", TUNE,
        "-b:v", VIDEO_BITRATE, "-pix_fmt", "yuv420p", "-g", "60",
    ]
    if has_audio:
        cmd += ["-c:a", "aac", "-b:a", "128k", "-map", "0:v", "-map", "1:a"]

    # mpegts (not raw h264) so an optional audio track can travel alongside
    # video in one stream — the phone side just does "-c copy" on whatever
    # tracks are present.
    cmd += ["-f", "mpegts", f"tcp://127.0.0.1:{port}"]
    return cmd


# ---------------------------------------------------------------------------
# Capture loop with auto-retry (mirrors the phone's own auto-reconnect)
# ---------------------------------------------------------------------------

def run_capture_loop(config: dict, port: int):
    print("\n🎥 Memulai capture & pengiriman ke HP (Ctrl+C untuk berhenti total)...\n")
    backoff = 2.0
    while True:
        cmd = build_capture_command(config, port)
        print(f"$ {' '.join(cmd)}")
        try:
            result = subprocess.run(cmd)
            if result.returncode == 0:
                print("ℹ️ FFmpeg berhenti normal.")
            else:
                print(f"⚠️ FFmpeg berhenti dengan kode {result.returncode} (kemungkinan koneksi ke HP terputus).")
        except FileNotFoundError:
            print("❌ ffmpeg tidak ditemukan.")
            return
        except KeyboardInterrupt:
            print("\n⏹️ Dihentikan oleh pengguna.")
            return

        print(f"🔁 Mencoba menyambung ulang dalam {backoff:.0f} detik... (Ctrl+C untuk berhenti total)")
        try:
            time.sleep(backoff)
        except KeyboardInterrupt:
            return
        backoff = min(backoff * 1.5, 15)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Potato Livestreamer — PC client")
    parser.add_argument("--video-port", type=int, default=6000, help="Port video (samakan dengan app HP)")
    parser.add_argument("--control-port", type=int, default=6001, help="Port kontrol (samakan dengan app HP)")
    parser.add_argument("--reconfigure", action="store_true", help="Paksa tanya ulang semua konfigurasi")
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

    config = load_config()
    if args.reconfigure or "stream_key" not in config:
        config = prompt_stream_config(config)
        config = prompt_capture_config(config)
        save_config(config)
    else:
        print(f"ℹ️ Memakai konfigurasi tersimpan (Stream URL: {config['stream_url']}).")
        print("   Jalankan dengan --reconfigure untuk mengubah Stream URL/Key/sumber capture.")

    setup_adb_forward(args.video_port)
    setup_adb_forward(args.control_port)

    print("\n⚠️ Pastikan app Potato Livestreamer di HP sudah menekan 'Tunggu Koneksi PC'.")
    if not send_control_config(args.control_port, config["stream_url"], config["stream_key"]):
        sys.exit(1)

    print("\n👉 Sekarang tekan tombol '🔴 Go LIVE' di HP, lalu tekan Enter di sini untuk mulai capture.")
    input()

    run_capture_loop(config, args.video_port)


if __name__ == "__main__":
    main()
