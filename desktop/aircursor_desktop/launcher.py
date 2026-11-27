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
    """Read classic Start menu and Recent links independently of Windows app registration."""
    items = []
    for base in ("APPDATA", "PROGRAMDATA"):
        if os.environ.get(base):
            root = Path(os.environ[base]) / "Microsoft/Windows/Start Menu/Programs"
            items.extend((p.stem, str(p)) for p in root.rglob("*.lnk"))
    if os.environ.get("APPDATA"):
        recent = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Recent"
        items.extend((p.stem, str(p)) for p in recent.glob("*.lnk"))
    return sorted(set(items), key=lambda pair: pair[0].casefold())


def indexed_files(query):
    """Ask the existing Windows Search catalog for a bounded set of matching filesystem items."""
    import json

    term = query.strip()
    if os.name != "nt" or not 2 <= len(term) <= 100:
        return []
    # The filter is data, transmitted in the process environment. Disallow wildcard expressions
    # and escape apostrophes in the SQL literal independently of PowerShell quoting.
    if any(ch in "%_[]\x00" or ord(ch) < 32 for ch in term):
        return []
    script = """
    $ErrorActionPreference='Stop'
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $cn = New-Object -ComObject ADODB.Connection
    $cn.ConnectionTimeout = 3
    $cn.CommandTimeout = 4
    $cn.Open("Provider=Search.CollatorDSO;Extended Properties='Application=Windows';")
    try {
      $q = $env:AIRCURSOR_FIND.Replace("'", "''")
      $rows = $cn.Execute("SELECT TOP 16 System.ItemNameDisplay, System.ItemPathDisplay FROM SystemIndex WHERE System.FileName LIKE '%$q%'")
      $output = @()
      while (-not $rows.EOF) {
        $output += @{ name = [string]$rows.Fields.Item(0).Value; path = [string]$rows.Fields.Item(1).Value }
        $rows.MoveNext()
      }
      ConvertTo-Json -InputObject $output -Compress
    } finally { $cn.Close() }
    """
    from . import powershell
    result = powershell.run(script, timeout=8, variables={"AIRCURSOR_FIND": term})
    result.check_returncode()
    return [(r["name"], r["path"]) for r in json.loads(result.stdout.decode("utf-8-sig"))
            if r.get("name") and Path(r.get("path") or "").is_absolute()]
