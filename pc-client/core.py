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
# -> fallback libx264 software kalau tidak ada satupun yang beneran jalan.
#
# PENTING: `ffmpeg -encoders` cuma nunjukin encoder yang KE-COMPILE di build
# ffmpeg-nya — build lengkap biasanya mencantumkan nvenc/qsv/amf semua,
# terlepas dari GPU apa yang benar-benar terpasang. Jadi cuma cek nama di
# daftar itu TIDAK CUKUP: kita coba encode 1 frame sungguhan ke tiap kandidat
# lewat `-f null -`, dan yang dipakai cuma yang benar-benar berhasil (exit
# code 0). Ini sedikit lebih lambat sekali di awal, tapi hasilnya dicache.
# ---------------------------------------------------------------------------

_HW_ENCODER_CACHE: str | None = None

HW_ENCODER_CANDIDATES = ["h264_nvenc", "h264_qsv", "h264_amf"]


def _encoder_actually_works(encoder: str) -> bool:
    try:
        result = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error",
             "-f", "lavfi", "-i", "nullsrc=s=1280x720", "-frames:v", "1",
             "-c:v", encoder, "-f", "null", "-"],
            capture_output=True, text=True, timeout=15,
        )
        return result.returncode == 0
    except Exception:
        return False


def detect_hw_encoder(force_refresh: bool = False) -> str:
    """Mengembalikan nama encoder H.264 terbaik yang BENAR-BENAR BISA jalan
    di PC ini: salah satu dari HW_ENCODER_CANDIDATES, atau 'libx264' kalau
    tidak ada hardware encoder yang berhasil di-probe (fallback software)."""
    global _HW_ENCODER_CACHE
    if _HW_ENCODER_CACHE is not None and not force_refresh:
        return _HW_ENCODER_CACHE

    try:
        listed = subprocess.run(
            ["ffmpeg", "-hide_banner", "-encoders"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except Exception:
        listed = ""

    for candidate in HW_ENCODER_CANDIDATES:
        # Cek dulu apakah ke-compile (murah) sebelum probe encode (lebih mahal).
        if candidate in listed and _encoder_actually_works(candidate):
            _HW_ENCODER_CACHE = candidate
            return candidate

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

NO_AUDIO_LABEL = "Tanpa Audio"


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


def build_audio_input_args(config: dict) -> list[str] | None:
    """None kalau user pilih 'Tanpa Audio' / tidak set device. Kalau ada,
    mengembalikan args INPUT audio terpisah (device sendiri, bukan bagian
    dari video input) — supaya PC bebas ambil dari device audio apapun
    (loopback speaker atau mic) sesuai pilihan di list_audio_devices()."""
    audio_device = (config.get("audio_device") or "").strip()
    if not audio_device or audio_device == NO_AUDIO_LABEL:
        return None

    system = platform.system()
    if system == "Windows":
        # dshow butuh nama device dibungkus format "audio=<nama>"
        return ["-f", "dshow", "-i", f"audio={audio_device}"]
    elif system == "Darwin":
        # list_audio_devices() Darwin mengembalikan "<index> - <nama>"
        idx = audio_device.split(" - ")[0].strip()
        return ["-f", "avfoundation", "-i", f":{idx}"]
    elif system == "Linux":
        # list_audio_devices() Linux mengembalikan nama source PulseAudio langsung
        return ["-f", "pulse", "-i", audio_device]
    return None


def build_capture_command(config: dict, video_port: int) -> list[str]:
    system = platform.system()
    fps = int(config.get("fps", "30"))
    cmd = ["ffmpeg", "-y"]

    # --- Input 0: video (layar) --------------------------------------------
    cmd += ["-framerate", str(fps)]
    width, height = config.get("width", 1280), config.get("height", 720)
    if config.get("capture_mode") != "window":
        cmd += ["-video_size", f"{width}x{height}"]
        if system == "Windows" and config.get("offset_x") is not None and config.get("offset_y") is not None:
            cmd += ["-offset_x", str(config["offset_x"]), "-offset_y", str(config["offset_y"])]
    cmd += build_video_input_args(config)  # -> input index 0

    # --- Input 1: audio (opsional) ------------------------------------------
    audio_args = build_audio_input_args(config)
    has_audio = audio_args is not None
    if has_audio:
        cmd += audio_args  # -> input index 1

    bitrate_kbps = config.get("bitrate_kbps", 2500)
    gop = fps * 2  # keyframe tiap 2 detik: cukup rapat utk recovery paket hilang,
                   # tapi tidak terlalu sering supaya bitrate tetap efisien.
    encoder = detect_hw_encoder()

    # --- Mapping: video dari input 0, audio (kalau ada) dari input 1 --------
    cmd += ["-map", "0:v:0"]
    if has_audio:
        cmd += ["-map", "1:a:0"]

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

    if has_audio:
        # AAC 128kbps standar buat RTMP/YouTube, ringan buat CPU/USB dibanding video.
        cmd += ["-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2"]

    # Video (+ audio kalau ada) dibungkus satu MPEG-TS lalu dikirim mentah
    # lewat TCP. HP tinggal remux (-c copy) — ini otomatis menyalin SEMUA
    # stream yang ada (video dan audio), jadi MainActivity.kt tidak perlu
    # diubah sama sekali untuk mendukung audio.
    cmd += ["-f", "mpegts", f"tcp://127.0.0.1:{video_port}"]
    return cmd
