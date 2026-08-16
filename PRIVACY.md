# What DashCast collects, and where it goes

DashCast is a diagnostic tool for a vehicle. When you send a report, it collects data about the car
and about the device it runs on. This document says what, so you can decide before you tap send.

It describes the behaviour of the current source tree. Earlier builds behaved differently, and the
differences that matter are called out at the end.

## Nothing leaves the car unless you ask

There is no background telemetry, no crash reporting service, no analytics. Every outbound transfer
in this application starts with a button you press. If you never press it, nothing is sent.

## What a bug report contains

A report is assembled on the spot and is, deliberately, a broad snapshot — a narrow one would not let
anyone diagnose a vehicle they cannot touch. It includes:

- **The system log (`logcat`), device-wide.** Not just DashCast's own lines: everything the head unit
  logged in the recent window, from every application. This is the largest part of the report and the
  most revealing. It routinely contains Wi-Fi network names and MAC addresses, account names,
  Bluetooth device names, and lines from the vehicle's own navigation and location services.
- **System state**: running processes, memory, display and window configuration, installed navigation
  and vehicle-related packages, system properties, granted permissions.
- **The application's own journal**, which records what DashCast did and why.
- **What you typed**: the category, the problem, and any details you added.
- **Your Telegram handle**, if you entered one — so you can be contacted when a fix exists. It is
  attached to the report. Earlier versions of the consent dialog claimed the opposite; that text was
  wrong and has been corrected.
- **Screenshots of the cluster and of the main screen**, if the rolling recorder is on. It is on by
  default. These can show a map, a destination, or whatever else was displayed. You are asked, each
  time, whether to attach them, and answering no does not cancel the report.

HUD and cluster diagnostics collect the same kind of material, focused on the instrument cluster.

The APK extraction tool collects copies of manufacturer software from the vehicle. An earlier
version of this page said it does not collect personal data. That was wrong: alongside the
manufacturer packages it writes an inventory of **every** application installed on the head unit —
name, path, version and signer — and a set of vehicle identity properties. A full list of the
applications someone installed is personal data by any reasonable reading of the phrase, and the
vehicle properties name the car. The archives are also large.

## Where it goes

Reports are uploaded to a **private Telegram group whose only members are the project maintainer
and the bot that delivers them** — not the community group linked from the README, which is a
separate place where no diagnostic is ever posted. Archives too large for Telegram go to a
**private Azure container** instead, and large diagnostic pulls may use it as well.

The upload itself passes through a relay that holds the delivery credential, so no car carries one.
The relay forwards and keeps nothing.

Two consequences worth stating plainly:

- **Telegram is a third party.** Anything sent there is stored on their infrastructure, outside the
  maintainer's control, under their terms. Everyone who is a member of that group can read what you
  send.
- **Neither destination is under a retention policy.** There is no automatic deletion today. If you
  want a report removed, ask the maintainer.

Uploads go through a relay whose address is built into the application, so a device does not need
any credential to send a report. That address is not a secret and grants nothing — it can only be
used to send something to the relay — but it does mean the previous sentence on this page, that
nothing is uploaded by default because no channel is configured, is no longer true and has been
removed.

What stands in its place is the question. Nothing is uploaded until you have been asked and have
agreed, once, at your first attempt to send a report; the notice is re-readable and revocable in
Settings. If you refuse, the report is written to the device and offered to the system share sheet,
and you decide what happens to it.

## What is never collected

No contacts, no messages, no media, no browsing history, no continuous location tracking, no
microphone recording outside the voice feature, and no unique advertising identifier. The
application does not have a server that tracks installations.

## What you can do

- **Do not attach screenshots** — answer no when asked.
- **Turn the rolling recorder off** in Settings, and there will be no screenshots to attach.
- **Do not enter a Telegram handle**, and no personal identifier is attached. You will not be
  contacted about the fix.
- **Read the report before sending it.** It is a plain text file and its path is shown to you.
- **Ask for deletion** by contacting the maintainer.

## Third parties in the vehicle

A report is a snapshot of the whole head unit, so it can contain traces of other people who used the
car — a paired phone's name, a passenger's Bluetooth device. Please keep that in mind when reporting
from a shared vehicle.

## What changed, and when

- Until version 1.8.22, the Telegram credentials were compiled into the published APK and could be
  extracted from it by anyone who downloaded a release. They are no longer part of the build.
- Until the same version, the consent dialog stated that your Telegram handle was never shared. That
  was incorrect: it was attached to every report. The text now says what actually happens.
- The system log is still sent in full, without redaction. Reducing it is planned and not done.

## Contact

Open an issue at <https://github.com/Kiroha/byd-dashcast>, or use the private reporting route
described in [SECURITY.md](SECURITY.md) if the matter is sensitive.
