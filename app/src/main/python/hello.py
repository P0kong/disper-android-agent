"""最小的 Python 模块：验证 Chaquopy 打包与调用链。

阶段 1 只新增 interpreter_summary()，它给诊断页提供一行可读的运行时信息。
这里没有任何 eval/exec、没有动态导入外部包，也没有读取用户文件的入口。
"""


def greet(name: str) -> str:
    return f"Hello from Python {__import__('sys').version.split()[0]} | {name}"


def interpreter_info() -> dict:
    import platform
    import sys

    return {
        "implementation": platform.python_implementation(),
        "version": sys.version.split()[0],
        "executable": sys.executable,
    }


def interpreter_summary() -> str:
    """返回一行摘要字符串，供诊断页显示（不抛出异常，失败时返回说明文本）。"""
    try:
        import platform
        import sys

        return "{} {} | platform={} | executable={}".format(
            platform.python_implementation(),
            sys.version.split()[0],
            platform.platform(),
            sys.executable,
        )
    except Exception as error:  # noqa: BLE001 - 诊断页需要看到真实原因
        return "interpreter_summary failed: {}: {}".format(type(error).__name__, error)
