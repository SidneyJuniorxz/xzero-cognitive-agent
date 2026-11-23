#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero (Linux Edition) — Floating Xenon Pet Overlay (Always-on-Top Desktop Widget)
Equivalent to Android's BubbleService.kt:
- Always-on-top borderless window on Linux X11/Wayland
- Draggable with mouse
- Synchronized floating speech bubble
- Click to send quick command or open Control Center
"""

import json
import os
import urllib.request
import webbrowser
import tkinter as tk


SERVER_URL = os.environ.get("XZERO_URL", "http://127.0.0.1:3031")


class XenonFloatingPet:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("X0 / X-Zero Pet")
        self.root.attributes("-topmost", True)
        self.root.overrideredirect(True)
        self.root.configure(bg="#081426")
        self.root.geometry("290x96+40+160")

        self.drag_x = 0
        self.drag_y = 0

        self.frame = tk.Frame(self.root, bg="#081426", highlightbackground="#35d7ff", highlightthickness=2)
        self.frame.pack(fill="both", expand=True)

        top = tk.Frame(self.frame, bg="#081426")
        top.pack(fill="x", padx=8, pady=(6, 2))

        self.avatar_lbl = tk.Label(
            top,
            text="✨ X0",
            bg="#1e1b4b",
            fg="#38bdf8",
            font=("Sans", 11, "bold"),
            padx=8,
            pady=4,
        )
        self.avatar_lbl.pack(side="left")

        self.status_lbl = tk.Label(
            top,
            text="🎙️ Live X0 / X-Zero",
            bg="#081426",
            fg="#4ade80",
            font=("Sans", 9, "bold"),
        )
        self.status_lbl.pack(side="left", padx=6)

        btn_open = tk.Button(
            top,
            text="⚙️ Painel",
            bg="#0284c7",
            fg="white",
            relief="flat",
            font=("Sans", 8, "bold"),
            command=lambda: webbrowser.open(SERVER_URL),
        )
        btn_open.pack(side="right")

        self.speech_lbl = tk.Label(
            self.frame,
            text="Olá! Chame 'X0' / 'X-Zero' ou clique em Painel.",
            bg="#081426",
            fg="#e0f2fe",
            font=("Sans", 9),
            wraplength=265,
            justify="left",
        )
        self.speech_lbl.pack(fill="x", padx=8, pady=4)

        for w in (self.frame, top, self.avatar_lbl, self.speech_lbl):
            w.bind("<ButtonPress-1>", self.start_drag)
            w.bind("<B1-Motion>", self.on_drag)

        self.poll_status()

    def start_drag(self, event):
        self.drag_x = event.x
        self.drag_y = event.y

    def on_drag(self, event):
        x = self.root.winfo_x() + (event.x - self.drag_x)
        y = self.root.winfo_y() + (event.y - self.drag_y)
        self.root.geometry(f"+{x}+{y}")

    def poll_status(self):
        try:
            with urllib.request.urlopen(f"{SERVER_URL}/api/status", timeout=1.5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                sp = data.get("latest_speech", "")
                if sp:
                    self.speech_lbl.config(text=sp[:95])
                busy = data.get("busy", False)
                self.status_lbl.config(
                    text="⏳🎙️ Pensando + /btw" if busy else "🎙️ Live X0 / X-Zero",
                    fg="#f472b6" if busy else "#4ade80",
                )
        except Exception:
            pass
        self.root.after(2000, self.poll_status)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    XenonFloatingPet().run()
