# Contributing

## The rules

Every line has to earn its place. A change that adds a library, a permission, a web address or a background
service needs a strong reason, and `build.sh` will block most of them anyway. The app stays under 1,000 lines.

## Adding a list for your TV

A list is a folder, `models/<platform>/`, with two files: `disable.txt` (what to switch off) and `keep.txt` (what
`debloat` must never touch). `models/BRAVIA_VH21/` is the worked example: every line says what the package is
and why it's off.

1. **Find your platform and your packages:**
   ```sh
   export TV=<your TV's IP address>
   ./tv.sh connect
   adb -s $TV:5555 shell getprop ro.product.device          # the folder name, e.g. BRAVIA_VH21
   adb -s $TV:5555 shell pm list packages | sort > packages.txt
   ```
2. **Start from the closest existing list.** Keep only packages your TV actually has. Only switch off what tracks
   you, shows ads, or runs in the background for features the TV can't use. Apps that just sit unused don't
   belong on the list, because Frame already hides them. Put anything the TV needs into `keep.txt`: the screensaver,
   tuners and inputs, casting, the remote's app buttons, updates, and the Play Store.
3. **Run the restart test.** Some packages break other things only at start-up:
   ```sh
   ./tv.sh debloat
   sleep 20                                        # Android saves package changes after a short delay
   adb -s $TV:5555 shell svc power reboot          # a proper restart; plain `adb reboot` can lose recent changes
   # when the TV is back:
   adb -s $TV:5555 shell logcat -b crash -d | grep "Process:"
   ```
   Compare with the same test after `./tv.sh restore`. If a crash appears only with your list, find the package by
   halving the list and restarting until one is left, then move it to `keep.txt` with a note. That's how
   `com.sony.dtv.seeds.iot` got there.
4. **Open a pull request** with your TV's model number, region, Android version, and the result of the restart test.

## Changing the app

`./build.sh` must pass. It runs every check CI runs. Test on a real TV: install with `./tv.sh install`, and make
sure the remote can reach everything you added.
