# 🛡️ VoiceShield

### Real-Time AI Voice Impersonation Detection for Safer Calls

VoiceShield is an AI-powered voice security system designed to detect **AI-generated or impersonated voices during internet-based calls**.

It combines an **Android calling interface**, **real-time audio capture**, **machine-learning-based voice analysis**, and **instant risk scoring** to help identify suspicious synthetic speech.

> **Built by Team Jacks Warriors**  
> Sri Vasavi Engineering College

---

## 🚨 The Problem

AI voice cloning is becoming increasingly convincing. Attackers can imitate a person's voice and use it in believable conversations for fraud, social engineering, and impersonation.

VoiceShield adds an AI-powered security layer to the calling experience by analyzing voice characteristics and returning a risk assessment.

---

## 💡 How VoiceShield Works

```text
📱 Android App
     │
     ▼
🎙️ RTC Audio Capture
     │
     ▼
🌐 FastAPI Backend
     │
     ▼
🎧 Audio Feature Extraction
     │
     ├── Pitch
     ├── MFCC
     ├── Spectral Centroid
     ├── RMS Energy
     └── Zero Crossing Rate
     │
     ▼
🤖 ML Classifier
     │
     ▼
📊 Risk Score + Verdict
     │
     ▼
⚠️ VoiceShield UI
```

---

## ✨ Key Features

| Feature | Description |
|---|---|
| 🎙️ Real-Time Audio | Captures remote call audio during an active internet call |
| 🤖 AI Voice Detection | Uses machine-learning-based voice classification |
| 📊 Risk Score | Produces a percentage-style voice risk score |
| ⚠️ Instant Verdict | Displays results such as `REAL` or `FAKE` |
| 📱 Android Client | Built using Kotlin and Jetpack Compose |
| 🌐 FastAPI Backend | Processes uploaded audio through an API |
| 🎧 Audio Analysis | Extracts acoustic features using Librosa |
| 🧪 AI Voice Testing | Supports prepared AI voice samples for demonstrations |

---

## 🧠 Machine Learning Pipeline

The model is trained using `train_model.py`.

```text
Audio Dataset
     ↓
Real / Fake Voice Labels
     ↓
Librosa Feature Extraction
     ↓
Feature Matrix
     ↓
StandardScaler
     ↓
Logistic Regression
     ↓
voice_model.pkl
```

The training pipeline supports `.wav`, `.mp3`, and `.flac` files and identifies samples from folders containing `real_voice` and `fake_voice` labels.

---

## 🔍 Audio Features

VoiceShield extracts multiple characteristics from speech:

- 🎵 Pitch mean and pitch variation
- 🧠 13 MFCC coefficients
- 📡 Spectral centroid
- 🔊 RMS energy variation
- 🌊 Zero-crossing rate

These features are combined into a numerical representation that is passed to the trained classifier.

---

## 🏗️ Technology Stack

### 📱 Android
- Kotlin
- Jetpack Compose
- Android SDK
- Agora RTC SDK

### 🧠 AI / ML
- Python
- Scikit-learn
- Logistic Regression
- StandardScaler
- Joblib

### 🎧 Audio Processing
- Librosa
- NumPy

### 🌐 Backend
- FastAPI
- Uvicorn
- OkHttp

---

## 📁 Project Structure

```text
Voice-Shield/
│
├── AndroidManifest.xml       # Android configuration
├── MainActivity.kt           # Android UI, RTC integration and audio handling
├── build.gradle.kts          # Android dependencies and build configuration
│
├── app.py                    # FastAPI live-audio analysis endpoint
├── main.py                   # Voice detection API and ML logic
├── features.py               # Audio feature extraction
├── train_model.py            # ML training pipeline
│
└── README.md                 # Project documentation
```

---

## 🚀 Getting Started

### Prerequisites

- Android Studio
- JDK 11+
- Python 3.9+
- Agora App ID
- Trained `voice_model.pkl`

### 1. Clone the repository

```bash
git clone https://github.com/varunramkuri/Voice-Shield.git
cd Voice-Shield
```

### 2. Install backend dependencies

```bash
pip install fastapi uvicorn librosa numpy scikit-learn joblib python-multipart
```

### 3. Add the trained model

Place the trained model in the backend directory:

```text
voice_model.pkl
```

### 4. Start the FastAPI server

```bash
python app.py
```

The backend listens on port `8000` by default.

### 5. Configure the Android app

Update the development placeholders in `MainActivity.kt`:

```kotlin
private val appId = "YOUR_AGORA_APP_ID"
private val serverBaseUrl = "YOUR_BACKEND_SERVER_URL"
```

Then build and run the Android application from Android Studio.

---

## 🔌 API

### Analyze Live Audio

```http
POST /analyze_live_audio
```

The endpoint accepts an uploaded audio chunk and returns the analysis result.

Example response:

```json
{
  "status": "success",
  "risk_score": 82.41,
  "verdict": "FAKE",
  "message": "Analysis complete using ML features."
}
```

---

## 🧪 AI Voice Testing

VoiceShield includes an AI-voice testing flow in the Android application.

Prepared AI voice samples can be played during a test call so the receiver side can evaluate the detection pipeline and display the resulting risk score.

---

## 🔐 Security & Production Notes

The current repository contains development placeholders for service credentials and backend configuration.

For production deployment:

- Store secrets securely instead of committing them.
- Use HTTPS/TLS for backend communication.
- Add authentication and authorization to production APIs.
- Apply appropriate privacy and consent controls for call-audio processing.
- Move API configuration into secure environment/configuration management.

---

## 🎯 Project Goals

**Detect** suspicious synthetic speech.

**Analyze** voice characteristics using machine learning.

**Alert** users when suspicious voice patterns are detected.

---

## 🌍 Potential Applications

- 🏦 Financial fraud prevention
- 👨‍👩‍👧 Family impersonation scam detection
- 🏢 Enterprise call security
- 📞 Customer-support verification
- 🔐 Identity verification workflows
- 🚨 Social-engineering and scam prevention

---

## 🔮 Future Scope

- Real-time continuous audio analysis
- Deep-learning-based voice classifiers
- Speaker verification and voice identity matching
- Multi-language voice analysis
- Noise-robust detection
- Cloud deployment and horizontal scaling
- More explainable detection results
- Continuous monitoring throughout the call

---

## 👥 Team

### Jacks Warriors

**Sri Vasavi Engineering College**

A collaborative AI + Android project focused on real-time voice security and AI impersonation detection.

---

## 📌 Project Status

🚧 **Active Development**

The repository currently contains the Android client, RTC audio handling, audio feature extraction pipeline, ML training code, and FastAPI-based voice-analysis services.

---

## ⭐ Support the Project

If you find VoiceShield useful or interesting, consider giving the repository a ⭐ and sharing it with developers and teams working on AI security.

---

# 🛡️ VoiceShield

### Don't trust every voice. Verify it with AI.
