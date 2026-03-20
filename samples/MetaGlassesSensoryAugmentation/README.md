# Camera Access App Notes

Current runtime behavior is documented in `Stream Control UI` and `Running the app` below. Earlier sections in this file include historical implementation notes and design discussion.
To achieve continuous playback, the app needs to move from a `discrete cue scheduler` to a `continuous multistream audio renderer`.

Current limitation:
- `CueScheduler` and `emitSceneCues()` are built around one-shot playback.
- `BinauralSpatialAudioEngine.playSpatialCue(...)` renders a single cue and returns a duration.
- That architecture is correct for pings, but not for always-on object/north/plane beds.

**What needs to change**

**1. Split audio into two layers**
- `Event cue layer`: keep existing ping/beacon/landmark one-shots.
- `Continuous layer`: new always-running background streams for:
  - tracked object
  - north
  - selected plane classes

This lets you preserve pings while adding continuous guidance.

**2. Add a continuous stream model**
Create new audio-domain classes:

- `app/src/main/java/.../audio/ContinuousAudioChannel.kt`
- `app/src/main/java/.../audio/ContinuousAudioState.kt`
- `app/src/main/java/.../audio/ContinuousCueProfile.kt`
- `app/src/main/java/.../audio/ContinuousCueProfileRegistry.kt`

Suggested shape:

```kotlin
enum class ContinuousChannelId {
    OBJECT,
    NORTH,
    PLANE_WALL,
    PLANE_FLOOR,
    PLANE_CEILING,
    PLANE_DOOR,
    PLANE_WINDOWPANE,
    PLANE_TABLE,
    PLANE_CHAIR,
    PLANE_SOFA,
}
```

```kotlin
data class ContinuousAudioState(
    val channelId: ContinuousChannelId,
    val enabled: Boolean,
    val soundAssetPath: String,
    val gain: Float,
    val brightness: Float,
    val playbackRate: Float,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val beatRateHz: Float,
)
```

**3. Add a continuous mixer/renderer**
Create:

- `app/src/main/java/.../audio/ContinuousSpatialAudioEngine.kt`

Responsibilities:
- maintain several looping mono sources simultaneously
- apply per-channel modulation:
  - object: gain by proximity, brightness by confidence, pan by azimuth
  - plane: gain by area, timbre by class, pan by centroid/plane direction, fixed 1 m conceptual distance
  - north: beat rate + gain by heading error, centered on north bearing
- convolve each channel through HRIR based on current azimuth/elevation
- sum them with headphone-safe limiter/soft clipper
- continuously update parameters without restarting playback each frame

This should be a new engine, not a patch to `playSpatialCue(...)`.

**4. Make rendering frame-based instead of cue-based**
The continuous engine should run an internal audio loop:
- every `20–40 ms`, render a small stereo buffer
- for each active channel:
  - get current modulation state
  - read from its looping source
  - apply EQ/brightness
  - apply amplitude envelope / beat gating
  - spatialize with HRIR
- mix all active channels
- output to a persistent `AudioTrack`

That is the core architectural shift.

**5. Keep event pings separate**
Do not convert landmark/beacon ping behavior into the continuous engine unless intended.
Use:
- `ContinuousSpatialAudioEngine` for background streams
- existing `BinauralSpatialAudioEngine.playSpatialCue(...)` for event pings

Or merge later into one engine with two buses:
- `continuous bus`
- `event bus`

**6. Add channel control state**
Extend `StreamUiState.kt` with dedicated continuous controls:

- `isContinuousObjectEnabled`
- `isContinuousNorthEnabled`
- `isContinuousPlaneEnabled`
- `selectedTrackedObjectLabel: String?`
- `selectedPlaneLabels: Set<String>`

You already have some toggles, but they currently mean “allow discrete cue generation”.
They should become explicit continuous-channel toggles.

**7. Add object target locking**
For your “requested object should track” requirement:
- add selected target state in `StreamViewModel`
- object chosen by:
  - voice command: `track chair`
  - manual UI selection
- lock onto best matching tracked object until lost
- if lost, either:
  - silence object channel
  - or reacquire nearest matching object

Needed files:
- `StreamViewModel.kt`
- `VoiceCommand.kt`
- `VoiceCommandParser.kt`
- `StreamScreen.kt`

**8. Extend voice commands**
Add commands for continuous channel control:

- `enable object audio`
- `disable object audio`
- `track chair`
- `track table`
- `enable north audio`
- `disable north audio`
- `enable plane audio`
- `disable plane audio`
- `only wall`
- `only floor`
- `only door`

Current parser only partially supports this.

**9. Replace scene scheduling with state updates**
Right now `emitSceneCues()` schedules discrete scene entries.
That should become:
- compute latest continuous states for object/north/planes
- push them into `ContinuousSpatialAudioEngine.updateChannels(...)`

So:
- `UnifiedCueArbitrator` remains useful for events
- continuous channels bypass that scheduler

**10. Add cue profiles for continuous channels**
You asked for pitch, timbre, loudness, tempo.

Implement that with:

- `loudness` -> channel gain
- `pitch` -> playback-rate or pitch-shift parameter
- `timbre` -> EQ/filter shaping + source asset choice
- `tempo` -> beat/gating envelope, not playback speed

Per your spec:
- `object`
  - gain from proximity
  - brightness from confidence
  - azimuth from object center
  - fixed tempo
- `plane`
  - gain from area
  - timbre from class
  - azimuth from plane location
  - fixed slow tempo
- `north`
  - gain from heading error
  - beat rate from heading error
  - always centered on north bearing

**11. Add headphone-safe master control**
Because you want full mix simultaneously:
- add per-channel gain caps
- add overall limiter / soft clipper
- optionally duck continuous channels during event pings

Required inside `ContinuousSpatialAudioEngine`:
- master gain
- per-channel gain ceiling
- soft saturation or limiter

**12. Preserve current pings as optional events**
Object tracking off but “ping” requested:
- keep current ping path
- that is separate from continuous object stream

So behavior becomes:
- continuous channel toggled off: no background object stream
- user says `ping chair`: one-shot cue still plays

**Implementation order**
Recommended sequence:

1. Add continuous audio data model and profile registry
2. Build `ContinuousSpatialAudioEngine` with persistent `AudioTrack`
3. Add `updateChannels(...)` API
4. Wire north continuous state
5. Wire tracked-object continuous state
6. Wire selected plane-class continuous states
7. Update UI toggles and target selectors
8. Extend voice commands
9. Keep existing discrete ping logic for events

**Concrete files to create**
- `app/src/main/java/.../audio/ContinuousSpatialAudioEngine.kt`
- `app/src/main/java/.../audio/ContinuousAudioChannel.kt`
- `app/src/main/java/.../audio/ContinuousCueProfile.kt`
- `app/src/main/java/.../audio/ContinuousCueProfileRegistry.kt`
- `app/src/main/java/.../audio/ContinuousMixer.kt`

**Concrete files to update**
- `app/src/main/java/.../stream/StreamUiState.kt`
- `app/src/main/java/.../stream/StreamViewModel.kt`
- `app/src/main/java/.../ui/StreamScreen.kt`
- `app/src/main/java/.../voice/VoiceCommand.kt`
- `app/src/main/java/.../voice/VoiceCommandParser.kt`

**Important engineering note**
This should not be implemented by repeatedly calling `playSpatialCue()` in a tight loop.
That would produce audible seams, excess allocations, timing drift, and poor mixing.
You need a persistent streaming renderer.

If you want, I can implement the first vertical slice next:
1. continuous north channel,
2. continuous tracked-object channel,
3. engine scaffolding for multistream mixing,
while leaving plane channels for the next step.


## CONTEXT
› I want to create profiles for different cues such as the north cue, beacon/landmark cue,
  and the segmented planes. The sounds will be passed through the hrir transformation
  determined by position in the RGB scene but I want the sounds to be characterised by the
  following: pitch, timbre, loudness and tempo. How would this feature be implemented?

• Implement it as a cue-profile layer between cue selection and BinauralSpatialAudioEngine.

  Core point:

  - HRIR should stay responsible only for spatialization: left/right filtering from scene
    position.
  - pitch, timbre, loudness, and tempo should be encoded in a separate render profile per cue
    type.

  Recommended design

  1. Add cue identity separate from object label

  - Current code keys profiles off objectLabel in ObjectSonificationProfiles.
  - That is too narrow for north, landmark, and plane cues.
  - Introduce a cue profile key:

  enum class CueProfileId {
      NORTH,
      LANDMARK,
      WALL,
      FLOOR,
      CEILING,
      DOOR,
      WINDOWPANE,
      TABLE,
      CHAIR,
      SOFA,
      PERSON
  }

  2. Replace ObjectSonificationProfile with a richer render profile

  - Create audio/CueRenderProfile.kt

  data class CueRenderProfile(
      val baseAssetPath: String,
      val loudnessGain: Float,
      val pitchSemitones: Float,
      val playbackRateScale: Float,
      val lowShelfDb: Float,
      val highShelfDb: Float,
      val attackMs: Int,
      val releaseMs: Int,
      val pulseCount: Int,
      val pulseOnMs: Int,
      val pulseOffMs: Int,
  )

  Why both pitchSemitones and playbackRateScale:

  - tempo is rhythmic repetition or spacing between pulses.
  - pitch is spectral shift.
  - If you only resample audio, pitch and duration change together.
  - If you want them independent, you need a pitch-shift/time-stretch stage.

  3. Add a cue rendering request object

  - Create audio/CueRenderRequest.kt

  data class CueRenderRequest(
      val profileId: CueProfileId,
      val azimuthDeg: Float,
      val elevationDeg: Float,
      val distanceGain: Float = 1f,
      val urgencyScale: Float = 1f,
  )

  4. Add a profile registry

  - Replace or evolve ObjectSonificationProfiles into CueRenderProfiles

  Suggested defaults:

  - NORTH: stable pure/clean timbre, medium pitch, slow pulse
  - LANDMARK: brighter timbre, melodic, repeating beacon pulse
  - WALL: low pitch, dull timbre, slow tempo
  - FLOOR: low-mid pitch, soft timbre, sparse pulse
  - CEILING: higher pitch, airy timbre
  - DOOR: distinct percussive transient
  - WINDOWPANE: thin bright timbre
  - TABLE/CHAIR/SOFA: mid-band object tones

  How to represent timbre
  Use a combination of:

  - source sample choice (baseAssetPath)
  - EQ shaping (lowShelfDb, highShelfDb)
  - optional transient envelope (attack/release)

  That is the practical mobile approach.

  How to represent tempo
  Do not encode tempo as playbackRateScale.
  Use a pulse pattern scheduler:

  - one cue may render as 3 x 90ms pulses with 120ms gaps
  - another as one sustained 500ms event

  This belongs above the audio engine.

  How to represent pitch
  Two options:

  1. Simple implementation now

  - use resampling only
  - easy, but pitch changes duration too

  2. Correct implementation

  - add a pitch-shift/time-stretch stage
  - use a DSP library or implement WSOLA/phase vocoder
  - needed only if you want independent pitch and tempo

  For your current app, I would start with:

  - independent tempo via pulse scheduling
  - approximate pitch via resampling
  - timbre via sample choice + EQ
  - loudness via gain

  That will get you most of the perceptual effect with much less complexity.

  Where to change the app

  1. app/src/main/java/.../audio/ObjectSonificationProfile.kt

  - Replace with CueRenderProfile.kt
  - Rename registry to something cue-source agnostic

  2. app/src/main/java/.../audio/BinauralSpatialAudioEngine.kt

  - Add a method like:

  fun playSpatialCue(request: CueRenderRequest): Long

  Internally:

  - load base asset
  - apply pitch/rate transform
  - apply EQ + envelope + gain
  - apply HRIR convolution using azimuth/elevation
  - play result

  3. app/src/main/java/.../cue/CueScheduler.kt

  - Add pulse scheduling support for tempo patterns
  - It should emit one CueRenderRequest or a sequence of sub-cues

  4. app/src/main/java/.../stream/StreamViewModel.kt

  - Map:
      - north cue -> CueProfileId.NORTH
      - landmark cue -> CueProfileId.LANDMARK
      - SOLOPlanes scene cues -> CueProfileId.WALL, DOOR, etc.
  - Continue using existing ranking/arbitration
  - Only change final render request construction

  5. app/src/main/java/.../priority/ObjectCueSoundMap.kt

  - This should stop being the main carrier of cue character
  - Keep it only as fallback asset selection, or replace it with profile lookup entirely

  Suggested class split

  - audio/CueProfileId.kt
  - audio/CueRenderProfile.kt
  - audio/CueRenderProfiles.kt
  - audio/CueRenderRequest.kt
  - audio/CueSignalShaper.kt
  - audio/CuePulseRenderer.kt
  - audio/CuePitchProcessor.kt if you later add real pitch shifting

  Render pipeline
  For every cue:

  1. Build CueRenderRequest
  2. Resolve CueRenderProfile
  3. Generate pulse pattern if needed
  6. Apply EQ/envelope/gain
  7. Apply HRIR convolution from scene position
  8. Play

  Minimal first implementation
  If you want the fastest path:

  - Keep one WAV per cue family
  - Add CueRenderProfile
  - Implement:
      - loudnessGain
      - playbackRateScale
      - lowShelfDb/highShelfDb
      - pulseCount/pulseOnMs/pulseOffMs
  - Leave true independent pitch-shifting for later

  That gives you distinct north/beacon/plane cue personalities without rewriting the whole
  engine.

  If you want, I can scaffold this feature next by:

  1. replacing ObjectSonificationProfile with a generic cue-profile system, and
  2. wiring north, landmark, and scene/plane cues into it.


› Does the sonification of objects still function with the current changes?


  What did not change:

  - object detection path is still active in StreamViewModel
  - object cues still call cueScheduler.requestObjectCue(...)
  - object labels still resolve through ObjectCueSoundMap and ObjectSonificationProfiles

  What did change:

  - the old stub semantic-segmentation path was replaced in the live stream loop by
    SoloPlanesOnnxReconstructor
  - scene cues now come from SOLOPlanes instead of SegmentationOutput

  So the existing object sonification pipeline was not removed or replaced.

  Important caveat:

  - I could not verify this with a real app build because your Gradle/Kotlin environment is
    still failing on JavaVersion.parse(25.0.1).
  - So I can say the object sonification path is still present and wired, but not yet
    runtime-verified in this environment.


› Before moving on, I want both the object, north, and plane information to be continually
  played, as though it were a continuous signal, rather than a discrete 'ping'. What needs
  to be done to achieve this? Ask clarifying questions before implementing.


• To implement this correctly, I need these specifics first.

  1. Should all three channels play at the same time continuously?

  - object
  - north
  - plane
  - Or should there still be mixing limits so one can duck another?

  2. For object sonification, what should “continuous” track?

  - the top-ranked object only
  - one object per class
  - multiple objects simultaneously
  - all tracked objects within a cap

  3. For plane sonification, what should be rendered continuously?

  - only the dominant plane
  - one wall + floor + ceiling
  - all decoded planes up to a cap
  - only selected plane classes

  4. For north, do you want:

  - a constant beacon always present
  - a repeating tone with no gaps
  - a low-level background cue that gets stronger as heading deviates
  - silence when already aligned to north

  5. Should continuous cues be synthesized as:

  - looping audio beds with parameter modulation
  - very fast retriggered grains/pulses
  - either is fine if perceptually continuous

  6. What should happen when multiple continuous cues overlap?

  - full mix
  - priority ducking
  - hard cap on simultaneous streams
  - user-selectable channel volumes
  - stronger north cue when further from alignment

  8. Is the target behavior headphone-safe background audio, or more assertive navigation
     audio?
     streams?

  - object on/off
  - scene/plane on/off
  - north on/off

  10. For implementation scope, do you want:

  - first pass: continuous single-stream-per-channel architecture
  - or full multistream mixing immediately

  Once you answer these, I can implement the right audio architecture instead of patching the
  current ping scheduler into something it was not designed for.


› 1. Simultaneously is fine but I want to be able to toggle them or off using either voice
  or buttons. 2. the requested object should track. Though this can also be 'pinged' if the
  user wants object tracking to be turned off. 3. only selected plane classes. 4. a
  repeating beating tone that can be turned on/off. 5. looping audio beds with parameter
  modulation. 6. full mix. 8. The target behaviour is headphone-safe background audio with
  exception to event related information such as pinging or landmarks/beacons. 9. Yes all
  channels should have toggles. 10. full multistream mixing immediately.


• Two details still need to be fixed before implementation.
  - by voice command like track chair
  - by manual UI selection from currently detected objects
  - nearest matching object automatically
  - one locked track ID until lost
     I need your rule for pitch/timbre/loudness/tempo changes over time.

  A concrete way to specify it:

  If you want a default, I would implement:

  - object: loudness by proximity, brightness by confidence, pan by azimuth, tempo fixed
  - plane: loudness by area, timbre by class, pan by centroid azimuth, tempo slow and fixed
  - north: beat rate and loudness by heading error, centered at north bearing

  Once you confirm those two points, I can implement the multistream continuous mixer with
  channel toggles and leave discrete pings available as separate event cues.


› 1. both manual UI selection and voice command. 2. object: loudness by proximity,
  brightness by confidence, pan by azimuth, tempo fixed; plane: loudness by area, timbre by
  class, pan by fixed radial distance from user (1-m for now), tempo slow and fixed; north:
  beat rate and loudness by heading error, centered at north bearing.


• I have the behavioral spec now. I’m inspecting the current voice/UI control points and
  audio engine entrypoints so I can convert the cue system from discrete playback to a
  continuous multistream mixer without breaking the existing event pings.

─────────────────────────────────────────────────────────────────────────────────────────────

• To achieve continuous playback, the app needs to move from a discrete cue scheduler to a
  continuous multistream audio renderer.

  Current limitation:

  - CueScheduler and emitSceneCues() are built around one-shot playback.
  - BinauralSpatialAudioEngine.playSpatialCue(...) renders a single cue and returns a
    duration.
  - That architecture is correct for pings, but not for always-on object/north/plane beds.

  What needs to change

  1. Split audio into two layers

  - Event cue layer: keep existing ping/beacon/landmark one-shots.
  - Continuous layer: new always-running background streams for:
      - tracked object
      - north
      - selected plane classes

  This lets you preserve pings while adding continuous guidance.

  2. Add a continuous stream model
  Create new audio-domain classes:

  - app/src/main/java/.../audio/ContinuousAudioChannel.kt
  - app/src/main/java/.../audio/ContinuousAudioState.kt
  - app/src/main/java/.../audio/ContinuousCueProfile.kt
  - app/src/main/java/.../audio/ContinuousCueProfileRegistry.kt

  Suggested shape:

  enum class ContinuousChannelId {
      OBJECT,
      NORTH,
      PLANE_WALL,
      PLANE_FLOOR,
      PLANE_CEILING,
      PLANE_DOOR,
      PLANE_WINDOWPANE,
      PLANE_TABLE,
      PLANE_CHAIR,
      PLANE_SOFA,
  }

  data class ContinuousAudioState(
      val channelId: ContinuousChannelId,
      val enabled: Boolean,
      val soundAssetPath: String,
      val gain: Float,
      val brightness: Float,
      val playbackRate: Float,
      val azimuthDeg: Float,
      val elevationDeg: Float,
      val beatRateHz: Float,
  )

  3. Add a continuous mixer/renderer
  Create:

  - app/src/main/java/.../audio/ContinuousSpatialAudioEngine.kt

  Responsibilities:

  - maintain several looping mono sources simultaneously
  - apply per-channel modulation:
      - object: gain by proximity, brightness by confidence, pan by azimuth
      - plane: gain by area, timbre by class, pan by centroid/plane direction, fixed 1 m
        conceptual distance
      - north: beat rate + gain by heading error, centered on north bearing
  - convolve each channel through HRIR based on current azimuth/elevation
  - sum them with headphone-safe limiter/soft clipper
  - continuously update parameters without restarting playback each frame

  This should be a new engine, not a patch to playSpatialCue(...).

  4. Make rendering frame-based instead of cue-based
  The continuous engine should run an internal audio loop:

  - every 20–40 ms, render a small stereo buffer
  - for each active channel:
      - get current modulation state
      - read from its looping source
      - apply EQ/brightness
      - apply amplitude envelope / beat gating
      - spatialize with HRIR
  - mix all active channels
  - output to a persistent AudioTrack

  That is the core architectural shift.

  5. Keep event pings separate
  Do not convert landmark/beacon ping behavior into the continuous engine unless intended.
  Use:

  - ContinuousSpatialAudioEngine for background streams
  - existing BinauralSpatialAudioEngine.playSpatialCue(...) for event pings

  Or merge later into one engine with two buses:

  - continuous bus
  - event bus

  6. Add channel control state
  Extend StreamUiState.kt with dedicated continuous controls:

  - isContinuousObjectEnabled
  - isContinuousNorthEnabled
  - isContinuousPlaneEnabled
  - selectedTrackedObjectLabel: String?
  - selectedPlaneLabels: Set<String>

  You already have some toggles, but they currently mean “allow discrete cue generation”.
  They should become explicit continuous-channel toggles.

  7. Add object target locking
  For your “requested object should track” requirement:

  - add selected target state in StreamViewModel
  - object chosen by:
      - voice command: track chair
      - manual UI selection
  - lock onto best matching tracked object until lost
  - if lost, either:
      - silence object channel
      - or reacquire nearest matching object

  Needed files:

  - StreamViewModel.kt
  - VoiceCommand.kt
  - VoiceCommandParser.kt
  - StreamScreen.kt

  8. Extend voice commands
  Add commands for continuous channel control:

  - enable object audio
  - disable object audio
  - track chair
  - track table
  - enable north audio
  - disable north audio
  - enable plane audio
  - disable plane audio
  - only wall
  - only floor
  - only door

  Current parser only partially supports this.

  9. Replace scene scheduling with state updates
  Right now emitSceneCues() schedules discrete scene entries.
  That should become:

  - compute latest continuous states for object/north/planes
  - push them into ContinuousSpatialAudioEngine.updateChannels(...)

  So:

  - UnifiedCueArbitrator remains useful for events
  - continuous channels bypass that scheduler

  10. Add cue profiles for continuous channels
  You asked for pitch, timbre, loudness, tempo.

  Implement that with:

  - loudness -> channel gain
  - pitch -> playback-rate or pitch-shift parameter
  - timbre -> EQ/filter shaping + source asset choice
  - tempo -> beat/gating envelope, not playback speed

  Per your spec:

  - object
      - gain from proximity
      - brightness from confidence
      - azimuth from object center
      - fixed tempo
  - plane
      - gain from area
      - timbre from class
      - azimuth from plane location
      - fixed slow tempo
  - north
      - gain from heading error
      - beat rate from heading error
      - always centered on north bearing

  11. Add headphone-safe master control
  Because you want full mix simultaneously:

  - add per-channel gain caps
  - add overall limiter / soft clipper
  - optionally duck continuous channels during event pings

  Required inside ContinuousSpatialAudioEngine:

  - master gain
  - per-channel gain ceiling
  - soft saturation or limiter

  12. Preserve current pings as optional events
  Object tracking off but “ping” requested:

  - keep current ping path
  - that is separate from continuous object stream

  So behavior becomes:

  - continuous channel toggled off: no background object stream
  - user says ping chair: one-shot cue still plays

  Implementation order
  Recommended sequence:

  1. Add continuous audio data model and profile registry
  2. Build ContinuousSpatialAudioEngine with persistent AudioTrack
  3. Add updateChannels(...) API
  4. Wire north continuous state
  5. Wire tracked-object continuous state
  6. Wire selected plane-class continuous states
  7. Update UI toggles and target selectors
  8. Extend voice commands
  9. Keep existing discrete ping logic for events

  Concrete files to create

  - app/src/main/java/.../audio/ContinuousSpatialAudioEngine.kt
  - app/src/main/java/.../audio/ContinuousAudioChannel.kt
  - app/src/main/java/.../audio/ContinuousCueProfile.kt
  - app/src/main/java/.../audio/ContinuousCueProfileRegistry.kt
  - app/src/main/java/.../audio/ContinuousMixer.kt

  Concrete files to update

  - app/src/main/java/.../stream/StreamUiState.kt
  - app/src/main/java/.../stream/StreamViewModel.kt
  - app/src/main/java/.../ui/StreamScreen.kt
  - app/src/main/java/.../voice/VoiceCommand.kt
  - app/src/main/java/.../voice/VoiceCommandParser.kt

  Important engineering note
  This should not be implemented by repeatedly calling playSpatialCue() in a tight loop.
  That would produce audible seams, excess allocations, timing drift, and poor mixing.
  You need a persistent streaming renderer.

  If you want, I can implement the first vertical slice next:

  1. continuous north channel,
  2. continuous tracked-object channel,
  3. engine scaffolding for multistream mixing,
     while leaving plane channels for the next step.


# Rays Additions
To save or forget a position:
  - save landmark <name> / save <name>
  - track <name> / forget <name>
Turn on/off north compass
  - north mode on|off
Activate or deactivate complete scene replay
  - activate, deactivate
Ping any persistent landmark or north:
  - ping <name>
Sonify only a single class:
  - "only <name>" 
  - "<name> only"
Continuous object selection by voice:
  - object <class>
  - follow <class>

## Stream Control UI

The stream screen now uses a two-button full-screen layout.

Top button: Audio compass
- Single tap toggles the north compass cue on or off.
- When enabled, the north cue plays as a continuous background signal.
- Voice alternative:
  - `north mode on`
  - `north mode off`
  - `ping north`

Bottom button: People detection
- Single tap toggles people-only detection sonification on or off.
- Playback runs at a fixed `1.25 Hz` cadence.
- Only `person` detections are sonified from this control path.

Notes
- The old beacon button has been removed from the main stream UI.
- Voice-based landmark save/forget/ping commands still exist, but there is no dedicated landmark or beacon button on the stream screen.

# Camera Access App

A sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, and managing connection states.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Timer-based streaming sessions
- Share captured photos

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Add your personal access token (classic) to the `local.properties` file (see [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup))
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Toggle the north compass cue from the top half of the screen
   - Toggle people detection sonification from the bottom half of the screen

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
