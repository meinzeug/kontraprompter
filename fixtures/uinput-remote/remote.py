#!/usr/bin/env python3
"""Drive one explicitly selected *synthetic* Android input device; never delete recordings."""
import argparse
import json
import re
import secrets
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "de.kontrakurs.kontraprompter"
RUNNING = "Aufnahme läuft"
PAUSED = "Aufnahme pausiert"
READY = {"Kamera bereit", "Aufnahme lokal gespeichert"}


def ui_state(xml_text):
    start = xml_text.find("<?xml")
    end = xml_text.find("</hierarchy>", start)
    if start < 0 or end < 0:
        raise RuntimeError("Android-Ansicht nicht lesbar; keine Taste gesendet")
    root = ET.fromstring(xml_text[start : end + len("</hierarchy>")])
    nodes = [n for n in root.iter("node") if n.get("package") == PACKAGE]
    texts = {n.get("text", "") for n in nodes}
    phases = texts & (READY | {RUNNING, PAUSED, "Aufnahme startet …", "Aufnahme wird gespeichert …", "Aufnahme fehlgeschlagen"})
    if len(phases) != 1:
        raise RuntimeError("Aufnahmestatus nicht eindeutig sichtbar; keine Taste gesendet")
    scroll = texts & {"Start", "Pause"}
    return next(iter(phases)), next(iter(scroll)) if len(scroll) == 1 else None


class Fixture:
    def __init__(self, serial):
        self.serial = serial
        self.name = "KP synthetic HID " + secrets.token_hex(6)
        self.id = secrets.randbelow(900000) + 100000
        self.process = None
        self.descriptor = None
        self.started = False

    def adb(self, *args, timeout=12):
        result = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("ADB-Abfrage fehlgeschlagen; keine Eingabe gesendet")
        return result.stdout

    def snapshot(self):
        # Dump to a PTY, never to shared device storage; only whitelisted status text is returned.
        return ui_state(self.adb("shell", "-t", "uiautomator", "dump", "/dev/tty"))

    def device_block(self):
        dump = self.adb("shell", "dumpsys", "input")
        match = re.search(r"(?m)^\s*\d+: " + re.escape(self.name) + r"\s*\n(?P<body>(?:\s{6,}.*\n)+)", dump)
        return match.group("body") if match else None

    def register(self):
        self.process = subprocess.Popen(["adb", "-s", self.serial, "shell", "uinput", "-"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
        self.write({"id": self.id, "command": "register", "name": self.name, "vid": 0x18d2, "pid": 0x2c42, "bus": "bluetooth", "configuration": [{"type": 100, "data": [1]}, {"type": 101, "data": [28]}]})
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            block = self.device_block()
            if block:
                found = re.search(r"Descriptor:\s*(\S+)", block)
                if not found or "EXTERNAL" not in block:
                    raise RuntimeError("Testgerät nicht als externes Eingabegerät erkannt")
                self.descriptor = found.group(1)
                return
            if self.process.poll() is not None:
                break
            time.sleep(.25)
        raise RuntimeError("Testgerät konnte nicht registriert werden")

    def write(self, command):
        if not self.process or self.process.poll() is not None:
            raise RuntimeError("Testgerät ist nicht mehr aktiv")
        self.process.stdin.write(json.dumps(command, separators=(",", ":")) + "\n")
        self.process.stdin.flush()

    def selected(self):
        # This read is confined to the app's own preference and never prints its contents.
        xml_text = self.adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/prompter_settings.xml")
        root = ET.fromstring(xml_text)
        return any(n.get("name") == "volume_remote_descriptor" and n.text == self.descriptor for n in root)

    def press(self, count):
        if not self.selected() or not self.device_block():
            raise RuntimeError("Eigenes Testgerät nicht mehr ausgewählt/verbunden; keine Taste gesendet")
        for index in range(count):
            self.write({"id": self.id, "command": "inject", "events": [1, 28, 1, 0, 0, 0]})
            self.write({"id": self.id, "command": "delay", "duration": 60})
            self.write({"id": self.id, "command": "inject", "events": [1, 28, 0, 0, 0, 0]})
            if index + 1 < count:
                self.write({"id": self.id, "command": "delay", "duration": 150})

    def await_phase(self, expected, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            phase, scroll = self.snapshot()
            if phase == expected:
                return phase, scroll
            if phase == "Aufnahme fehlgeschlagen":
                break
            time.sleep(.5)
        raise RuntimeError("Erwarteter Aufnahmestatus nicht bestätigt; keine Wiederholung ausführen")

    def close(self):
        if self.process:
            self.process.stdin.close()  # EOF removes only this process's uinput device.
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=5)
            self.process = None
        if self.device_block():
            raise RuntimeError("Testgerät ist noch sichtbar; ADB-Verbindung und Fixture prüfen")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB-Seriennummer des Telepromptergeräts")
    args = parser.parse_args()
    f = Fixture(args.serial)
    try:
        f.register()
        print("Testgerät registriert:", f.name)
        print("In den App-Einstellungen dieses Gerät auswählen und zum verbundenen Prompter zurückkehren.")
        print("Befehle: status, start, scroll, pause, resume, quit. Aufnahme nur regulär über Stopp in der App beenden.")
        while True:
            command = input("> ").strip().lower()
            if command == "quit":
                if f.started:
                    phase, _ = f.snapshot()
                    if phase in {RUNNING, PAUSED, "Aufnahme startet …", "Aufnahme wird gespeichert …"}:
                        print("Testaufnahme läuft noch: zuerst regulär in der App stoppen und Speicherung abwarten.")
                        continue
                break
            phase, scroll = f.snapshot()
            if command == "status":
                print("Aufnahme:", phase, "| Textsteuerung:", scroll or "nicht sichtbar")
            elif command == "start" and phase in READY and not f.started:
                f.press(1)
                # A timeout can still mean the request reached the camera: never retry automatically.
                f.started = True
                f.await_phase(RUNNING)
                print("Aufnahme läuft bestätigt")
            elif command == "scroll" and f.started and phase in {RUNNING, PAUSED} and scroll:
                f.press(1)
                time.sleep(1)
                actual, after = f.snapshot()
                if actual != phase or after not in {"Start", "Pause"} or after == scroll:
                    raise RuntimeError("Textänderung oder unveränderter Aufnahmestatus nicht bestätigt")
                print("Textsteuerung geändert; Aufnahmestatus unverändert")
            elif command in {"pause", "resume"} and f.started and phase == (RUNNING if command == "pause" else PAUSED):
                f.press(2)
                f.await_phase(PAUSED if command == "pause" else RUNNING)
                print("Aufnahmestatus bestätigt")
            else:
                print("Befehl im aktuellen Zustand nicht zulässig; keine Taste gesendet")
    finally:
        f.close()
        print("Testgerät entfernt. In den App-Einstellungen die frühere Geräteauswahl wiederherstellen.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired, EOFError, KeyboardInterrupt) as error:
        print("Test angehalten:", error, "Laufende Aufnahmen nur regulär in der App stoppen.", file=sys.stderr)
        sys.exit(1)
