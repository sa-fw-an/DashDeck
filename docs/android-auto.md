# Using DashDeck with Android Auto

DashDeck is not distributed through Google Play, so Android Auto only shows it after you allow
apps from unknown sources.

## Setup

1. Install the DashDeck APK on your phone (Android 15 or newer).
2. Open **Android Auto** settings on the phone (Settings → Connected devices → Android Auto, or
   the Android Auto app).
3. Scroll to **Version** and tap it ten times, then confirm. This enables developer settings.
4. Open the menu (⋮) → **Developer settings** and turn on **Unknown sources**.
5. Connect to the car. DashDeck appears in the app launcher. You can add it to the launcher bar
   from **Customize launcher** in Android Auto settings.

## Phone screen sharing

- Open DashDeck on the car screen and tap **Start** under **Share phone screen**.
- Approve the share prompt on the phone. Choose **Share entire screen** for the full phone, or a
  single app.
- **Browser** returns to the web view while sharing continues; **Stop sharing** ends it.
- Apps that protect their content (some video and banking apps) show black in the mirror.

### Touch control

Turn on **DashDeck** under Settings → Accessibility → Installed apps on the phone. Touches on the
mirrored image are then replayed on the phone: taps, long presses, scrolling and dragging.
Android may turn the service off when DashDeck is updated from an APK; turn it on again if
touch stops working.

## Continue on car screen

With DashDeck open on the car screen, open **Menu → Continue on car screen** on the phone to send
the current page to the car. Playback on the phone pauses.

## Troubleshooting

**"This can't be accessed on your Android Auto at this time."**
Android Auto only lets apps start on the car display from its own launcher. This appears if
DashDeck was updated while it was open in the car. Tap OK and open DashDeck from the launcher.

**DashDeck is missing from the launcher.**
Check that **Unknown sources** is still on in Android Auto developer settings, then reconnect.

**The launcher shows an old icon.**
Android Auto caches icons per app version. The new icon appears after the next update or
reconnect.

**Pages look too small or too large.**
Change **Settings → Display scale**.

## Testing without a car

Google's Desktop Head Unit (DHU) emulates a car display on a computer:

```bash
sdkmanager "extras;google;auto"
# Phone: Android Auto developer settings → Start head unit server, or connect over USB:
$ANDROID_HOME/extras/google/auto/desktop-head-unit --usb
```

See [Test Android apps for cars](https://developer.android.com/training/cars/testing/dhu).
