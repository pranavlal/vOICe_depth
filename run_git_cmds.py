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

with open("git_setup_log.txt", "w", encoding="utf-8") as f:
    import sys
    sys.stdout = f
    
    # 1. Check gh auth
    run_cmd(["gh", "auth", "status"])
    
    # 2. git init
    run_cmd(["git", "init"])
    
    # 3. git config (set dummy if not set)
    run_cmd(["git", "config", "user.name"])
    if run_cmd(["git", "config", "user.email"]) != 0:
        run_cmd(["git", "config", "user.email", "bot@example.com"])
        run_cmd(["git", "config", "user.name", "Bot"])
    
    # 4. git add
    run_cmd(["git", "add", "."])
    
    # 5. git status
    run_cmd(["git", "status"])
