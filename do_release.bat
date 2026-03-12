@echo off
echo Starting release process...
git add .
git commit -m "Update for release 0.1"
git push
git tag 0.1
git push origin 0.1
"C:\Program Files\GitHub CLI\gh.exe" release create 0.1 --title "Release 0.1" --notes "Initial 0.1 release" vOICe_depth_v0.1.zip
echo Release process complete.
