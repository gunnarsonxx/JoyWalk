# JoyWalk — joystick GPS spoofer for Android

A floating joystick that sits on top of any app and "walks" your phone's GPS position around.

## Build & install
1. Install **Android Studio** (free) on your computer.
2. Unzip this folder → Android Studio › **Open** › pick the `JoyWalk` folder. Let Gradle sync
   (first sync downloads the build tools; if it asks to create a Gradle wrapper, say yes).
3. On the phone: Settings › About phone › tap **Build number** 7× to unlock Developer options,
   then turn on **USB debugging**.
4. Plug the phone in, pick it in the device dropdown, press **Run ▶**.
   (Or Build › Build APK(s) and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.)

## One-time phone setup
- Developer options › **Select mock location app** › **JoyWalk**
- Allow JoyWalk to **display over other apps** (button in the app)
- Grant location + notification permissions when asked

## Use
1. Long-press a spot in Google Maps, copy the coordinates, paste into JoyWalk, tap **Start**.
2. The joystick floats over everything. Push it to walk; how far you push sets the pace.
   - **Speed** pill: Walk 1.4 m/s → Jog 3 → Bike 6 → Drive 15
   - **Cruise**: the knob stays where you let go, so you keep moving hands-free
   - Drag the top bar to move the panel
3. Paste new coordinates and tap Start again to teleport.
4. **Stop** (in the app or the notification) gives you your real GPS back. Your last
   position is remembered for next time.

## Notes
- It spoofs the GPS, network, and fused providers, so Google Maps and most apps follow it.
- Android marks mock locations, and some apps (banking, some games) check for that and
  refuse or flag your account. Getting around that needs root and isn't included here.
- Big jumps (teleports) or fast speeds can trip anti-cheat "cooldown" or speed checks in games.
