# Guide - Intelligent Navigation Assistant
 

An AI-powered assistive application leveraging computer vision to provide real-time obstacle detection and multimodal feedback for visually impaired users and those needing enhanced environmental awareness.

## Key Features

- **Real-Time Perception**
  - 80+ object detection with TensorFlow Lite (EfficientDet-Lite0 model)
  - Adaptive ambient light adjustment (Auto-flash)
  - 16-direction spatial localization

- **Smart Feedback System**
  - Multi-level voice alerts (Danger/Caution/Info)
  - Configurable vibration patterns
  - Dynamic feedback frequency adaptation

- **Accessibility Optimized**
  - Full voice interaction support
  - Low-power sensor fusion
  - Huawei/Xiaomi device optimizations

## Tech Stack

- **Core Frameworks**
  - TensorFlow Lite 2.8+
  - Android CameraX
  - AndroidX Lifecycle

- **Key Components**
  - `CameraManager`: Camera control & image pipeline
  - `ObjectDetectorHelper`: TFLite inference engine
  - `FeedbackManager`: Multimodal feedback system
  - `OverlayView`: Detection visualization

- **Device Integration**
  - Light sensor-driven auto-flash
  - Cross-vendor TTS engine support
  - Multi-type vibration motor handling

## Getting Started

### Requirements

- Android 9.0+ (API 24+)
- Camera2 API support
- Recommended: Light sensor & vibration motor

### Installation

1. Clone repository:
   ```bash
   git clone https://github.com/yourusername/guide.git
   ```

2. Import to Android Studio:
   - Use Android Studio Arctic Fox+
   - Gradle 7.0+ & Android Gradle Plugin 7.0+

3. Model deployment:
   - Place `efficientdet_lite0.tflite` in `app/src/main/assets`

## Usage

1. First launch:
   - Grant camera permission
   - Allow TTS engine initialization

2. Basic operations:
   - Automatic environment scanning starts
   - Tap settings (bottom-right) for preferences
   - Two-finger swipe down for emergency stop

3. Feedback modes:
   - Danger alerts: Continuous vibration + priority speech
   - Regular warnings: Single vibration + standard speech
   - Environment updates: Speech-only notifications

## Advanced Configuration

### Preference Settings

```xml
<!-- app/src/main/res/xml/settings.xml -->

<string-array name="pref_confidence_entries">
    <item>High Accuracy (0.7)</item>
    <item>Balanced (0.5)</item>
    <item>Sensitive (0.3)</item>
</string-array>

<string-array name="pref_feedback_frequency_entries">
    <item>Realtime</item>
    <item>Power Saver</item>
    <item>Emergency Only</item>
</string-array>
```

### Development Extensions

1. Custom detection model:
   ```kotlin
   objectDetectorHelper = ObjectDetectorHelper(
       context = this,
       modelName = "custom_model.tflite",
       labelPath = "labels.txt"
   )
   ```

2. Add new feedback pattern:
   ```kotlin
   feedbackManager.registerFeedbackProfile(
       profileName = "door_alert",
       vibrationPattern = longArrayOf(0, 200, 100, 300),
       ttsTemplate = "Door detected ${distance}m ahead"
   )
   ```

## Contributing

We welcome contributions through:

1. Issue reporting:
   - Use [Issue Template](.github/ISSUE_TEMPLATE.md)
   - Include device model & reproduction steps

2. Code contributions:
   - Fork repository and create feature branch
   - Follow [Kotlin Style Guide](code-style.md)
   - Submit PR with linked issue

3. Localization support:
   - Add translations in `feedback_labels.csv`
   - Test multilingual TTS compatibility

## License

Apache License 2.0
```

Key differences from Chinese version:
1. Technical terms use official translations (e.g., "TensorFlow Lite" instead of localized names)
2. Device brands retain original names (Huawei/Xiaomi)
3. Measurement units use international standards (e.g., "m" for meters)
4. Development references align with Android ecosystem conventions
5. Localization instructions emphasize multilingual support

This version maintains technical accuracy while being accessible to global developers and users.
