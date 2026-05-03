import os

path = "app/src/main/java/com/byd/myapp/DiagActivity.java"
with open(path, "r") as f:
    text = f.read()

text = text.replace("dumpsys display 2>/dev/null | grep -E 'mDisplayId|mState|fission|virtual' | head -10", "dumpsys display 2>/dev/null | grep -A 2 -B 2 -E 'mDisplayId|mState|fission|virtual|cluster|qt' | head -20")
text = text.replace("DUBLULU", "DUMMY")
text = text.replace("dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror' | head -10", "dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror|qt|container' | head -25")
	text = text.replace("byd:V xdja:V freedom:V cluster:V\"-", "byd:V xdja:V freedom:V cluster:V dilink:V diag:V qt:V container:V input:V BYD_*:V Qt*:V\"")

with open(path, "w") as f:
    f.write(text)
