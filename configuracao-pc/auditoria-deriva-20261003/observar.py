"""Read-only audio/process observation until 02:51:25 on 2026-10-03."""
from pathlib import Path
from datetime import datetime, timedelta
import csv
import ctypes
from ctypes import wintypes
import json
import re
import statistics
import time

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parent
LOG = ROOT / "audio-sistema.log"
DEADLINE = datetime(2026, 10, 3, 2, 51, 25)
RUNNER, PLAYER = 30560, 33084
FIELDS = ("capturedFrames", "sentFrames", "paddingSilenceFrames", "droppedFrames",
          "queueMs", "queueHighMs", "discontinuities", "timestampErrors",
          "maxWriteMs", "writeInProgressMs", "maxCaptureGapMs", "poolAllocations",
          "inFlightFrames", "meterMsPer10ms", "maxMeterMs", "peakAbs", "overOneSamples")
kernel = ctypes.WinDLL("kernel32", use_last_error=True)
kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.GetProcessTimes.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.FILETIME),
                                   ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME),
                                   ctypes.POINTER(wintypes.FILETIME))
kernel.GetProcessTimes.restype = wintypes.BOOL
kernel.CloseHandle.argtypes = (wintypes.HANDLE,)


def cpu_seconds(pid):
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        raise OSError(ctypes.get_last_error(), "OpenProcess " + str(pid))
    try:
        created, exited, system, user = (wintypes.FILETIME() for _ in range(4))
        if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                       ctypes.byref(system), ctypes.byref(user)):
            raise OSError(ctypes.get_last_error(), "GetProcessTimes")
        return (((system.dwHighDateTime << 32) + system.dwLowDateTime) +
                ((user.dwHighDateTime << 32) + user.dwLowDateTime)) / 1e7
    finally:
        kernel.CloseHandle(handle)


def parse_line(line):
    values = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=(\S+)", line))
    if "capturedFrames" not in values:
        return None
    values["time"] = line[:23]
    return values


def number(value):
    return float(value.replace(",", "."))


initial = LOG.read_text(encoding="utf-8-sig", errors="replace").splitlines()
assert "mpvPid=" + str(PLAYER) in initial[0], initial[0]
samples = []
previous = None
with (AUDIT / "observacao-30s.csv").open("w", newline="", encoding="utf-8") as file:
    writer = csv.DictWriter(file, fieldnames=("sample_time", "relay_log_time", "runner_cpu_seconds",
                            "player_cpu_seconds", "runner_cpu_percent_one_core", "player_cpu_percent_one_core") + FIELDS)
    writer.writeheader()
    while True:
        now = datetime.now()
        lines = LOG.read_text(encoding="utf-8-sig", errors="replace").splitlines()
        assert "mpvPid=" + str(PLAYER) in lines[0], "Audio relay restarted during observation"
        metrics = next(value for line in reversed(lines) if (value := parse_line(line)))
        runner_cpu, player_cpu = cpu_seconds(RUNNER), cpu_seconds(PLAYER)
        row = {"sample_time": now.isoformat(timespec="milliseconds"), "relay_log_time": metrics["time"],
               "runner_cpu_seconds": runner_cpu, "player_cpu_seconds": player_cpu}
        if previous:
            elapsed = (now - previous[0]).total_seconds()
            row["runner_cpu_percent_one_core"] = 100 * (runner_cpu - previous[1]) / elapsed
            row["player_cpu_percent_one_core"] = 100 * (player_cpu - previous[2]) / elapsed
        previous = now, runner_cpu, player_cpu
        row.update({key: metrics.get(key) for key in FIELDS})
        writer.writerow(row)
        file.flush()
        samples.append(row)
        print(now.strftime("%H:%M:%S"), "queue=" + metrics["queueMs"],
              "drop=" + metrics["droppedFrames"], "padding=" + metrics["paddingSilenceFrames"],
              "pool=" + metrics.get("poolAllocations", "?"), flush=True)
        if now >= DEADLINE:
            break
        time.sleep(min(30, max(0, (DEADLINE - now).total_seconds())))

log_copy = AUDIT / "relay-apos-calibracao.log"
log_copy.write_text("\n".join(lines) + "\n", encoding="utf-8")
all_metrics = [value for line in lines if (value := parse_line(line))]
start = datetime.strptime(lines[0][:23], "%Y-%m-%d %H:%M:%S.%f")
end = datetime.strptime(all_metrics[-1]["time"], "%Y-%m-%d %H:%M:%S.%f")
first_two = [number(row["queueMs"]) for row in all_metrics
             if datetime.strptime(row["time"], "%Y-%m-%d %H:%M:%S.%f") < start + timedelta(minutes=2)]
last_two = [number(row["queueMs"]) for row in all_metrics
            if datetime.strptime(row["time"], "%Y-%m-%d %H:%M:%S.%f") >= end - timedelta(minutes=2)]
cpu_runner = [row["runner_cpu_percent_one_core"] for row in samples if "runner_cpu_percent_one_core" in row]
cpu_player = [row["player_cpu_percent_one_core"] for row in samples if "player_cpu_percent_one_core" in row]
summary = {"start_time": start.isoformat(), "end_time": end.isoformat(),
           "elapsed_seconds": (end - start).total_seconds(), "process_ids": {"runner": RUNNER, "player": PLAYER},
           "metric_lines": len(all_metrics), "first_2min_queue_median_ms": statistics.median(first_two),
           "last_2min_queue_median_ms": statistics.median(last_two),
           "first_2min_queue_mean_ms": statistics.mean(first_two),
           "last_2min_queue_mean_ms": statistics.mean(last_two),
           "last_metrics": all_metrics[-1],
           "runner_cpu_mean_percent_one_core": statistics.mean(cpu_runner),
           "player_cpu_mean_percent_one_core": statistics.mean(cpu_player),
           "runner_cpu_max_percent_one_core": max(cpu_runner),
           "player_cpu_max_percent_one_core": max(cpu_player),
           "mpv_device_underruns": (ROOT / "audio-sistema.log.mpv.log").read_text(
                 encoding="utf-8-sig", errors="replace").count("Audio device underrun detected.")}
(AUDIT / "observacao-resumo.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, indent=2), flush=True)
