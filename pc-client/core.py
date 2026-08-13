"""
Potato Livestreamer — logika inti sisi PC.

ARSITEKTUR (v4): PC encode H.264 (hardware), HP cuma remux — tanpa flicker.
  - PC capture layar lalu langsung ENCODE ke H.264 memakai hardware encoder
    GPU (NVENC/QuickSync/AMF, fallback libx264 software) dengan tuning
    low-latency. H.264 punya kompensasi gerak antar-frame, jadi hasilnya
    stabil di 30-60fps tanpa "flicker" yang muncul kalau kirim JPEG lepas
    per-frame (MJPEG) — tiap frame JPEG dikuantisasi independen, jadi area
    datar/gradasi bisa terlihat "bernapas"/berkedip antar frame, apalagi
    setelah di-decode+encode ulang di HP (double lossy compression).
  - PC mengirim H.264 itu dibungkus MPEG-TS lewat TCP (adb forward) ke HP.
  - HP TIDAK decode/encode apa-apa lagi — cuma REMUX (`-c copy`) stream itu
    langsung ke RTMP/YouTube. Jauh lebih ringan buat HP (tidak panas, tidak
    boros baterai) dibanding versi decode+encode sebelumnya.

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

# Preset kecepatan encoder software (libx264), dipakai HANYA kalau tidak ada
# hardware encoder (NVENC/QuickSync/AMF) terdeteksi di PC. Preset lebih cepat
# = CPU lebih ringan tapi kompresi kurang efisien di bitrate yang sama.
# (Kalau hardware encoder terdeteksi, preset ini tidak dipakai — GPU jauh
# lebih murah CPU-nya daripada libx264 preset apapun.)
ENCODER_SPEED_PRESETS = {
    "Hemat CPU (disarankan utk PC pas-pasan)": "ultrafast",
    "Seimbang": "veryfast",
    "Kualitas lebih baik (CPU lebih berat)": "faster",
}
# Alias lama dipertahankan supaya config.json existing & GUI lama tidak error.
MJPEG_QUALITY_PRESETS = ENCODER_SPEED_PRESETS

# Target bitrate H.264 yang di-ENCODE LANGSUNG DI PC (kbps) sebelum dikirim
# ke HP. HP tidak lagi encode apapun, jadi angka ini sekarang benar-benar
# menentukan kualitas video yang sampai ke penonton YouTube.
BITRATE_PRESETS = {
    "Rendah (hemat data)": {"480p": 800, "720p": 1500, "1080p": 2500},
    "Sedang (disarankan)": {"480p": 1200, "720p": 2500, "1080p": 4500},
    "Tinggi (kualitas terbaik)": {"480p": 1800, "720p": 3500, "1080p": 6000},
}

FPS_OPTIONS = ["24", "30", "60"]


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
# Control channel — kirim Stream URL + Stream Key + target bitrate H.264 ke HP
# (HP yang akan encode, jadi dia perlu tahu target bitrate output-nya)
# ---------------------------------------------------------------------------

def send_control_config(control_port: int, stream_url: str, stream_key: str,
                         video_bitrate_kbps: int = 2500,
                         retries: int = 30, delay: float = 2.0, on_attempt=None) -> tuple[bool, str]:
    payload = json.dumps({
        "streamUrl": stream_url,
        "streamKey": stream_key,
        "videoBitrateKbps": video_bitrate_kbps,
    }).encode() + b"\n"
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
# Deteksi hardware encoder H.264 yang tersedia di PC. Dicoba urut dari yang
# paling murah CPU-nya: NVENC (Nvidia) -> QuickSync (Intel iGPU) -> AMF (AMD)
# -> fallback libx264 software kalau tidak ada satupun yang tersedia.
# Hasil dicache per-proses supaya tidak nge-spawn ffmpeg -encoders berulang.
# ---------------------------------------------------------------------------

_HW_ENCODER_CACHE: str | None = None

HW_ENCODER_CANDIDATES = ["h264_nvenc", "h264_qsv", "h264_amf"]


def detect_hw_encoder(force_refresh: bool = False) -> str:
    """Mengembalikan nama encoder H.264 terbaik yang tersedia di ffmpeg PC
    ini: salah satu dari HW_ENCODER_CANDIDATES, atau 'libx264' kalau tidak
    ada hardware encoder yang terdeteksi (fallback software)."""
    global _HW_ENCODER_CACHE
    if _HW_ENCODER_CACHE is not None and not force_refresh:
        return _HW_ENCODER_CACHE

    try:
        result = subprocess.run(
            ["ffmpeg", "-hide_banner", "-encoders"],
            capture_output=True, text=True, timeout=10,
        )
        available = result.stdout
        for candidate in HW_ENCODER_CANDIDATES:
            if candidate in available:
                _HW_ENCODER_CACHE = candidate
                return candidate
    except Exception:
        pass

    _HW_ENCODER_CACHE = "libx264"
    return "libx264"


def encoder_display_name(encoder: str) -> str:
    return {
        "h264_nvenc": "NVIDIA NVENC (hardware)",
        "h264_qsv": "Intel QuickSync (hardware)",
        "h264_amf": "AMD AMF (hardware)",
        "libx264": "libx264 (software, fallback)",
    }.get(encoder, encoder)


# ---------------------------------------------------------------------------
# FFmpeg command building — PC capture + ENCODE H.264 langsung (hardware bila
# tersedia), dibungkus MPEG-TS, dikirim mentah ke HP untuk cuma di-remux.
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
    fps = int(config.get("fps", "30"))
    cmd = ["ffmpeg", "-y", "-framerate", str(fps)]

    width, height = config.get("width", 1280), config.get("height", 720)

    if config.get("capture_mode") != "window":
        cmd += ["-video_size", f"{width}x{height}"]
        if system == "Windows" and config.get("offset_x") is not None and config.get("offset_y") is not None:
            cmd += ["-offset_x", str(config["offset_x"]), "-offset_y", str(config["offset_y"])]

    cmd += build_video_input_args(config)

    # NOTE (audio): belum didukung di versi arsitektur ini (video-only).
    # Rencana lanjutan: tambah -f dshow/pulse input audio kedua dan mux
    # bareng jadi satu mpegts (-map 0:v -map 1:a) sebelum dikirim ke HP.
    # audio_device = (config.get("audio_device") or "").strip()

    bitrate_kbps = config.get("bitrate_kbps", 2500)
    gop = fps * 2  # keyframe tiap 2 detik: cukup rapat utk recovery paket hilang,
                   # tapi tidak terlalu sering supaya bitrate tetap efisien.
    encoder = detect_hw_encoder()

    cmd += ["-c:v", encoder]
    if encoder == "h264_nvenc":
        cmd += [
            "-preset", "p1", "-tune", "ull",  # p1+ull = ultra-low-latency Nvidia
            "-rc", "cbr", "-b:v", f"{bitrate_kbps}k",
            "-g", str(gop), "-bf", "0", "-zerolatency", "1",
        ]
    elif encoder == "h264_qsv":
        cmd += [
            "-preset", "veryfast",
            "-b:v", f"{bitrate_kbps}k", "-g", str(gop), "-bf", "0",
        ]
    elif encoder == "h264_amf":
        cmd += [
            "-usage", "ultralowlatency", "-quality", "speed",
            "-b:v", f"{bitrate_kbps}k", "-g", str(gop), "-bf", "0",
        ]
    else:  # libx264 software fallback
        speed_preset = config.get("encoder_speed_preset", "ultrafast")
        cmd += [
            "-preset", speed_preset, "-tune", "zerolatency",
            "-b:v", f"{bitrate_kbps}k", "-maxrate", f"{bitrate_kbps}k",
            "-bufsize", f"{bitrate_kbps * 2}k",
            "-g", str(gop), "-bf", "0", "-pix_fmt", "yuv420p",
        ]

    # H.264 elementary stream dibungkus MPEG-TS (standar buat streaming lewat
    # jaringan/pipe) lalu dikirim mentah lewat TCP. HP tinggal remux (-c copy)
    # stream ini langsung ke RTMP, tanpa decode/encode ulang sama sekali —
    # itu sebabnya tidak ada flicker (tidak ada kompresi lossy dobel) dan
    # HP hampir tidak terbebani.
    cmd += ["-f", "mpegts", f"tcp://127.0.0.1:{video_port}"]
    return cmd
