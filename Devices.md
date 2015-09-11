In principle, this program should work on any device if the device is based on a Qualcomm chip, either old (msm72xx) or a new one (snapdragons like qsd8x50/msm8x55/msm7x30).

However, many device manufacturers do not provide necessary support for 2-way recording in their ROMs.

For old devices (and for newer ones based on msm7227 widely used now) it is readily checked: you must have files named "voc**" in /dev directory. If you haven't, you've got to ask somebody.  If your device isn't HTC Tattoo, you almost surely have to apply the patch in branches/snapdragon/kernel/msm7k because otherwise your device won't support auto-answering.**

For newer devices: almost surely, you have to install an updated kernel. Please look at the xda forum, the chances are the required patch already exists (my patches for popular devices are in branches/snapdragon).