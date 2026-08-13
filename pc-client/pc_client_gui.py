#!/usr/bin/env python3
"""
Potato Livestreamer — PC client (GUI).

Antarmuka grafis sederhana pakai Tkinter (bawaan Python, tidak perlu install
tambahan). Ini pengganti pc_client.py versi CLI yang dulu ribet — semua
pengaturan (resolusi, FPS, sumber capture, device audio) tinggal pilih dari
dropdown, dan ada log window supaya kalau ada error ffmpeg langsung kelihatan
(bukan hilang begitu saja seperti versi CLI).

Jalankan dengan:
    python pc_client_gui.py
"""

from __future__ import annotations

import platform
import queue
import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

import core

APP_BG = "#F5EAD6"
BROWN = "#3D3226"
TAN = "#D9A15B"
RED = "#E0432B"


class PotatoLivestreamerGUI:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("🥔 Potato Livestreamer — PC Client")
        self.root.geometry("560x760")
        self.root.configure(bg=APP_BG)

        self.log_queue: "queue.Queue[str]" = queue.Queue()
        self.capture_process: subprocess.Popen | None = None
        self.capture_thread: threading.Thread | None = None
        self.stop_requested = threading.Event()
        self.is_live = False
        self.live_started_at: float | None = None

        self.config_data = core.load_config()

        self._build_ui()
        self._poll_log_queue()
        self._tick_timer()

    # ------------------------------------------------------------------
    # UI construction
    # ------------------------------------------------------------------

    def _build_ui(self):
        pad = {"padx": 12, "pady": 4}

        header = tk.Label(self.root, text="Potato Livestreamer", bg=APP_BG, fg=BROWN,
                           font=("Segoe UI", 16, "bold"))
        header.pack(pady=(12, 8))

        form = tk.Frame(self.root, bg=APP_BG)
        form.pack(fill="x", **pad)

        # --- Stream URL / Key ---------------------------------------------------
        self._label(form, "Stream URL (YouTube Studio > Go Live > Stream)")
        self.stream_url_var = tk.StringVar(value=self.config_data.get("stream_url", "rtmp://a.rtmp.youtube.com/live2"))
        tk.Entry(form, textvariable=self.stream_url_var, width=50).pack(fill="x", pady=(0, 8))

        self._label(form, "Stream Key")
        self.stream_key_var = tk.StringVar(value=self.config_data.get("stream_key", ""))
        tk.Entry(form, textvariable=self.stream_key_var, show="•", width=50).pack(fill="x", pady=(0, 12))

        # --- Resolution / FPS / Bitrate -----------------------------------------
        row1 = tk.Frame(form, bg=APP_BG)
        row1.pack(fill="x", pady=(0, 8))

        col1 = tk.Frame(row1, bg=APP_BG)
        col1.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self._label(col1, "Resolusi")
        self.resolution_var = tk.StringVar(value=self.config_data.get("resolution", "720p (1280x720)"))
        ttk.Combobox(col1, textvariable=self.resolution_var,
                     values=list(core.RESOLUTION_PRESETS.keys()), state="readonly").pack(fill="x")

        col2 = tk.Frame(row1, bg=APP_BG)
        col2.pack(side="left", fill="x", expand=True, padx=(6, 0))
        self._label(col2, "FPS")
        self.fps_var = tk.StringVar(value=self.config_data.get("fps", "30"))
        ttk.Combobox(col2, textvariable=self.fps_var, values=core.FPS_OPTIONS, state="readonly").pack(fill="x")

        encoder = core.detect_hw_encoder()
        self._label(form, f"Encoder PC: {core.encoder_display_name(encoder)}")
        self.mjpeg_quality_var = tk.StringVar(value=self.config_data.get("mjpeg_quality_preset", "Seimbang (disarankan)"))
        ttk.Combobox(form, textvariable=self.mjpeg_quality_var,
                     values=list(core.ENCODER_SPEED_PRESETS.keys()), state="readonly").pack(fill="x", pady=(0, 4))
        if encoder == "libx264":
            note = "Tidak ada GPU hardware encoder terdeteksi — pakai software (lebih berat CPU). Turunkan preset kalau lag."
        else:
            note = "GPU hardware encoder terdeteksi — preset ini nyaris tidak berpengaruh ke CPU."
        tk.Label(form, text=note, bg=APP_BG, fg=BROWN, font=("Segoe UI", 8, "italic")).pack(anchor="w", pady=(0, 10))

        self._label(form, "Kualitas / Bitrate Video ke YouTube (di-encode PC, H.264)")
        self.bitrate_var = tk.StringVar(value=self.config_data.get("bitrate_preset", "Sedang (disarankan)"))
        ttk.Combobox(form, textvariable=self.bitrate_var,
                     values=list(core.BITRATE_PRESETS.keys()), state="readonly").pack(fill="x", pady=(0, 12))

        # --- Capture source -------------------------------------------------------
        self._label(form, "Sumber Capture")
        self.capture_mode_var = tk.StringVar(value=self.config_data.get("capture_mode", "fullscreen"))
        mode_frame = tk.Frame(form, bg=APP_BG)
        mode_frame.pack(fill="x")
        tk.Radiobutton(mode_frame, text="Seluruh Layar", variable=self.capture_mode_var, value="fullscreen",
                        bg=APP_BG, command=self._on_capture_mode_change).pack(side="left")
        tk.Radiobutton(mode_frame, text="Area Tertentu", variable=self.capture_mode_var, value="region",
                        bg=APP_BG, command=self._on_capture_mode_change).pack(side="left")
        window_state = "normal" if platform.system() == "Windows" else "disabled"
        tk.Radiobutton(mode_frame, text="Jendela Aplikasi (Windows)", variable=self.capture_mode_var, value="window",
                        bg=APP_BG, command=self._on_capture_mode_change, state=window_state).pack(side="left")

        # Region fields (shown only when "Area Tertentu" dipilih)
        self.region_frame = tk.Frame(form, bg=APP_BG)
        self._label(self.region_frame, "Offset X / Offset Y / Lebar / Tinggi")
        region_row = tk.Frame(self.region_frame, bg=APP_BG)
        region_row.pack(fill="x")
        self.offset_x_var = tk.StringVar(value=str(self.config_data.get("offset_x", 0)))
        self.offset_y_var = tk.StringVar(value=str(self.config_data.get("offset_y", 0)))
        self.region_w_var = tk.StringVar(value=str(self.config_data.get("region_width", 1280)))
        self.region_h_var = tk.StringVar(value=str(self.config_data.get("region_height", 720)))
        for var in (self.offset_x_var, self.offset_y_var, self.region_w_var, self.region_h_var):
            tk.Entry(region_row, textvariable=var, width=8).pack(side="left", padx=2)

        # Window title picker (shown only when "Jendela Aplikasi" dipilih)
        self.window_frame = tk.Frame(form, bg=APP_BG)
        self._label(self.window_frame, "Pilih Jendela")
        window_row = tk.Frame(self.window_frame, bg=APP_BG)
        window_row.pack(fill="x")
        self.window_title_var = tk.StringVar(value=self.config_data.get("window_title", ""))
        self.window_combo = ttk.Combobox(window_row, textvariable=self.window_title_var, state="readonly", width=38)
        self.window_combo.pack(side="left", fill="x", expand=True)
        tk.Button(window_row, text="🔄 Refresh", command=self._refresh_windows).pack(side="left", padx=4)

        self._on_capture_mode_change()  # tampilkan frame yang sesuai di awal

        # --- Audio -----------------------------------------------------------------
        audio_note = tk.Label(
            form,
            text="🔇 Audio belum didukung di arsitektur ini (PC kirim H.264 video-only).\n"
                 "Rencana lanjutan: jalur audio terpisah — lihat README.",
            bg=APP_BG, fg=BROWN, font=("Segoe UI", 9, "italic"), justify="left", anchor="w",
        )
        audio_note.pack(fill="x", pady=(0, 12))

        # --- Ports (advanced) -------------------------------------------------------
        ports_row = tk.Frame(form, bg=APP_BG)
        ports_row.pack(fill="x", pady=(0, 12))
        pcol1 = tk.Frame(ports_row, bg=APP_BG)
        pcol1.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self._label(pcol1, "Port Video")
        self.video_port_var = tk.StringVar(value=str(self.config_data.get("video_port", 6000)))
        tk.Entry(pcol1, textvariable=self.video_port_var).pack(fill="x")
        pcol2 = tk.Frame(ports_row, bg=APP_BG)
        pcol2.pack(side="left", fill="x", expand=True, padx=(6, 0))
        self._label(pcol2, "Port Kontrol")
        self.control_port_var = tk.StringVar(value=str(self.config_data.get("control_port", 6001)))
        tk.Entry(pcol2, textvariable=self.control_port_var).pack(fill="x")

        # --- Buttons -----------------------------------------------------------------
        btn_row = tk.Frame(self.root, bg=APP_BG)
        btn_row.pack(fill="x", padx=12, pady=(4, 4))
        self.start_button = tk.Button(btn_row, text="🔴 Mulai Live", bg=RED, fg="white",
                                       font=("Segoe UI", 11, "bold"), command=self._on_start_clicked)
        self.start_button.pack(side="left", fill="x", expand=True, padx=(0, 4), ipady=8)
        self.stop_button = tk.Button(btn_row, text="⏹ Stop", bg=BROWN, fg="white",
                                      font=("Segoe UI", 11, "bold"), command=self._on_stop_clicked, state="disabled")
        self.stop_button.pack(side="left", fill="x", expand=True, padx=(4, 0), ipady=8)

        # --- Status ------------------------------------------------------------------
        self.status_var = tk.StringVar(value="Belum dimulai.")
        tk.Label(self.root, textvariable=self.status_var, bg=APP_BG, fg=BROWN,
                 font=("Segoe UI", 11, "bold")).pack(pady=(8, 4))

        # --- Log -----------------------------------------------------------------------
        log_frame = tk.Frame(self.root, bg=APP_BG)
        log_frame.pack(fill="both", expand=True, padx=12, pady=(4, 12))
        tk.Label(log_frame, text="Log", bg=APP_BG, fg=BROWN, anchor="w").pack(fill="x")
        self.log_text = tk.Text(log_frame, height=12, bg="#FFFFFF", fg=BROWN, font=("Consolas", 9))
        self.log_text.pack(fill="both", expand=True)
        self.log_text.configure(state="disabled")

    def _label(self, parent, text):
        tk.Label(parent, text=text, bg=APP_BG, fg=BROWN, font=("Segoe UI", 9)).pack(anchor="w")

    def _on_capture_mode_change(self):
        self.region_frame.pack_forget()
        self.window_frame.pack_forget()
        mode = self.capture_mode_var.get()
        if mode == "region":
            self.region_frame.pack(fill="x", pady=(0, 8))
        elif mode == "window":
            self.window_frame.pack(fill="x", pady=(0, 8))

    # ------------------------------------------------------------------
    # Refresh button (window titles)
    # ------------------------------------------------------------------

    def _refresh_windows(self):
        self._log("🔍 Mencari jendela aplikasi yang terbuka...")

        def worker():
            titles = core.list_window_titles()
            self.root.after(0, lambda: self._apply_window_list(titles))

        threading.Thread(target=worker, daemon=True).start()

    def _apply_window_list(self, titles: list[str]):
        self.window_combo["values"] = titles
        if titles:
            self._log(f"✅ Ditemukan {len(titles)} jendela terbuka.")
        else:
            self._log("⚠️ Tidak ada jendela terdeteksi.")

    # ------------------------------------------------------------------
    # Start / Stop
    # ------------------------------------------------------------------

    def _gather_config(self) -> dict | None:
        stream_url = self.stream_url_var.get().strip()
        stream_key = self.stream_key_var.get().strip()
        if not stream_url.startswith("rtmp"):
            messagebox.showerror("Error", "Stream URL harus diawali rtmp:// atau rtmps://")
            return None
        if not stream_key:
            messagebox.showerror("Error", "Stream Key wajib diisi.")
            return None

        resolution_label = self.resolution_var.get()
        width, height = core.RESOLUTION_PRESETS.get(resolution_label, (1280, 720))

        bitrate_label = self.bitrate_var.get()
        res_key = resolution_label.split(" ")[0]  # "720p (1280x720)" -> "720p"
        bitrate_kbps = core.BITRATE_PRESETS.get(bitrate_label, {}).get(res_key, 2500)

        speed_label = self.mjpeg_quality_var.get()
        encoder_speed_preset = core.ENCODER_SPEED_PRESETS.get(speed_label, "ultrafast")

        capture_mode = self.capture_mode_var.get()

        config = {
            "stream_url": stream_url,
            "stream_key": stream_key,
            "resolution": resolution_label,
            "fps": self.fps_var.get(),
            "bitrate_preset": bitrate_label,
            "bitrate_kbps": bitrate_kbps,
            "mjpeg_quality_preset": speed_label,
            "encoder_speed_preset": encoder_speed_preset,
            "width": width,
            "height": height,
            "capture_mode": capture_mode,
            "video_port": int(self.video_port_var.get() or 6000),
            "control_port": int(self.control_port_var.get() or 6001),
        }

        if capture_mode == "region":
            config["offset_x"] = int(self.offset_x_var.get() or 0)
            config["offset_y"] = int(self.offset_y_var.get() or 0)
            config["width"] = int(self.region_w_var.get() or width)
            config["height"] = int(self.region_h_var.get() or height)
        elif capture_mode == "window":
            title = self.window_title_var.get().strip()
            if not title:
                messagebox.showerror("Error", "Pilih jendela aplikasi dulu (tombol Refresh).")
                return None
            config["window_title"] = title

        core.save_config(config)
        return config

    def _on_start_clicked(self):
        config = self._gather_config()
        if config is None:
            return

        if not core.check_tool("ffmpeg"):
            messagebox.showerror("Error", "ffmpeg tidak ditemukan di PATH.")
            return
        if not core.check_tool("adb"):
            messagebox.showerror("Error", "adb tidak ditemukan di PATH (lihat panduan AdbWinApi.dll di README).")
            return

        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.stop_requested.clear()
        self.status_var.set("🔍 Mengecek HP...")

        self.capture_thread = threading.Thread(target=self._run_pipeline, args=(config,), daemon=True)
        self.capture_thread.start()

    def _on_stop_clicked(self):
        self.stop_requested.set()
        if self.capture_process and self.capture_process.poll() is None:
            self.capture_process.terminate()
        self.is_live = False
        self.live_started_at = None
        self.status_var.set("⏹ Dihentikan.")
        self.start_button.configure(state="normal")
        self.stop_button.configure(state="disabled")
        self._log("⏹ Dihentikan oleh pengguna.")

    # ------------------------------------------------------------------
    # Background pipeline: cek device -> adb forward -> kirim config -> capture loop
    # ------------------------------------------------------------------

    def _run_pipeline(self, config: dict):
        ok, info = core.check_device_connected()
        if not ok:
            self._log(f"❌ {info}")
            self._set_status_threadsafe("❌ HP tidak terdeteksi.")
            self._reset_buttons_threadsafe()
            return
        self._log(f"✅ HP terdeteksi: {info}")

        for port in (config["video_port"], config["control_port"]):
            ok, msg = core.setup_adb_forward(port)
            self._log(("✅ " if ok else "❌ ") + msg)
            if not ok:
                self._set_status_threadsafe("❌ adb forward gagal.")
                self._reset_buttons_threadsafe()
                return

        self._set_status_threadsafe("📤 Mengirim konfigurasi ke HP...")
        self._log("📤 Mengirim Stream URL + Key ke HP. Pastikan app HP sudah menekan 'Tunggu Koneksi PC'.")

        def on_attempt(attempt, total):
            self._log(f"⏳ Menunggu HP siap menerima konfigurasi... ({attempt}/{total})")

        ok, msg = core.send_control_config(
            config["control_port"], config["stream_url"], config["stream_key"],
            video_bitrate_kbps=config["bitrate_kbps"], on_attempt=on_attempt
        )
        self._log(("✅ " if ok else "❌ ") + msg)
        if not ok:
            self._set_status_threadsafe("❌ Gagal mengirim konfigurasi.")
            self._reset_buttons_threadsafe()
            return

        self._set_status_threadsafe("🎥 Memulai capture & menyambung ke HP...")
        self._log("🎥 Memulai capture. Tekan '🔴 Go LIVE' di HP kalau belum (script akan otomatis retry).")
        self._capture_loop(config)

    def _capture_loop(self, config: dict):
        backoff = 2.0
        while not self.stop_requested.is_set():
            cmd = core.build_capture_command(config, config["video_port"])
            self._log("$ " + " ".join(cmd))
            try:
                self.capture_process = subprocess.Popen(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    universal_newlines=True, bufsize=1,
                )
            except FileNotFoundError:
                self._log("❌ ffmpeg tidak ditemukan.")
                break

            connected_this_run = False
            for line in self.capture_process.stdout:
                if self.stop_requested.is_set():
                    break
                line = line.rstrip()
                if not line:
                    continue
                self._log(line)
                if "frame=" in line and not connected_this_run:
                    connected_this_run = True
                    self.is_live = True
                    self.live_started_at = time.time()
                    self._log("✅ Video tersambung ke HP dan sedang mengalir.")

            self.capture_process.wait()
            self.is_live = False

            if self.stop_requested.is_set():
                break

            self._log(f"⚠️ Capture berhenti/terputus. Mencoba lagi dalam {backoff:.0f} detik...")
            self._set_status_threadsafe("⚠️ Terputus — menyambung ulang...")
            time.sleep(backoff)
            backoff = min(backoff * 1.5, 15)

        self._reset_buttons_threadsafe()
        if not self.stop_requested.is_set():
            self._set_status_threadsafe("⏹ Berhenti.")

    # ------------------------------------------------------------------
    # Thread-safe helpers
    # ------------------------------------------------------------------

    def _log(self, message: str):
        self.log_queue.put(message)

    def _poll_log_queue(self):
        try:
            while True:
                message = self.log_queue.get_nowait()
                self.log_text.configure(state="normal")
                self.log_text.insert("end", message + "\n")
                self.log_text.see("end")
                self.log_text.configure(state="disabled")
        except queue.Empty:
            pass
        self.root.after(150, self._poll_log_queue)

    def _tick_timer(self):
        if self.is_live and self.live_started_at:
            elapsed = int(time.time() - self.live_started_at)
            h, rem = divmod(elapsed, 3600)
            m, s = divmod(rem, 60)
            self.status_var.set(f"🔴 LIVE — {h:02d}:{m:02d}:{s:02d}")
        self.root.after(1000, self._tick_timer)

    def _set_status_threadsafe(self, text: str):
        self.root.after(0, lambda: self.status_var.set(text))

    def _reset_buttons_threadsafe(self):
        def _reset():
            self.start_button.configure(state="normal")
            self.stop_button.configure(state="disabled")
        self.root.after(0, _reset)


def main():
    root = tk.Tk()
    PotatoLivestreamerGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
