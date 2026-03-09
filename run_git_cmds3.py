import subprocess

def run_cmd(args):
    res = subprocess.run(args, capture_output=True, text=True)
    print(f"[{' '.join(args)}] Exit: {res.returncode}")
    return res

with open("git_setup_log3.txt", "w", encoding="utf-8") as f:
    import sys
    sys.stdout = f
    
    # force remove cached
    run_cmd(["git", "rm", "-r", "--cached", "-f", "."])
    
    # re-add based on gitignore
    run_cmd(["git", "add", "."])
    
    # commit changes
    res = run_cmd(["git", "commit", "-m", "Clean up unnecessary files from repo"])
    if res.returncode == 0:
        # push
        run_cmd(["git", "push", "origin", "master"])
