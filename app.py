import io
import joblib
import librosa
import numpy as np
import warnings
from fastapi import FastAPI, UploadFile, File
from fastapi.responses import JSONResponse
import uvicorn


warnings.filterwarnings("ignore")

# =========================
# App Initialization
# =========================
app = FastAPI(title="VoiceShield API")

# =========================
# Load ML Model Safely
# =========================
_model = None

def get_model():
    global _model
    if _model is None:
       
        _model = joblib.load("voice_model.pkl")
    return _model

# =========================
# Feature Extraction
# =========================

def extract_features(audio, sr):
    features = {}

    pitches, magnitudes = librosa.piptrack(y=audio, sr=sr)
    pitch_values = pitches[pitches > 0]

    features["pitch_mean"] = float(np.mean(pitch_values)) if len(pitch_values) > 0 else 0.0
    features["pitch_std"] = float(np.std(pitch_values)) if len(pitch_values) > 0 else 0.0

    mfcc = librosa.feature.mfcc(y=audio, sr=sr, n_mfcc=13)
    mfcc_means = np.mean(mfcc, axis=1)

    for i, val in enumerate(mfcc_means):
        features[f"mfcc_{i+1}"] = float(val)

    centroid = librosa.feature.spectral_centroid(y=audio, sr=sr)
    features["spectral_centroid_mean"] = float(np.mean(centroid))

    rms = librosa.feature.rms(y=audio)
    features["rms_std"] = float(np.std(rms))

    zcr = librosa.feature.zero_crossing_rate(y=audio)
    features["zcr_mean"] = float(np.mean(zcr))

    return features

# =========================
# API Endpoint 
# =========================
@app.post("/analyze_live_audio")
async def analyze_live_audio(audio_chunk: UploadFile = File(...)):
    try:
       
        audio_bytes = await audio_chunk.read()

        audio, sr = librosa.load(io.BytesIO(audio_bytes), sr=16000, mono=True)

        audio, _ = librosa.effects.trim(audio, top_db=20)

        duration = librosa.get_duration(y=audio, sr=sr)
        if duration < 0.5:
            return JSONResponse(content={
                "status": "success",
                "risk_score": 0.0,
                "verdict": "REAL", 
                "message": "Audio too short or silent"
            })

        features = extract_features(audio, sr)
        feature_vector = np.array(list(features.values())).reshape(1, -1)

        model = get_model()
        probabilities = model.predict_proba(feature_vector)[0]
        prob_ai = probabilities[1]

        risk_score = prob_ai * 100.0

        verdict = "FAKE" if prob_ai >= 0.57 else "REAL"
        print(f"Risk Score: {risk_score:.2f}, Verdict: {verdict}")

        return JSONResponse(content={
            "status": "success",
            "risk_score": round(risk_score, 2),
            "verdict": verdict,
            "message": "Analysis complete using ML features."
        })

    except Exception as e:
        print(f"Error processing audio: {e}")
        return JSONResponse(content={
            "status": "error",
            "message": str(e)
        }, status_code=500)

# =========================
# Server Run Command
# =========================
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)