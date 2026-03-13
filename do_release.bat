@echo off
echo Starting release process...
git add .
git commit -m "Update for release 1.2"
git push
git tag 1.2
git push origin 1.2
"C:\Program Files\GitHub CLI\gh.exe" release create 1.2 --title "Release 1.2" --notes "vOICe Depth Release 1.2" vOICe_depth_v1.2.zip
echo Release process complete.
