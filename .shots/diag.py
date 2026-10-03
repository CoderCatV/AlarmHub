import os, subprocess, sys
env = dict(os.environ, CDP_PORT="9223")
p = subprocess.run(["node", "tools/cdp-probe.js", "--eval", "JSON.stringify({t:1})"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=r"D:\Code\DeepSeek\alarm-clock", env=env)
print("returncode:", p.returncode)
print("STDOUT:", repr(p.stdout[:400]))
print("STDERR:", repr(p.stderr[:400]))
print("CDP_PORT in env:", env.get("CDP_PORT"))
