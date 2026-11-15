Robot profiles
==============

A profile stores **a connection and a set of tabs**: domain, node name, peers, Discovery Server,
foxglove_bridge, Zenoh router, interfaces, service format and every tab with its settings. Switch
between robots with one tap.

* **Save as…** saves the current connection and tabs (names up to 32 characters).
* **Load…** connects with the profile and replaces your tabs. The same list lets you **Delete** a
  profile.

In the cloud
------------

Profiles always stay on the device. If you link an account on Home (:doc:`../getting-started/account`),
they are also synced with it, so they appear on all your phones and PCs. If the same profile was
changed on two devices, the most recent change wins. **Sync now** forces a sync.

Without an internet connection (for example, connected only to the robot's Wi-Fi) you can still save
and delete profiles: they stay on the phone, the app tells you, and the card shows that there are
pending changes. They are uploaded to the cloud on their own as soon as you are back online.

Files
-----

**Export…** saves a profile as a JSON file to share it; **Import…** reads one (a profile with the
same name is replaced).

QR codes
--------

**Share as QR** shows a code for the current setup or a saved profile, with the *full profile* or
the *connection only*. A full profile that doesn't fit in one code is split into several that
change by themselves: keep the other phone's camera on them. **Scan QR** reads them with the Google
Play services scanner: a connection-only code asks *Connect to…?* and keeps your tabs.

On the PC
---------

**Configuration → Robot profiles** has **Save profile**, **Load profile** (with a QR and a delete
button next to each profile), **QR** (the current setup, to scan it with the phone; a full profile
in several parts has arrows to go through them) and **Import…**, which reads a profile's JSON file
or a picture (PNG or JPEG) of its QR code. Profiles sync with your account as on the phone.
