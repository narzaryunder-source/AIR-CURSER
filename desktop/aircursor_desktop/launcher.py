"""Bounded launcher index and calculator. Windows Search owns the filesystem index."""

import ast
import math
import operator
import os
from pathlib import Path


OPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.Mod: operator.mod,
}


def calculate(text):
    if len(text) > 120 or not any(ch in "+-*/%" for ch in text):
        return None

    def number(node):
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            return float(node.value)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
            return (-1 if isinstance(node.op, ast.USub) else 1) * number(node.operand)
        if isinstance(node, ast.BinOp) and type(node.op) in OPS:
            return OPS[type(node.op)](number(node.left), number(node.right))
        raise ValueError

    try:
        result = number(ast.parse(text, mode="eval").body)
        return format(result, ".12g") if math.isfinite(result) else None
    except (ArithmeticError, SyntaxError, ValueError, RecursionError):
        return None


def shortcuts():
    items = []
    for base in ("APPDATA", "PROGRAMDATA"):
        if os.environ.get(base):
            root = Path(os.environ[base]) / "Microsoft/Windows/Start Menu/Programs"
            items.extend((p.stem, str(p)) for p in root.rglob("*.lnk"))
    if os.environ.get("APPDATA"):
        recent = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Recent"
        items.extend((p.stem, str(p)) for p in recent.glob("*.lnk"))
    return sorted(set(items), key=lambda pair: pair[0].casefold())
