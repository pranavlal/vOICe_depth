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

with open("git_release_log.txt", "w", encoding="utf-8") as f:
    import sys
    sys.stdout = f
    
    # Check status
    run_cmd(["git", "status"])
    
    # Add files
    run_cmd(["git", "add", "."])
    
    # Commit files
    run_cmd(["git", "commit", "-m", "Prepare release 0.1"])
    
    # Push to main branch
    run_cmd(["git", "push"])
    
    # Create tag
    run_cmd(["git", "tag", "0.1"])
    
    # Push tag
    run_cmd(["git", "push", "origin", "0.1"])
    
    # Check if gh release 0.1 already exists
    exit_code = run_cmd([r"C:\Program Files\GitHub CLI\gh.exe", "release", "view", "0.1"])
    if exit_code != 0:
        # Create GitHub release and upload the zip file as asset
        run_cmd([
            r"C:\Program Files\GitHub CLI\gh.exe", "release", "create", "0.1",
            "--title", "Release 0.1",
            "--notes", "Initial 0.1 release",
            "vOICe_depth_v0.1.zip"
        ])
    else:
        print("Release 0.1 already exists on GitHub.")
