#include <jni.h>
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <cmath>
#include <algorithm>

enum class Waveform { SINE = 0, SAW = 1, SQUARE = 2, TRIANGLE = 3 };
enum class EnvStage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE };

struct Voice {
    bool active = false;
    int note = -1;
    double phase = 0.0;
    double phaseInc = 0.0;
    EnvStage stage = EnvStage::IDLE;
    float env = 0.0f;
};

class SynthEngine : public oboe::AudioStreamDataCallback {
public:
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
        double freq = 440.0 * std::pow(2.0, (note - 69) / 12.0);
        Voice* target = nullptr;
        for (auto& v : mVoices) {
            if (v.note == note || !v.active) { target = &v; break; }
        }
        if (!target) target = &mVoices[0];
        target->note = note;
        target->phaseInc = freq / mSampleRate;
        target->active = true;
        target->stage = EnvStage::ATTACK;
    }

    void noteOff(int note) {
        for (auto& v : mVoices) {
            if (v.active && v.note == note && v.stage != EnvStage::RELEASE)
                v.stage = EnvStage::RELEASE;
        }
    }

    std::atomic<int> waveform{1};      // Default: SAW
    std::atomic<float> cutoff{2500.0f}; // Filtro LowPass Hz
    std::atomic<float> attack{0.02f};
    std::atomic<float> release{0.3f};

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        int wave = waveform.load();
        float cut = cutoff.load();
        float att = std::max(0.005f, attack.load());
        float rel = std::max(0.005f, release.load());

        // Coefficiente filtro Low-Pass 1-polo
        float rc = 1.0f / (2.0f * 3.14159265f * cut);
        float dt = 1.0f / mSampleRate;
        float alpha = dt / (rc + dt);

        for (int i = 0; i < numFrames; ++i) {
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
                if (wave == 0) osc = std::sin(v.phase * 2.0 * M_PI);
                else if (wave == 1) osc = 2.0 * v.phase - 1.0;
                else if (wave == 2) osc = (v.phase < 0.5) ? 1.0f : -1.0f;
                else osc = 4.0 * std::abs(v.phase - 0.5) - 1.0;

                mix += osc * v.env;
                v.phase += v.phaseInc;
                if (v.phase >= 1.0) v.phase -= 1.0;
            }
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
JNIEXPORT void JNICALL Java_com_example_mysynth_MainActivity_setParams(JNIEnv*, jobject, jint wave, jfloat cut, jfloat att, jfloat rel) {
    if (engine) {
        engine->waveform.store(wave);
        engine->cutoff.store(cut);
        engine->attack.store(att);
        engine->release.store(rel);
    }
}
}
