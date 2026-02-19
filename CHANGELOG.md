# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0]

### Added

- [API] Result-like object (`DatResult`) used to return `Error` from some methods.
- [API] `ALREADY_INITIALIZED` error to `WearablesError`.

### Changed

- [API] Updated permission functions to return `DatResult<PermissionStatus, PermissionError>` instead of `PermissionStatus`.
- [API] In `PermissionError`, `COMPANION_APP_NOT_INSTALLED` has been renamed to `META_AI_NOT_INSTALLED`.
- The Camera Access app streaming UX reflects device availability.
- The Camera Access app shows errors when incompatible glasses are found.

### Fixed

- Fixed orientation of images caputured by `MockDevice`.
- Streaming status is set to `stopped` if permission is not granted.
- Fixed invalidation of flow from `Wearables.getDeviceSessionState` after streaming stops.
- Fixed UI issues in the Camera Access app.

### Removed

- [API] `Error` data class from `PermissionStatus`.

## [0.2.1] - 2025-12-04

### Changed

- The Camera Access app now correctly processes orientation metadata in HEIC images.

## [0.2.0] - 2025-11-18

### Added

- [API] Base classes for errors (`DatError`) and exceptions (`DatException`) of the SDK.
- [API] New `WearablesError` and `WearablesException` types.
- [API] Configurable frame rate for the video stream. Valid values include 30, 24, 15, 7 and 2 fps.
- [API] `AutoDeviceSelector` constructor now accepts a device ranking function to influence device selection.
- [API] A description (string) to enum types.
- [API] `DeviceMetadata` includes new fields for compatibility and firmware info versions.

### Changed

- [API] The SDK is now split into components, allowing independent inclusion in projects as needed.
- [API] Calling any `Wearables` function without initialization throws a WearablesException.
- [API] Permission API updated for better consistency with iOS:
  - `checkPermission` renamed to `checkPermissionStatus`.
  - `AskPermissionContract` renamed to `RequestpermissionContract`.
  - `PermissionGrantState` replaced by `PermissionStatus`, with values `GRANTED` and `DENIED`.
  - Updated the set of values of `PermissionError`.
- [API] Permission requests no longer throw exceptions, but they return an `Error` instead.
- [API] `RegistrationError` now holds different errors, aligning more closely with the iOS SDK.
- [API] `DeviceSelector`'s select method replaced by an active device Flow.
- [API] Renamed `DeviceType` enum values.
- [API] Replaced `MockDevice` `UUID` with `DeviceIdentifier`.
- `AutoDeviceSelector` now selects or drops devices based on connectivity state.
- Adaptive Bit Rate (streaming) updated to use provided resolution and frame rate hints.
- Camera Access app redesigned and updated to the current SDK version.

### Removed

- [API] `PermissionException`.
- [API] `onDeviceName` method on `Permission`.

### Fixed

- Sessions now close properly when the connection with the glasses is lost.
- The requested video quality is now correctly applied to the stream.

## [0.1.0]

### Added

- First version of the Wearables Device Access Toolkit for Android.
