"""Local model selection of registered Windows apps. Model output never becomes a command."""
import json

from .applications import registered_apps
from .intelligence import LIGHT_MODEL, ollama_call


def plan_launch(task):
    apps = registered_apps()
    if not apps:
        raise ValueError("Windows reported no Start menu apps.")
    from collections import Counter

    counts = Counter(name for name, _ in apps)
    choices = {(name if counts[name] == 1 else f"{name} ({target[9:]})"): (name, target)
               for name, target in apps}
    names = list(choices)
    schema = {"type": "object", "properties": {"apps": {"type": "array", "minItems": 1, "maxItems": 3,
              "items": {"type": "string", "enum": [*names, "No match"]}}}, "required": ["apps"]}
    result = ollama_call("/api/chat", {
        "model": LIGHT_MODEL, "stream": False, "think": False, "keep_alive": 0,
        "format": schema, "options": {"num_ctx": 4096, "num_predict": 128, "temperature": 0},
        "messages": [
            {"role": "system", "content": "You are the Windows app picker. Return the names of installed "
             "apps needed for the task. Pick No match if none are suitable. Available names: " + json.dumps(names)},
            {"role": "user", "content": task},
        ],
    })
    selected = json.loads(result["message"]["content"])["apps"]
    if selected == ["No match"]:
        return []
    if (not isinstance(selected, list) or len(selected) > 3
            or any(not isinstance(name, str) or name not in choices for name in selected)):
        raise ValueError("The model returned an invalid app selection.")
    return [choices[name] for name in dict.fromkeys(selected)]
