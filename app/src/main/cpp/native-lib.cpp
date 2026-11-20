#include <jni.h>
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <cmath>
#include <algorithm>

enum class EnvStage { IDLE, ATTACK, SUSTAIN, RELEASE };

struct Voice {
    bool active = false;
    int note = -1;
    double phase = 0.0;
    double basePhaseInc = 0.0;
    EnvStage stage = EnvStage::IDLE;
    float env = 0.0f;
};

class SynthEngine : public oboe::AudioStreamDataCallback {
public:
    SynthEngine() {
        // Pattern iniziale (arpeggio Do minore)
        int defaultNotes[8] = {60, 63, 67, 72, 67, 63, 60, -1};
        for (int i = 0; i < 8; ++i) seqNotes[i].store(defaultNotes[i]);
    }

    bool start() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setDataCallback(this);
        if (builder.openStream(mStream) != oboe::Result::OK) return false;
        mSampleRate = static_cast<float>(mStream->getSampleRate());
        return mStream->requestStart() == oboe::Result::OK;
    }

    void stop() {
        if (mStream) { mStream->stop(); mStream->close(); mStream.reset(); }
    }

    void noteOn(int note) {
        if (note < 0 || note > 127) return;
        double freq = 440.0 * std::pow(2.0, (note - 69) / 12.0);
        Voice* target = nullptr;
        for (auto& v : mVoices) {
            if (v.note == note || !v.active) { target = &v; break; }
        }
        if (!target) target = &mVoices[0];
        target->note = note;
        target->basePhaseInc = freq / mSampleRate;
        target->active = true;
        target->stage = EnvStage::ATTACK;
    }

    void noteOff(int note) {
        for (auto& v : mVoices) {
            if (v.active && v.note == note && v.stage != EnvStage::RELEASE)
                v.stage = EnvStage::RELEASE;
        }
    }

    // Parametri Sintesi
    std::atomic<int> waveform{1};
    std::atomic<float> cutoff{2500.0f};
    std::atomic<float> attack{0.02f};
    std::atomic<float> release{0.25f};

    // Parametri LFO (0 = OFF, 1 = PITCH, 2 = CUTOFF)
    std::atomic<int> lfoTarget{0};
    std::atomic<float> lfoRate{4.0f};
    std::atomic<float> lfoDepth{0.5f};

    // Parametri Sequencer
    std::atomic<bool> seqPlaying{false};
    std::atomic<float> seqBpm{120.0f};
    std::atomic<int> currentStep{0};
    std::array<std::atomic<int>, 8> seqNotes;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);

        int wave = waveform.load();
        float baseCut = cutoff.load();
        float att = std::max(0.005f, attack.load());
        float rel = std::max(0.005f, release.load());

        int target = lfoTarget.load();
        float rate = lfoRate.load();
        float depth = lfoDepth.load();
        double lfoInc = rate / mSampleRate;

        bool playing = seqPlaying.load();
        float bpm = std::max(40.0f, seqBpm.load());
        // Ottavi (1/8 note) per step
        float samplesPerStep = (60.0f / bpm) * 0.5f * mSampleRate;

        if (!playing && mWasPlaying) {
            if (mLastSeqNote >= 0) { noteOff(mLastSeqNote); mLastSeqNote = -1; }
            mSeqCounter = 0.0f;
            currentStep.store(0);
        }
        mWasPlaying = playing;

        for (int i = 0; i < numFrames; ++i) {
            // 1. Avanzamento Sequencer
            if (playing) {
                if (mSeqCounter <= 0.0f) {
                    if (mLastSeqNote >= 0) {
                        noteOff(mLastSeqNote);
                        mLastSeqNote = -1;
                    }
                    int step = mSeqStepIndex;
                    currentStep.store(step);
                    int noteToPlay = seqNotes[step].load();
                    if (noteToPlay >= 0) {
                        noteOn(noteToPlay);
                        mLastSeqNote = noteToPlay;
                    }
                    mSeqStepIndex = (mSeqStepIndex + 1) % 8;
                    mSeqCounter += samplesPerStep;
                }
                mSeqCounter -= 1.0f;
            } else {
                mSeqStepIndex = 0;
                mSeqCounter = 0.0f;
            }

            // 2. Calcolo LFO (-1.0 a +1.0)
            float lfoVal = static_cast<float>(std::sin(mLfoPhase * 2.0 * M_PI));
            mLfoPhase += lfoInc;
            if (mLfoPhase >= 1.0) mLfoPhase -= 1.0;

            // Modulazione Pitch (±3 semitoni max)
            double pitchMult = 1.0;
            if (target == 1 && depth > 0.001f) {
                pitchMult = std::pow(2.0, (lfoVal * depth * 3.0) / 12.0);
            }

            // Modulazione Cutoff
            float activeCut = baseCut;
            if (target == 2 && depth > 0.001f) {
                float modFactor = std::pow(2.0f, lfoVal * depth * 2.5f);
                activeCut = std::clamp(baseCut * modFactor, 60.0f, mSampleRate * 0.42f);
            }

            // 3. Somma Voci (Oscillatori + ADSR)
            float mix = 0.0f;
            for (auto& v : mVoices) {
                if (!v.active) continue;
                if (v.stage == EnvStage::ATTACK) {
                    v.env += 1.0f / (att * mSampleRate);
                    if (v.env >= 1.0f) { v.env = 1.0f; v.stage = EnvStage::SUSTAIN; }
                } else if (v.stage == EnvStage::RELEASE) {
                    v.env -= 1.0f / (rel * mSampleRate);
                    if (v.env <= 0.0f) { v.env = 0.0f; v.stage = EnvStage::IDLE; v.active = false; }
                }

                float osc = 0.0f;
                if (wave == 0) osc = static_cast<float>(std::sin(v.phase * 2.0 * M_PI));
                else if (wave == 1) osc = static_cast<float>(2.0 * v.phase - 1.0);
                else if (wave == 2) osc = (v.phase < 0.5) ? 1.0f : -1.0f;
                else osc = static_cast<float>(4.0 * std::abs(v.phase - 0.5) - 1.0);

                mix += osc * v.env;
                v.phase += v.basePhaseInc * pitchMult;
                if (v.phase >= 1.0) v.phase -= 1.0;
            }

            // 4. Filtro Low-Pass
            float rc = 1.0f / (2.0f * 3.14159265f * activeCut);
            float dt = 1.0f / mSampleRate;
            float alpha = dt / (rc + dt);
            mFilterState += alpha * (mix - mFilterState);

            out[i] = std::tanh(mFilterState * 0.25f);
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    std::array<Voice, 8> mVoices;
    float mSampleRate = 48000.0f;
    float mFilterState = 0.0f;
    double mLfoPhase = 0.0;

    bool mWasPlaying = false;
    int mSeqStepIndex = 0;
    float mSeqCounter = 0.0f;
    int mLastSeqNote = -1;
};

static SynthEngine* engine = nullptr;

extern "C" {
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_startEngine(JNIEnv*, jobject) {
    if (!engine) engine = new SynthEngine();
    engine->start();
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_stopEngine(JNIEnv*, jobject) {
    if (engine) { engine->stop(); delete engine; engine = nullptr; }
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_noteOn(JNIEnv*, jobject, jint note) {
    if (engine) engine->noteOn(note);
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_noteOff(JNIEnv*, jobject, jint note) {
    if (engine) engine->noteOff(note);
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_setSynthParams(
        JNIEnv*, jobject, jint wave, jfloat cut, jfloat att, jfloat rel,
        jint lfoTarget, jfloat lfoRate, jfloat lfoDepth) {
    if (engine) {
        engine->waveform.store(wave);
        engine->cutoff.store(cut);
        engine->attack.store(att);
        engine->release.store(rel);
        engine->lfoTarget.store(lfoTarget);
        engine->lfoRate.store(lfoRate);
        engine->lfoDepth.store(lfoDepth);
    }
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_setSequencerState(
        JNIEnv*, jobject, jboolean playing, jfloat bpm) {
    if (engine) {
        engine->seqPlaying.store(playing);
        engine->seqBpm.store(bpm);
    }
}
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_setSequencerStep(
        JNIEnv*, jobject, jint stepIndex, jint midiNote) {
    if (engine && stepIndex >= 0 && stepIndex < 8) {
        engine->seqNotes[stepIndex].store(midiNote);
    }
}
JNIEXPORT jint JNICALL Java_com_example_mysynth_MainActivity_getCurrentStep(JNIEnv*, jobject) {
    return engine ? engine->currentStep.load() : 0;
}
}
