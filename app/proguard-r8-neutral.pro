# TEMPORARY — keeps R8 behaviourally identical across the AGP 8 -> AGP 9 jump.
#
# AGP 9 rejects getDefaultProguardFile('proguard-android.txt') because that file carries
# -dontoptimize. Its replacement, proguard-android-optimize.txt, turns the R8 optimizer ON —
# for the first time in this project's history (inlining, class merging, devirtualisation,
# -allowaccessmodification, repackaging). That is a large behavioural change to the shipped
# DEX and it needs its own DL3 + DL5 on-car validation window.
#
# Bundling it with the toolchain jump would mean an on-car regression has two plausible causes
# and cannot be bisected cheaply on a car. So the optimizer is neutralised here and enabled in
# a separate, separately-validated commit.
#
# This is the vendor-sanctioned path: AGP 9's own error text says to "temporarily use
# -dontoptimize in a custom keep rule file while fixing breakages".
#
# DELETE THIS FILE (and its proguardFiles entry) in the commit that enables the optimizer.
-dontoptimize
-dontrepackage
