"""
Potato Livestreamer — logika inti sisi PC.

Dipakai oleh pc_client_gui.py (rekomendasi, ada UI) dan pc_client.py (CLI,
untuk yang lebih suka terminal / scripting). Tidak ada logika UI di sini
supaya kedua versi tetap konsisten.
"""

from __future__ import annotations

import json
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config.json"

# Preset resolusi umum. "Custom" ditangani terpisah oleh UI (isi manual).
RESOLUTION_PRESETS = {
    "480p (854x480)": (854, 480),
    "720p (1280x720)": (1280, 720),
    "1080p (1920x1080)": (1920, 1080),
}

# Preset bitrate kasar per level kualitas (kbps). Dipakai untuk video 30fps;
# untuk 60fps sebaiknya dinaikkan manual di UI kalau perlu.
BITRATE_PRESETS = {
    "Rendah (hemat data)": {"480p": 800, "720p": 1500, "1080p": 2500},
    "Sedang (disarankan)": {"480p": 1200, "720p": 2500, "1080p": 4500},
    "Tinggi (kualitas terbaik)": {"480p": 1800, "720p": 3500, "1080p": 6000},
}

FPS_OPTIONS = ["24", "30", "60"]

PRESET_SPEED = "ultrafast"  # ultrafast/superfast/veryfast -> lebih ringan CPU
TUNE = "zerolatency"


# ---------------------------------------------------------------------------
# Config persistence
# ---------------------------------------------------------------------------

def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text())
        except Exception:
            pass
    return {}


def save_config(config: dict):
    CONFIG_PATH.write_text(json.dumps(config, indent=2))


# ---------------------------------------------------------------------------
# Tool checks
# ---------------------------------------------------------------------------

def check_tool(name: str) -> bool:
    return shutil.which(name) is not None


def check_device_connected() -> tuple[bool, str]:
    """Mengembalikan (True, serial_device) atau (False, pesan_error)."""
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        return False, "adb tidak ditemukan di PATH."
    except subprocess.TimeoutExpired:
        return False, "adb tidak merespons (timeout)."

    lines = [l for l in result.stdout.splitlines()[1:] if l.strip()]
    devices = [l for l in lines if l.split("\t")[-1].strip() == "device"]
    if not devices:
        return False, (
            "Tidak ada HP terdeteksi. Cek: kabel USB data, USB debugging aktif, "
            "mode USB = File Transfer/MTP, popup 'Allow USB debugging?' sudah di-Allow."
        )
    return True, devices[0].split("\t")[0]


def setup_adb_forward(port: int) -> tuple[bool, str]:
    try:
        result = subprocess.run(
            ["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode != 0:
            return False, result.stderr.strip() or "adb forward gagal."
        return True, f"adb forward tcp:{port} tcp:{port} aktif."
    except Exception as e:
        return False, str(e)


# ---------------------------------------------------------------------------
# Device enumeration (audio devices, window titles) — dipakai tombol
# "Refresh" di GUI supaya user tinggal pilih dari dropdown, bukan ngetik manual.
# ---------------------------------------------------------------------------

def list_audio_devices() -> list[str]:
    """Mengembalikan daftar nama perangkat audio yang bisa dipakai langsung
    sebagai value config['audio_device']."""
    system = platform.system()
    devices: list[str] = []

    try:
        if system == "Windows":
            result = subprocess.run(
                ["ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
                capture_output=True, text=True, timeout=15,
            )
            output = result.stderr
            in_audio_section = False
            for line in output.splitlines():
                if "DirectShow audio devices" in line:
                    in_audio_section = True
                    continue
                if "DirectShow video devices" in line:
                    in_audio_section = False
                    continue
                if in_audio_section:
                    m = re.search(r'"([^"]+)"', line)
                    if m:
                        devices.append(m.group(1))

        elif system == "Darwin":
            result = subprocess.run(
                ["ffmpeg", "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
                capture_output=True, text=True, timeout=15,
            )
            in_audio_section = False
            for line in result.stderr.splitlines():
                if "AVFoundation audio devices" in line:
                    in_audio_section = True
                    continue
                if in_audio_section:
                    m = re.search(r"\[(\d+)\]\s+(.*)", line)
                    if m:
                        devices.append(f"{m.group(1)} - {m.group(2).strip()}")

        elif system == "Linux":
            result = subprocess.run(["pactl", "list", "short", "sources"], capture_output=True, text=True, timeout=10)
            for line in result.stdout.splitlines():
                parts = line.split("\t")
                if len(parts) >= 2:
                    devices.append(parts[1])
    except Exception:
        pass

    return devices


def list_window_titles() -> list[str]:
    """Windows-only: daftar judul jendela yang sedang terbuka, untuk capture
    per-aplikasi (mis. Zoom, Google Meet di browser)."""
    if platform.system() != "Windows":
        return []
    try:
        ps_cmd = (
            "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | "
            "Select-Object -ExpandProperty MainWindowTitle"
        )
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            capture_output=True, text=True, timeout=10,
        )
        titles = [t.strip() for t in result.stdout.splitlines() if t.strip()]
        return titles
    except Exception:
        return []


# ---------------------------------------------------------------------------
# Control channel — kirim Stream URL + Stream Key ke HP
# ---------------------------------------------------------------------------

def send_control_config(control_port: int, stream_url: str, stream_key: str,
                         retries: int = 30, delay: float = 2.0, on_attempt=None) -> tuple[bool, str]:
    payload = json.dumps({"streamUrl": stream_url, "streamKey": stream_key}).encode() + b"\n"
    for attempt in range(1, retries + 1):
        if on_attempt:
            on_attempt(attempt, retries)
        try:
            with socket.create_connection(("127.0.0.1", control_port), timeout=3) as s:
                s.sendall(payload)
                ack = s.recv(16)
                if ack.strip() == b"OK":
                    return True, "Konfigurasi terkirim ke HP."
        except (ConnectionRefusedError, socket.timeout, OSError):
            pass
        time.sleep(delay)
    return False, "Gagal mengirim konfigurasi ke HP (HP belum menekan 'Tunggu Koneksi PC'?)."


# ---------------------------------------------------------------------------
# FFmpeg command building
# ---------------------------------------------------------------------------

def build_video_input_args(config: dict) -> list[str]:
    system = platform.system()
    mode = config.get("capture_mode", "fullscreen")

    if mode == "window" and system == "Windows" and config.get("window_title"):
        return ["-f", "gdigrab", "-i", f"title={config['window_title']}"]

    if system == "Windows":
        return ["-f", "gdigrab", "-i", "desktop"]
    elif system == "Darwin":
        return ["-f", "avfoundation", "-i", "1:none"]
    elif system == "Linux":
        display = ":0.0"
        if config.get("offset_x") is not None and config.get("offset_y") is not None:
            display = f":0.0+{config['offset_x']},{config['offset_y']}"
        return ["-f", "x11grab", "-i", display]
    else:
        raise RuntimeError(f"OS '{system}' belum didukung.")


def build_capture_command(config: dict, video_port: int) -> list[str]:
    system = platform.system()
    cmd = ["ffmpeg", "-y", "-framerate", str(config.get("fps", "30"))]

    width, height = config.get("width", 1280), config.get("height", 720)

    if config.get("capture_mode") != "window":
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

    bitrate_kbps = config.get("bitrate_kbps", 2500)
    cmd += [
        "-c:v", "libx264", "-preset", PRESET_SPEED, "-tune", TUNE,
        "-b:v", f"{bitrate_kbps}k", "-pix_fmt", "yuv420p", "-g", str(int(config.get("fps", 30)) * 2),
    ]
    if has_audio:
        cmd += ["-c:a", "aac", "-b:a", "128k", "-map", "0:v", "-map", "1:a"]

    # mpegts supaya video+audio (kalau ada) bisa lewat 1 koneksi TCP dan
    # di-remux apa adanya (-c copy) di HP tanpa perlu tahu ada audio atau tidak.
    cmd += ["-f", "mpegts", f"tcp://127.0.0.1:{video_port}"]
    return cmd
