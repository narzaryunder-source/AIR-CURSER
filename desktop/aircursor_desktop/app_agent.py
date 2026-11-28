"""Local model selection of registered Windows apps. Model output never becomes a command."""
import json

from .applications import registered_apps
from .intelligence import LIGHT_MODEL, ollama_call


def plan_launch(task):
    apps = registered_apps()
    if not apps:
        raise ValueError("Windows reported no Start menu apps.")
    schema = {"type": "object", "properties": {"apps": {"type": "array", "maxItems": 3,
              "items": {"type": "integer", "minimum": 0, "maximum": len(apps) - 1}}},
              "required": ["apps"]}
    result = ollama_call("/api/chat", {
        "model": LIGHT_MODEL, "stream": False, "think": False, "keep_alive": 0,
        "format": schema, "options": {"num_predict": 96, "temperature": 0},
        "messages": [
            {"role": "system", "content": "Choose up to three matching installed apps to open for the task. "
             "Use their integer indices. Return an empty array if none fit."},
            {"role": "user", "content": json.dumps({"task": task, "installed": dict(enumerate(a[0] for a in apps))})},
        ],
    })
    ids = json.loads(result["message"]["content"])["apps"]
    if (not isinstance(ids, list) or len(ids) > 3
            or any(type(i) is not int or not 0 <= i < len(apps) for i in ids)):
        raise ValueError("The model returned an invalid app selection.")
    return [apps[i] for i in dict.fromkeys(ids)]
