"""Export edited copies of local images and videos without replacing the source or other files."""

import math
import os
import tempfile
from contextlib import contextmanager
from pathlib import Path

from PySide6.QtCore import QProcess, Qt
from PySide6.QtGui import QImageReader, QTransform
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QProgressDialog,
    QSpinBox,
)

from .applications import find_tool


@contextmanager
def new_export(destination):
    dest = Path(destination)
    if dest.exists():
        raise FileExistsError(f"Choose a new output name. {dest.name} already exists.")
    with tempfile.TemporaryDirectory(prefix=".aircursor-", dir=dest.parent) as folder:
        staging = Path(folder) / dest.name
        yield staging
        if os.name == "nt":
            os.rename(staging, dest)  # Win32 refuses an existing target, including a racing writer.
        else:
            os.link(staging, dest)


def export_photo(source, destination, rotation, max_edge):
    reader = QImageReader(str(source))
    reader.setAutoTransform(True)
    image = reader.read()
    if image.isNull():
        raise ValueError(reader.errorString())
    image = image.transformed(QTransform().rotate(rotation), Qt.TransformationMode.SmoothTransformation)
    if max_edge and max(image.width(), image.height()) > max_edge:
        image = image.scaled(
            max_edge,
            max_edge,
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
    with new_export(destination) as temp:
        if not image.save(str(temp), "PNG"):
            raise OSError("Could not write the edited image.")


def trim_args(source, start, end, output):
    if not (math.isfinite(start) and math.isfinite(end) and 0 <= start < end):
        raise ValueError("End time must be later than start time.")
    return [
        "-hide_banner",
        "-abort_on",
        "empty_output",
        "-loglevel",
        "error",
        "-nostdin",
        "-n",
        "-ss",
        str(start),
        "-i",
        str(source),
        "-t",
        str(end - start),
        "-map",
        "0:v:0",
        "-map",
        "0:a:0?",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-vf",
        "pad=ceil(iw/2)*2:ceil(ih/2)*2",
        "-c:a",
        "aac",
        "-movflags",
        "+faststart",
        str(output),
    ]


def parameters(parent, title, fields):
    dialog = QDialog(parent)
    dialog.setWindowTitle(title)
    form = QFormLayout(dialog)
    for name, field in fields:
        form.addRow(name, field)
    controls = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
    controls.accepted.connect(dialog.accept)
    controls.rejected.connect(dialog.reject)
    form.addRow(controls)
    if dialog.exec() == QDialog.DialogCode.Accepted:
        return [
            field.currentData() if isinstance(field, QComboBox) else field.value() for _, field in fields
        ]
    return None


def destination(parent, path, extension):
    name, _ = QFileDialog.getSaveFileName(
        parent,
        "Export edited copy",
        str(path.with_name(path.stem + "-edited." + extension)),
        f"{extension.upper()} (*.{extension})",
    )
    if name and not name.lower().endswith("." + extension):
        name += "." + extension
    return name


def edit_photo(files, path):
    angle = QComboBox()
    for text, degrees in [
        ("Original orientation", 0),
        ("90° clockwise", 90),
        ("180°", 180),
        ("90° counterclockwise", 270),
    ]:
        angle.addItem(text, degrees)
    size = QSpinBox()
    size.setRange(0, 65535)
    size.setSpecialValueText("Original size")
    size.setSuffix(" px")
    values = parameters(files, "Edit photo", [("Rotate", angle), ("Longest edge", size)])
    if values:
        name = destination(files, path, "png")
        degrees, edge = values
        if name:
            files.task(lambda: export_photo(path, name, degrees, edge))


def trim_video(files, path):
    exe = find_tool("ffmpeg")
    if not exe:
        files.status.setText("Install FFmpeg from Air Store → Utilities, then try again.")
        return
    times = [QDoubleSpinBox(), QDoubleSpinBox()]
    for box in times:
        box.setRange(0, 604800)
        box.setDecimals(3)
        box.setSuffix(" s")
    times[1].setValue(10)
    values = parameters(files, "Trim video", list(zip(("Start", "End"), times)))
    if not values:
        return
    start, end = values
    if end <= start:
        files.status.setText("End time must be later than start time.")
        return
    name = destination(files, path, "mp4")
    if not name:
        return
    progress = QProgressDialog("Exporting video with FFmpeg…", "Cancel", 0, 0, files)
    progress.setWindowTitle("Trim video")
    progress.setMinimumDuration(0)
    process = QProcess(progress)
    progress.canceled.connect(process.kill)
    try:
        with new_export(name) as output:
            process.start(exe, trim_args(path, start, end, output))
            process.finished.connect(progress.accept)
            process.errorOccurred.connect(progress.reject)
            ok = progress.exec()
            if process.state() != QProcess.ProcessState.NotRunning:
                process.kill()
                process.waitForFinished(3000)
            if (
                progress.wasCanceled()
                or not ok
                or process.exitStatus() != QProcess.ExitStatus.NormalExit
                or process.exitCode()
            ):
                reason = bytes(process.readAllStandardError()).decode("utf-8", "replace").strip()
                raise ValueError(reason[-1000:] or "Video export stopped.")
        files.status.setText(f"Exported {name}")
    except (ValueError, OSError) as exc:
        files.status.setText(str(exc))
    finally:
        progress.deleteLater()
