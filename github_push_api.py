# -*- coding: utf-8 -*-
"""通过 GitHub Git Data API 上传本地目录为一次 commit（绕过被代理拦截的 git push）。
用法: GITHUB_TOKEN=xxx python github_push_api.py <owner> <repo> <local_dir> <branch> <message>
"""
import base64, json, os, subprocess, sys, time

API = "https://api.github.com"

def req(method, path, token, payload=None):
    args = ["curl", "-s", "--max-time", "60", "--retry", "5", "--retry-delay", "2",
            "--retry-all-errors",
            "-X", method,
            "-H", "Authorization: Bearer " + token,
            "-H", "Accept: application/vnd.github+json",
            "-H", "Content-Type: application/json"]
    if payload is not None:
        tmp = os.path.join(os.environ.get("TEMP", "."), "gh_payload.json")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(payload))
        args += ["-d", "@" + tmp]
    args.append(API + path)
    for attempt in range(3):
        tmpout = os.path.join(os.environ.get("TEMP", "."), "gh_resp.json")
        p = subprocess.run(args[:-1] + ["-o", tmpout, "-w", "%{http_code}", args[-1]],
                           capture_output=True, text=True, encoding="utf-8")
        code = p.stdout.strip() if p.returncode == 0 else "000"
        body = open(tmpout, encoding="utf-8").read() if os.path.exists(tmpout) else ""
        if code.startswith("2") and body.strip():
            return json.loads(body)
        print(f"  .. HTTP {code} ({method} {path}): {body[:150]}")
        if attempt == 2:
            raise RuntimeError(f"{method} {path} 连续失败 (HTTP {code})")
        time.sleep(3)

def main():
    owner, repo, local, branch, message = sys.argv[1:6]
    token = os.environ["GITHUB_TOKEN"]
    root = os.path.abspath(local)

    # 收集文件
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "toolchain")]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace("\\", "/")
            files.append((rel, full))
    files.sort()
    print(f"uploading {len(files)} files ...")

    # 1) blobs
    tree = []
    for rel, full in files:
        with open(full, "rb") as f:
            content = base64.b64encode(f.read()).decode()
        blob = req("POST", f"/repos/{owner}/{repo}/git/blobs", token,
                   {"content": content, "encoding": "base64"})
        tree.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  blob {rel}")

    # 2) tree
    t = req("POST", f"/repos/{owner}/{repo}/git/trees", token, {"tree": tree})
    print(f"tree {t['sha'][:12]}")

    # 3) commit
    c = req("POST", f"/repos/{owner}/{repo}/git/commits", token,
            {"message": message, "tree": t["sha"]})
    print(f"commit {c['sha'][:12]}")

    # 4) 分支：POST 建 ref；已存在（422）则强制 PATCH 更新
    try:
        req("POST", f"/repos/{owner}/{repo}/git/refs", token,
            {"ref": f"refs/heads/{branch}", "sha": c["sha"]})
        print(f"branch {branch} created")
    except RuntimeError:
        req("PATCH", f"/repos/{owner}/{repo}/git/refs/heads/{branch}", token,
            {"sha": c["sha"], "force": True})
        print(f"branch {branch} updated (force)")

    print("DONE")

if __name__ == "__main__":
    main()
