import os
import joblib
import librosa
import numpy as np
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from features import extract_features  # Importing your feature extraction code

# UPDATE THIS PATH to where your dataset is extracted
DATASET_PATH = "./mixed_voice_dataset" 

def train_model_on_mixed_dataset(dataset_dir):
    X, y = [], []
    print(f"[INFO] Scanning directory: {dataset_dir}")
    print("[INFO] Accessing all 4 categories and their sub-folders (balanced, bg_dominant, voice_dominant)...")

    file_count = 0

    # os.walk automatically navigates through every category and sub-folder
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file.endswith((".wav", ".mp3", ".flac")):
                path = os.path.join(root, file)

                # Telling the model "What is What" based on the folder name
                if "real_voice" in path:
                    label = 0  # Human Voice (Real)
                elif "fake_voice" in path:
                    label = 1  # AI Voice (Fake)
                else:
                    continue # Skip irrelevant files

                try:
                    # Loading the COMPLETE audio file (No cut-outs)
                    audio, sr = librosa.load(path, sr=16000, mono=True)
                    
                    # Extracting features from the full audio
                    feats = extract_features(audio, sr)
                    
                    X.append(list(feats.values()))
                    y.append(label)
                    
                    file_count += 1
                    # Progress tracker (so you know it's working)
                    if file_count % 500 == 0:
                        print(f"[PROGRESS] Processed {file_count} audio files successfully...")

                except Exception as e:
                    print(f"[WARNING] Skipping file {file} due to error: {e}")
    
    return X, y

if __name__ == "__main__":
    print("-" * 50)
    print(" 🚀 STARTING ML MODEL TRAINING PROCESS ")
    print("-" * 50)
    
    print("[INFO] Starting feature extraction. This will take some time for 12,000 files...")
    X, y = train_model_on_mixed_dataset(DATASET_PATH)

    print("\n[INFO] Feature Extraction Completed!")
    print("-" * 50)
    print(f"[STATS] Total Human (Real) files loaded : {y.count(0)}")
    print(f"[STATS] Total AI (Fake) files loaded    : {y.count(1)}")
    print("-" * 50)

    if len(X) == 0:
        print("[ERROR] No data found! Please check the DATASET_PATH variable.")
    else:
        print("\n[INFO] Initializing Machine Learning Pipeline...")
        # Using the original Logistic Regression setup[cite: 4]
        model = Pipeline([
            ("scaler", StandardScaler()),
            ("clf", LogisticRegression(
                max_iter=3000,
                class_weight="balanced",
                solver="lbfgs"
            ))
        ])

        print("[INFO] Training the model on the full dataset...")
        model.fit(X, y)

        joblib.dump(model, "voice_model.pkl")
        print("[SUCCESS] Model trained perfectly and saved as 'voice_model.pkl'!")
        print("🎉 You can now use main.py to test the live API!")