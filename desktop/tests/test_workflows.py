import json
from zipfile import ZipFile

import pytest

from aircursor_desktop import app_agent
from aircursor_desktop.ai_context import office_context, project_context


def test_local_app_selection_is_bounded(monkeypatch):
    monkeypatch.setattr(app_agent, "registered_apps", lambda: [("Editor", "startapp:editor")])
    def plan(ids):
        monkeypatch.setattr(app_agent, "ollama_call", lambda *args: {"message": {"content": json.dumps({"apps": ids})}})
        return app_agent.plan_launch("Edit code")
    assert plan(["Editor", "Editor"]) == [("Editor", "startapp:editor")]
    assert plan(["No match"]) == []
    for invalid in ([1], [-1], [False], ["cmd.exe"], [0]*4):
        with pytest.raises(ValueError):
            plan(invalid)


def test_office_and_project_sources(tmp_path):
    for filename, member in [("notes.docx", "word/document.xml"), ("deck.pptx", "ppt/slides/slide1.xml")]:
        with ZipFile(tmp_path / filename, "w") as archive:
            archive.writestr(member, '<doc><p><t>Selected text</t></p></doc>')
        assert office_context(tmp_path / filename) == (filename, "Selected text")
    assert "deck.pptx" in project_context(tmp_path)[1]


def test_exact_app_names_do_not_start_model(monkeypatch):
    apps = [("Calculator", "startapp:calc"), ("Photos", "startapp:photos"), ("Editor", "startapp:edit")]
    monkeypatch.setattr(app_agent, "registered_apps", lambda: apps)
    monkeypatch.setattr(app_agent, "ollama_call", lambda *_: pytest.fail("Exact names should resolve locally"))
    assert app_agent.plan_launch("Open Calculator and Photos.") == apps[:2]
    assert app_agent.plan_launch("launch EDITOR") == [apps[2]]
