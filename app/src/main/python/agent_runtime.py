import contextlib
import io
import json
import os
import runpy
import sys
import traceback


def _workspace_package_dir(cwd: str) -> str:
    package_dir = os.path.join(cwd, ".python-packages")
    os.makedirs(package_dir, exist_ok=True)
    if package_dir not in sys.path:
        sys.path.insert(0, package_dir)
    return package_dir


def execute(code: str, cwd: str, timeout: int = 60):
    os.chdir(cwd)
    _workspace_package_dir(cwd)
    stdout = io.StringIO()
    stderr = io.StringIO()
    namespace = {
        "__name__": "__agent_script__",
        "__file__": "<agent>",
        "os": os,
    }
    try:
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exec(compile(code, "<agent>", "exec"), namespace, namespace)
        return json.dumps({"ok": True, "exit_code": 0, "stdout": stdout.getvalue(), "stderr": stderr.getvalue()})
    except BaseException as exc:
        traceback.print_exc(file=stderr)
        return json.dumps({"ok": False, "exit_code": 1, "stdout": stdout.getvalue(), "stderr": stderr.getvalue(), "error": str(exc)})


def run_pip(args_json: str, cwd: str, timeout: int = 600):
    """Run pip in-process, installing packages into this workspace only."""
    os.chdir(cwd)
    package_dir = _workspace_package_dir(cwd)
    args = json.loads(args_json)
    if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
        return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": "pip arguments must be a list of strings"})
    if not args:
        args = ["--help"]

    command = args[0].lower()
    if command == "install":
        options = {"--target", "-t", "--user", "--prefix", "--root"}
        if any(arg.split("=", 1)[0] in options for arg in args[1:]):
            return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": "Packages are installed only into the private workspace"})
        if any(arg in ("--no-binary", "--only-binary") or arg.startswith(("--no-binary=", "--only-binary=")) for arg in args[1:]):
            return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": "Runtime pip accepts Android-compatible wheels only; binary-selection flags are managed by the app"})
        args = args + [
            "--target", package_dir,
            "--only-binary=:all:",
            "--disable-pip-version-check",
            "--no-warn-script-location",
            "--timeout=30",
            "--retries=1",
        ]
    elif command == "uninstall":
        import re
        from importlib import metadata

        flags = {"-y", "--yes", "-q", "--quiet", "-v", "--verbose"}
        invalid_options = [arg for arg in args[1:] if arg.startswith("-") and arg not in flags]
        if invalid_options:
            return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": f"Unsupported uninstall option: {invalid_options[0]}"})
        requested = [re.sub(r"\[.*$", "", arg) for arg in args[1:] if arg not in flags]
        if not requested:
            return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": "Provide at least one workspace package name to uninstall"})

        canonical = lambda value: re.sub(r"[-_.]+", "-", value).lower()
        installed = list(metadata.distributions(path=[package_dir]))
        removed = []
        missing = []
        for name in requested:
            matches = [dist for dist in installed if canonical(dist.metadata.get("Name", "")) == canonical(name)]
            if not matches:
                missing.append(name)
                continue
            for dist in matches:
                dist_name = dist.metadata.get("Name", name)
                for record in dist.files or ():
                    candidate = os.path.abspath(os.path.join(package_dir, str(record)))
                    if os.path.commonpath((package_dir, candidate)) != package_dir:
                        continue
                    parent = os.path.realpath(os.path.dirname(candidate))
                    if os.path.commonpath((package_dir, parent)) != package_dir:
                        continue
                    if os.path.islink(candidate):
                        os.remove(candidate)
                    elif os.path.isfile(candidate) and os.path.commonpath((package_dir, os.path.realpath(candidate))) == package_dir:
                        os.remove(candidate)
                top_level = dist.read_text("top_level.txt") or ""
                for module_name in top_level.splitlines():
                    for loaded_name in list(sys.modules):
                        if loaded_name == module_name or loaded_name.startswith(module_name + "."):
                            sys.modules.pop(loaded_name, None)
                removed.append(dist_name)
        stdout = "".join(f"Successfully uninstalled {name}\n" for name in removed)
        stderr = "".join(f"WARNING: Skipping {name} as it is not installed in this workspace\n" for name in missing)
        return json.dumps({"ok": bool(removed) and not missing, "exit_code": 0 if removed and not missing else 1, "stdout": stdout, "stderr": stderr})
    elif command not in ("list", "show", "check", "freeze", "--version", "--help", "help"):
        return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": f"Unsupported pip command: {command}"})

    stdout = io.StringIO()
    stderr = io.StringIO()
    try:
        os.environ["PIP_CACHE_DIR"] = os.path.join(cwd, ".pip-cache")
        os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        from pip._internal.cli.main import main as pip_main
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exit_code = pip_main(args)
        return json.dumps({
            "ok": exit_code == 0,
            "exit_code": exit_code,
            "stdout": stdout.getvalue(),
            "stderr": stderr.getvalue(),
        })
    except BaseException as exc:
        traceback.print_exc(file=stderr)
        return json.dumps({
            "ok": False,
            "exit_code": 1,
            "stdout": stdout.getvalue(),
            "stderr": stderr.getvalue(),
            "error": str(exc),
        })


def run_sqlmap(args_json: str, cwd: str, runtime_root: str):
    """Run the sqlmap CLI from the app-bundled runtime pack, preserving raw output."""
    try:
        args = json.loads(args_json)
    except Exception as exc:
        return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": f"Invalid sqlmap arguments: {exc}"})
    if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
        return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": "sqlmap arguments must be a list of strings"})

    source_root = os.path.join(runtime_root, "sqlmap")
    script = os.path.join(source_root, "sqlmap.py")
    if not os.path.isfile(script):
        return json.dumps({"ok": False, "exit_code": 127, "stdout": "", "stderr": "", "error": "Bundled sqlmap runtime is missing. Use runtime.sqlmap.install to unpack it."})

    try:
        os.makedirs(cwd, exist_ok=True)
        cwd = os.path.realpath(cwd)
    except Exception as exc:
        return json.dumps({"ok": False, "exit_code": 2, "stdout": "", "stderr": "", "error": f"Invalid sqlmap working directory: {exc}"})

    stdout = io.StringIO()
    stderr = io.StringIO()
    old_cwd = os.getcwd()
    old_argv = sys.argv
    saved_module_entries = {
        name: module for name, module in list(sys.modules.items())
        if name in ("lib", "thirdparty") or name.startswith(("lib.", "thirdparty."))
    }
    for name in saved_module_entries:
        sys.modules.pop(name, None)
    sys.path.insert(0, source_root)
    sys.argv = [script] + args
    exit_code = 0
    error = None
    try:
        os.chdir(cwd)
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                runpy.run_path(script, run_name="__main__")
            except SystemExit as exc:
                code = exc.code
                exit_code = code if isinstance(code, int) else (0 if code is None else 1)
    except BaseException as exc:
        traceback.print_exc(file=stderr)
        exit_code = 1
        error = str(exc)
    finally:
        sys.argv = old_argv
        os.chdir(old_cwd)
        try:
            sys.path.remove(source_root)
        except ValueError:
            pass
        for name, module in list(sys.modules.items()):
            if (name in ("lib", "thirdparty") or name.startswith(("lib.", "thirdparty."))) and name not in saved_module_entries:
                sys.modules.pop(name, None)
        sys.modules.update(saved_module_entries)

    return json.dumps({
        "ok": exit_code == 0,
        "exit_code": exit_code,
        "stdout": stdout.getvalue(),
        "stderr": stderr.getvalue(),
        "error": error,
    })
