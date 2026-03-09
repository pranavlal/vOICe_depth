import subprocess
with open("git_test_output.txt", "w", encoding="utf-8") as f:
    try:
        res = subprocess.run(["git", "--version"], capture_output=True, text=True)
        f.write("STDOUT:\n" + res.stdout + "\nSTDERR:\n" + res.stderr)
    except Exception as e:
        f.write("ERROR: " + str(e))
