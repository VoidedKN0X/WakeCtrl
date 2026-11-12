# Security policy

Thank you for helping keep WakeCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/WakeCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of WakeCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

WakeCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for WakeCtrl to send data off the phone
- Another app controlling WakeCtrl while "Allow other apps" is off, or reaching any other internal component
- The overlay window drawing, capturing touches or reading the screen
- Usage access data (the app on screen) being stored or exposed beyond the activity log line
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
A0:17:21:6D:05:E7:51:1B:E3:12:9F:1A:89:62:8B:B7:2C:31:F9:3F:BF:2E:D2:FE:DD:A4:7A:04:51:A0:44:A0
```
