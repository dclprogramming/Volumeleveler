# Volume Leveler (Google TV / Android TV)

Listens through a mic (auto-picks Built-in > USB > Bluetooth, or choose manually) to dynamically nudge the TV's volume up & down, keeping the rooms overall noise level near a set target. The apps primary function is to fix movies that go from normal volume to blasting you into another dimension to whispering by stabilizing and leveling audio output.   

Volume Control Compatibility:
| Output / Setup                          | Works?      | Notes                                    |
|-----------------------------------------|-------------|------------------------------------------|
| Google TV remote + TV speakers          | Yes         | Best case                                |
| ARC / eARC (TV controls volume)         | Usually Yes | Most common setup                        |
| ARC / eARC (Receiver controls volume)   | Often No    | Volume is handled by the AVR via CEC     |
| Optical / Digital out to receiver       | Usually No  | Volume is typically fixed / pass-through |

Details:
- The app adjusts Android’s `STREAM_MUSIC` volume.
- It detects fixed-volume outputs (`AudioManager.isVolumeFixed`) and will stop adjusting while showing the message: `Volume is fixed on this output (cannot adjust)`.

ARC/eARC:
- Works when the Google TV is set to control the volume (normal “TV speakers” / System audio control mode).
- Often does nothing when the receiver is set as the volume controller via CEC (Android usually locks volume at maximum and forwards commands to the AVR).

Digital audio out (optical/SPDIF)
- In almost all cases Android treats this as a fixed-volume / pass-through output, so the app cannot change the volume.

Installation Instructions:
1. Install the Downloader app on your TV.
2. In the Downloader app on the TV, enter the following as a favorite:
   https://github.com/<user><repo>releases/latest/download/app-debug.apk
3. Navigate to favorites and click on the link to install.
4. Allow "Install unknown apps" for Downloader when prompted, then install.
5. Open the Volume Leveler app from your home screen apps.
6. Click Allow on all prompts to grant access to the system microphone.
7. Follow instructions at the bottom of the app display.

* Tested on Hisense U8K connected to Yamaha RXV-475 via HDMI/Arc port
