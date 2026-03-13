import subprocess

def run_cmd(args):
    try:
        res = subprocess.run(args, capture_output=True, text=True)
        print(f"[{' '.join(args)}] Exit: {res.returncode}")
        if res.stdout.strip():
            print("STDOUT: " + res.stdout.strip())
        if res.stderr.strip():
            print("STDERR: " + res.stderr.strip())
        return res.returncode
    except Exception as e:
        print(f"[{' '.join(args)}] ERROR: {e}")
        return -1

with open("git_setup_log2.txt", "w", encoding="utf-8") as f:
    import sys
    sys.stdout = f
    
    # 1. git rm -r --cached .
    run_cmd(["git", "rm", "-r", "--cached", "."])
    
    # 2. git add .
    run_cmd(["git", "add", "."])
    
    # 3. git commit
    run_cmd(["git", "commit", "-m", "Initial commit from agent"])
    
    # 4. create gh repo
    gh_path = r"C:\Program Files\GitHub CLI\gh.exe"
    run_cmd([gh_path, "repo", "create", "vOICe_depth", "--public", "--source=.", "--remote=origin", "--push"])
