# SecureMdmPoc

Custom Device Policy Controller (DPC) Android application for UIDAI's Secure MDM proof-of-concept. Runs as **Device Owner** (not AMAPI/EMM-managed) on fully-managed kiosk devices, provisioned via QR, and remotely controlled over Firebase Cloud Messaging from a companion backend ([SecureMdmBackend](https://github.com/uidai-umang/SecureMdmBackend)).

No third-party EMM/AMAPI vendor is used. All device policy enforcement (app visibility, permission grants, kiosk lock task, Bluetooth/NFC restrictions, storage defence) is implemented directly against `DevicePolicyManager` as Device Owner.

---

## Architecture

```
gov.uidai.securemdmpoc/
├── app/
│   └── MyApplication.kt              Firebase init, Koin startup
├── data/
│   ├── model/                        Request/response/report data classes
│   ├── prefs/SharedPreferences.kt    Thin wrapper, Koin-injected
│   ├── remote/
│   │   ├── ApiService.kt             Main backend endpoints (port 3000)
│   │   ├── UpdateApiService.kt       OTA update endpoints (port 4000)
│   │   └── RetrofitClient.kt
│   └── repository/
│       ├── DeviceRepository.kt       Check-in, token update, FCM confirm
│       ├── AppManagementRepository.kt App hide/unhide reporting
│       └── UpdateRepository.kt       OTA check/download
├── manager/
│   ├── DeviceOwnerContext.kt          Single shared dpm/admin/isDeviceOwner — Koin single
│   ├── PolicyController.kt           SOLE entry point for all policy operations.
│   │                                 No other class injects the managers below directly.
│   ├── LockdownManager.kt            Device-wide UserManager restrictions, lock task, kiosk
│   ├── DynamicAppManager.kt          Per-app classification, hide/unhide, camera/storage perms
│   ├── BluetoothBlockManager.kt      Bluetooth/NFC restrictions, adapter control
│   ├── StorageDefenceManager.kt      MediaStore observer — detect/delete unauthorised files
│   └── PermissionState.kt            Type-safe wrapper over DPM grant-state constants
├── receivers/
│   ├── MyDeviceAdminReceiver.kt       Device Owner lifecycle (onEnabled, provisioning complete)
│   ├── PackageChangeReceiver.kt       Per-install policy enforcement (registered dynamically)
│   ├── UpdateInstallReceiver.kt       Post-OTA-install policy re-apply + reporting
│   ├── BootReceiver.kt                Restarts PolicyEnforcementService after reboot
│   └── KioskModeReceiver.kt           Full-screen notification for kiosk state transitions
├── ui/
│   ├── kiosk/                        KioskFragment, KioskViewModel
│   └── admin/                        AdminExitFragment, AdminViewModel (PIN-gated exit)
├── util/
│   ├── Utils.kt                      Fail-safe toast helper, exemption/suspend package lists
│   ├── HiddenAppsStore.kt             SharedPreferences-backed hidden-package set
│   └── AppCategory.kt                Classification enum
├── MyFirebaseMessagingService.kt     FCM command dispatch — delegates to PolicyController
├── PolicyEnforcementService.kt       Foreground service keeping process alive for PackageChangeReceiver
├── UpdateChecker.kt                  OTA check/download/silent-install via PackageInstaller
└── DeviceErrorReporter.kt            Fire-and-forget error reporting to backend
```

### Key design principle: one controller, many managers

`PolicyController` is the **only** class any Service, Receiver, or ViewModel injects for policy operations. It owns `LockdownManager`, `DynamicAppManager`, `BluetoothBlockManager`, and `StorageDefenceManager` internally, wraps every call in a single error-handling path (`DeviceErrorReporter`), and is the only place `DevicePolicyManager`/`admin` are touched outside the managers themselves. Adding a new policy capability means extending a manager and exposing one new method on the controller — not touching every consumer.

All managers and `DeviceOwnerContext` are registered as Koin `single {}` — there is exactly one instance of each for the process lifetime, since none hold meaningful per-instance state and `StorageDefenceManager` specifically requires single-instance semantics for its `ContentObserver` lifecycle to be correct.

---

## Core capabilities

| Capability | Mechanism |
|---|---|
| Zero-touch provisioning | QR code, Android Enterprise fully-managed device flow |
| Kiosk lock task | `setLockTaskPackages` + `setLockTaskFeatures`, toggled remotely via FCM |
| Dynamic app classification | 13-tier priority chain using `PackageManager` intent resolution, declared permissions, and `ApplicationInfo` flags — **zero hardcoded package names** except the AMAPI agent exception |
| App hide/unhide | `setApplicationHidden`, bulk and per-package, with backend confirmation reporting |
| Camera/storage permission control | `setPermissionGrantState`, applied during classification and on every fresh install |
| Bluetooth/NFC restriction | `DISALLOW_BLUETOOTH`, `DISALLOW_BLUETOOTH_SHARING`, NFC outgoing-beam block, per-app runtime permission denial, adapter force on/off (Device-Owner-exempt from the API-33 lockout) |
| Storage defence | `MediaStore` `ContentObserver` — detects and deletes unauthorised media/download entries not created by this app |
| Silent OTA updates | Backend-hosted APK, `PackageInstaller` silent session install, no user interaction |
| Fleet command & control | Firebase Cloud Messaging — topic broadcast with `targetDevice` filtering for per-device targeting, avoiding per-device token bookkeeping |
| Device release | Full policy/permission/Bluetooth restore, then `clearDeviceOwnerApp` |

---

## Known limitations (by design or platform constraint)

- **Play Store cannot be hidden or suspended.** `setApplicationHidden` breaks Play Integrity for any Play Store–distributed app on the device; `setPackagesSuspended` is rejected by the OS itself — Play Store is the device's "required package installer," one of a fixed set of roles (alongside device admins, the active launcher, the permission controller) that Android explicitly protects from suspension regardless of Device Owner privilege. Mitigated via kiosk mode's lock task, which makes Play Store practically unreachable during normal device use.
- **Storage permission denial does not block `MediaStore.insert()`-based writes.** Scoped Storage allows apps to write their own content into shared media collections without holding `WRITE_EXTERNAL_STORAGE` at all. `StorageDefenceManager`'s detect-and-delete mechanism exists specifically to close this gap after the fact; it is not a preventative block.
- **Bluetooth/Quick Share receiving cannot be blocked.** `DISALLOW_BLUETOOTH_SHARING` only restricts outgoing transfers (confirmed against Android Enterprise documentation). No Device Owner API blocks incoming Quick Share/Nearby Share reception.
- **The Device Owner admin component is bound at provisioning time and cannot be migrated.** If the `DeviceAdminReceiver` class is ever renamed or moved to a different package (as happened during an internal refactor), every already-provisioned device must be factory reset and re-provisioned — there is no in-place migration path; `DevicePolicyManagerService` permanently associates the registered `ComponentName` with the device at enrollment.
- **OTA signature/version verification is currently disabled** in `UpdateChecker.installApkSilently()` pending resolution of unrelated issues. Installs are presently unverified — flagged explicitly in code comments, to be re-enabled before any production rollout.

---

## Build requirements

- Android Studio with JDK 17 (project uses Gradle 8.13 — JDK versions newer than ~23 are not supported by this Gradle version)
- `minSdk 28`, `targetSdk 36`
- Release builds must be signed with the **original provisioning keystore**. Re-signing with a different key after devices are already enrolled will break every `DevicePolicyManager` call with `SecurityException` — Device Owner status is tied to a specific signing identity, not just the package name.

```bash
export JAVA_HOME=$(brew --prefix openjdk@17)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleRelease
```

---

## Provisioning a device

1. Ensure [SecureMdmBackend](https://github.com/uidai-umang/SecureMdmBackend) is running and serving the release APK over HTTP.
2. Generate the provisioning QR (see backend README) — confirm `PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` matches the **current** package path of `MyDeviceAdminReceiver` exactly.
3. Factory reset the target device.
4. On the welcome screen, tap 6 times to trigger QR scan provisioning.
5. Scan the generated QR. Provisioning completes in 3–5 minutes; the app installs as Device Owner automatically.

---

## Related repository

Backend (Node.js/Express + Firebase Admin SDK): [SecureMdmBackend](https://github.com/uidai-umang/SecureMdmBackend)
