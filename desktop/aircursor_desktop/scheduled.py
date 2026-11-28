"""Persistent local AI prompts owned by the signed-in AirCursor workspace."""

from datetime import datetime, timedelta
import time
from uuid import uuid4

from PySide6.QtCore import QDateTime, QObject, QTimer, Qt, Signal
from PySide6.QtWidgets import (
    QCheckBox, QDateTimeEdit, QDialog, QDialogButtonBox, QFormLayout, QLineEdit,
    QListWidget, QPlainTextEdit, QVBoxLayout,
)

from .navigation import item
from .widgets import Workers, button, confirm, label, row


def next_daily(due, now):
    original = datetime.fromtimestamp(due)
    next_run = datetime.combine(datetime.fromtimestamp(now).date(), original.time())
    if next_run.timestamp() <= now:
        next_run += timedelta(days=1)
    return next_run.timestamp()


class Scheduler(QObject):
    changed = Signal()
    result_ready = Signal(object)
    error = Signal(str)

    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.s = settings
        self.active = False
        self.running = None
        self.busy = lambda: False
        self.work = Workers(self)
        self.timer = QTimer(self, singleShot=True)
        self.timer.timeout.connect(self.run_due)

    def tasks(self):
        return self.s.get("ai_schedules", [])

    def update(self, tasks, **extras):
        self.s.set(ai_schedules=tasks, **extras)
        self.changed.emit()
        self.arm()

    def activate(self, active):
        self.active = active
        self.arm()

    def arm(self):
        self.timer.stop()
        if not self.active or self.running:
            return
        due = [task["due"] for task in self.tasks() if task.get("enabled")]
        if due:
            self.timer.start(max(0, min(60_000, int((min(due) - time.time()) * 1000) + 1)))

    def add(self, title, prompt, due, daily, project):
        from .intelligence import lightweight_messages

        lightweight_messages([{"role": "user", "content": prompt}])
        task = dict(id=uuid4().hex, title=title, prompt=prompt, due=due, daily=daily, project=project,
                    enabled=True, status="Scheduled")
        self.update([*self.tasks(), task])
        return task["id"]

    def modify(self, identifier, remove=False):
        if identifier == self.running:
            raise ValueError("Wait for this prompt to finish.")
        tasks = []
        for task in self.tasks():
            if task["id"] == identifier:
                if remove:
                    continue
                enabled = not task["enabled"]
                task = dict(task, enabled=enabled, status="Scheduled" if enabled else "Paused")
            tasks.append(task)
        self.update(tasks)

    def run_due(self):
        if not self.active or self.running:
            return
        if self.busy():
            self.timer.start(5000)
            return
        now = time.time()
        ready = [task for task in self.tasks() if task["enabled"] and task["due"] <= now]
        if not ready:
            self.arm()
            return
        task = min(ready, key=lambda entry: entry["due"])
        identifier = self.running = task["id"]

        def replace(**changes):
            return [dict(entry, **changes) if entry["id"] == identifier else entry for entry in self.tasks()]

        # Persist the next occurrence before sending. A crash cannot repeat this invocation.
        progress = dict(enabled=task["daily"], status="Interrupted if AirCursor closed")
        if task["daily"]:
            progress["due"] = next_daily(task["due"], now)
        try:
            self.update(replace(**progress))
        except OSError as exc:
            self.running = None
            self.error.emit(str(exc))
            self.timer.start(60_000)
            return
        message = {"role": "user", "content": task["prompt"]}

        def execute():
            from .intelligence import lightweight_chat

            if not self.active:
                raise ValueError("AirCursor paused before the prompt could run.")
            answer, _ = lightweight_chat([message])
            if not isinstance(answer, str) or not answer.strip():
                raise ValueError("Ollama returned no text.")
            return answer

        def finish(status, result=None):
            extras = {}
            if result is not None:
                extras["conversations"] = [*self.s.get("conversations", []), result]
            try:
                self.update(replace(status=status), **extras)
                if result is not None:
                    self.result_ready.emit(result)
            except OSError as exc:
                self.error.emit(str(exc))
            finally:
                self.running = None
                self.changed.emit()
                self.arm()

        def done(answer):
            from .intelligence import ENDPOINTS

            projects = self.s.get("ai_projects", [])
            project = task["project"] if any(p["id"] == task["project"] for p in projects) else ""
            result = dict(title=task["title"], named=True, project=project, endpoint=ENDPOINTS["Ollama"],
                          messages=[message, {"role": "assistant", "content": answer}])
            finish("Completed", result)

        self.work.run(execute, done, lambda reason: finish("Failed · " + reason[:160]))


def new_schedule(parent, scheduler, text, project):
    dialog = QDialog(parent)
    dialog.setWindowTitle("Schedule local AI prompt")
    layout = QFormLayout(dialog)
    title = QLineEdit()
    title.setMaxLength(80)
    prompt = QPlainTextEdit(text)
    when = QDateTimeEdit(QDateTime.currentDateTime().addSecs(600))
    when.setCalendarPopup(True)
    when.setDisplayFormat("yyyy-MM-dd HH:mm")
    daily = QCheckBox("Repeat daily at this time")
    layout.addRow("Name", title)
    layout.addRow("Prompt", prompt)
    layout.addRow("Run at", when)
    layout.addRow(daily)
    status = label("")
    layout.addRow(status)
    buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
    layout.addRow(buttons)

    def save():
        name, text = title.text().strip(), prompt.toPlainText().strip()
        due = when.dateTime().toSecsSinceEpoch()
        if not name or not text or due <= time.time():
            status.setText("Enter a name, a prompt, and a future time.")
            return
        try:
            scheduler.add(name, text, due, daily.isChecked(), project)
        except (ValueError, OSError) as exc:
            status.setText(str(exc))
            return
        dialog.accept()

    buttons.accepted.connect(save)
    buttons.rejected.connect(dialog.reject)
    dialog.exec()
    dialog.deleteLater()


def show_schedules(parent, scheduler, draft, project):
    dialog = QDialog(parent)
    dialog.setWindowTitle("Scheduled prompts")
    dialog.resize(545, 365)
    layout = QVBoxLayout(dialog)
    layout.addWidget(label("Uses local Qwen3 in Ollama while AirCursor is open and Windows is unlocked."))
    entries = QListWidget()
    layout.addWidget(entries)
    status = label("")
    layout.addWidget(status)

    def refresh():
        current = entries.currentItem()
        identifier = current.data(Qt.ItemDataRole.UserRole) if current else None
        entries.clear()
        for task in scheduler.tasks():
            state = "Running" if task["id"] == scheduler.running else task["status"]
            stamp = datetime.fromtimestamp(task["due"]).strftime("%d %b · %H:%M")
            entry = item(entries, f"{task['title']}   ·   {stamp}   ·   {state}", task["id"])
            if task["id"] == identifier:
                entries.setCurrentItem(entry)

    def modify(remove=False):
        current = entries.currentItem()
        if current is None or (remove and not confirm(dialog, "Remove schedule", "Remove this prompt schedule?")):
            return
        try:
            scheduler.modify(current.data(Qt.ItemDataRole.UserRole), remove=remove)
            status.clear()
        except (OSError, ValueError) as exc:
            status.setText(str(exc))

    row(layout, button("New schedule…", lambda: new_schedule(dialog, scheduler, draft, project)),
        button("Pause / enable", modify), button("Remove…", lambda: modify(True)), button("Done", dialog.accept))
    refresh()
    scheduler.changed.connect(refresh)
    try:
        dialog.exec()
    finally:
        scheduler.changed.disconnect(refresh)
        dialog.deleteLater()
