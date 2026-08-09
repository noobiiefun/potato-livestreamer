#!/usr/bin/env python3
"""
Potato Livestreamer — PC client (CLI).

Versi terminal, untuk yang lebih suka command line / mau scripting otomatis.
Kalau baru mulai dan mau lebih mudah, pakai pc_client_gui.py saja — ada
dropdown resolusi/FPS/audio dan log window, jadi lebih gampang di-debug.

Requirements: Python 3.8+, ffmpeg & adb di PATH, HP tersambung USB dengan
app Potato Livestreamer sudah menekan "Tunggu Koneksi PC".
"""

from __future__ import annotations

import argparse
import sys
import time

import core


def prompt_stream_config(existing: dict) -> dict:
    print("\n--- Konfigurasi YouTube (sama seperti YouTube Studio > Go Live > Stream) ---")
    default_url = existing.get("stream_url", "rtmp://a.rtmp.youtube.com/live2")
    has_saved_key = bool(existing.get("stream_key"))

    stream_url = input(f"Stream URL [{default_url}]: ").strip() or default_url
    key_hint = "(tersimpan, kosongkan untuk pakai yang lama)" if has_saved_key else "(kosong)"
    stream_key = input(f"Stream key {key_hint}: ").strip() or existing.get("stream_key", "")

    if not stream_key:
        print("❌ Stream key wajib diisi.")
        sys.exit(1)

    result = dict(existing)
    result["stream_url"] = stream_url
    result["stream_key"] = stream_key
    return result


def prompt_capture_config(existing: dict) -> dict:
    config = dict(existing)

    print("\n--- Sumber Capture di PC ---")
    print("  1. Seluruh layar / area tertentu")
    print("  2. Jendela aplikasi tertentu (Windows only)")
    choice = input("Pilih [1/2] (default 1): ").strip() or "1"

    if choice == "2":
        titles = core.list_window_titles()
        if titles:
            print("Jendela terbuka:")
            for i, t in enumerate(titles, 1):
                print(f"  {i}. {t}")
            idx = input("Pilih nomor jendela: ").strip()
            try:
                config["window_title"] = titles[int(idx) - 1]
                config["capture_mode"] = "window"
            except (ValueError, IndexError):
                print("⚠️ Pilihan tidak valid, fallback ke seluruh layar.")
                config["capture_mode"] = "fullscreen"
        else:
            print("⚠️ Tidak ada jendela terdeteksi (atau bukan Windows). Fallback ke seluruh layar.")
            config["capture_mode"] = "fullscreen"
    else:
        config["capture_mode"] = "fullscreen"
        use_region = input("Capture area tertentu saja? (y/N): ").strip().lower() == "y"
        if use_region:
            config["capture_mode"] = "region"
            config["offset_x"] = int(input("Offset X [0]: ").strip() or 0)
            config["offset_y"] = int(input("Offset Y [0]: ").strip() or 0)

    print("\nResolusi:")
    keys = list(core.RESOLUTION_PRESETS.keys())
    for i, k in enumerate(keys, 1):
        print(f"  {i}. {k}")
    res_choice = input("Pilih resolusi [2]: ").strip() or "2"
    try:
        resolution_label = keys[int(res_choice) - 1]
    except (ValueError, IndexError):
        resolution_label = keys[1]
    width, height = core.RESOLUTION_PRESETS[resolution_label]
    config["width"], config["height"] = width, height

    fps = input(f"FPS [{core.FPS_OPTIONS}] (default 30): ").strip() or "30"
    config["fps"] = fps

    print("\nKualitas:")
    b_keys = list(core.BITRATE_PRESETS.keys())
    for i, k in enumerate(b_keys, 1):
        print(f"  {i}. {k}")
    b_choice = input("Pilih kualitas [2]: ").strip() or "2"
    try:
        bitrate_label = b_keys[int(b_choice) - 1]
    except (ValueError, IndexError):
        bitrate_label = b_keys[1]
    res_key = resolution_label.split(" ")[0]
    config["bitrate_kbps"] = core.BITRATE_PRESETS[bitrate_label].get(res_key, 2500)

    print("\n--- Audio (opsional) ---")
    show = input("Tampilkan daftar perangkat audio? (y/N): ").strip().lower() == "y"
    if show:
        devices = core.list_audio_devices()
        if devices:
            for i, d in enumerate(devices, 1):
                print(f"  {i}. {d}")
            idx = input("Pilih nomor perangkat (kosongkan untuk tanpa audio): ").strip()
            if idx:
                try:
                    config["audio_device"] = devices[int(idx) - 1]
                except (ValueError, IndexError):
                    config["audio_device"] = ""
            else:
                config["audio_device"] = ""
        else:
            print("⚠️ Tidak ada perangkat audio terdeteksi.")
            config["audio_device"] = ""
    else:
        config["audio_device"] = existing.get("audio_device", "")

    return config


def run_capture_loop(config: dict, port: int):
    print("\n🎥 Memulai capture & pengiriman ke HP (Ctrl+C untuk berhenti total)...\n")
    backoff = 2.0
    while True:
        cmd = core.build_capture_command(config, port)
        print(f"$ {' '.join(cmd)}")
        try:
            import subprocess
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


def main():
    parser = argparse.ArgumentParser(description="Potato Livestreamer — PC client (CLI)")
    parser.add_argument("--video-port", type=int, default=6000)
    parser.add_argument("--control-port", type=int, default=6001)
    parser.add_argument("--reconfigure", action="store_true")
    args = parser.parse_args()

    print("🥔 Potato Livestreamer — PC client (CLI)\n")
    print("ℹ️ Tips: kalau mau lebih mudah, coba `python pc_client_gui.py` — ada UI & log visual.\n")

    if not core.check_tool("ffmpeg"):
        print("❌ ffmpeg tidak ditemukan di PATH.")
        sys.exit(1)
    if not core.check_tool("adb"):
        print("❌ adb tidak ditemukan di PATH.")
        sys.exit(1)

    ok, info = core.check_device_connected()
    if not ok:
        print(f"❌ {info}")
        sys.exit(1)
    print(f"✅ HP terdeteksi: {info}")

    config = core.load_config()
    if args.reconfigure or "stream_key" not in config:
        config = prompt_stream_config(config)
        config = prompt_capture_config(config)
        core.save_config(config)
    else:
        print(f"ℹ️ Memakai konfigurasi tersimpan (Stream URL: {config['stream_url']}).")
        print("   Jalankan dengan --reconfigure untuk mengubah.")

    for port in (args.video_port, args.control_port):
        ok, msg = core.setup_adb_forward(port)
        print(("✅ " if ok else "❌ ") + msg)
        if not ok:
            sys.exit(1)

    print("\n⚠️ Pastikan app Potato Livestreamer di HP sudah menekan 'Tunggu Koneksi PC'.")
    ok, msg = core.send_control_config(
        args.control_port, config["stream_url"], config["stream_key"],
        on_attempt=lambda a, t: print(f"⏳ Menunggu HP siap... ({a}/{t})"),
    )
    print(("✅ " if ok else "❌ ") + msg)
    if not ok:
        sys.exit(1)

    # Tidak perlu menunggu Enter manual lagi — capture loop otomatis retry
    # sampai HP menekan "Go LIVE" dan mulai listening.
    print("\n👉 Tekan '🔴 Go LIVE' di HP sekarang (script ini otomatis menyambung begitu HP siap).")
    run_capture_loop(config, args.video_port)


if __name__ == "__main__":
    main()
