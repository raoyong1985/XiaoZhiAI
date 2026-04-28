#include <jni.h>
#include <opus/opus.h>
#include <android/log.h>
#include <stdint.h>
#include <sys/time.h>

#define LOG_TAG "NativeOpus"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xiaozhi_aiservice_data_audio_NativeOpusDecoder_nativeCreateDecoder(
    JNIEnv *env, jobject thiz, jint sampleRate, jint channels) {
    
    int error;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    
    if (error != OPUS_OK) {
        LOGE("opus_decoder_create failed: %d", error);
        return 0;
    }
    
    LOGI("Opus decoder created: %dHz, %dch, error=%d", sampleRate, channels, error);
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_aiservice_data_audio_NativeOpusDecoder_nativeDecode(
    JNIEnv *env, jobject thiz, jlong decoderPtr, jbyteArray input, jshortArray output, jint maxFrameSize) {
    
    if (decoderPtr == 0) {
        LOGE("decoderPtr is 0");
        return 0;
    }
    
    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(decoderPtr);
    
    // Get input bytes
    jbyte *inputBytes = env->GetByteArrayElements(input, nullptr);
    jsize inputLen = env->GetArrayLength(input);
    
    // Get output shorts
    jshort *outputShorts = env->GetShortArrayElements(output, nullptr);
    
    if (inputBytes == nullptr || outputShorts == nullptr) {
        if (inputBytes) env->ReleaseByteArrayElements(input, inputBytes, 0);
        if (outputShorts) env->ReleaseShortArrayElements(output, outputShorts, 0);
        LOGE("Failed to get array elements");
        return 0;
    }
    
    // Decode Opus packet
    // frame_size is the maximum number of samples per channel
    struct timeval t0, t1;
    gettimeofday(&t0, NULL);
    int samples = opus_decode(
        decoder,
        reinterpret_cast<unsigned char *>(inputBytes),
        inputLen,
        outputShorts,
        maxFrameSize,
        0  // no FEC
    );
    gettimeofday(&t1, NULL);
    long decodeUs = (t1.tv_sec - t0.tv_sec) * 1000000L + (t1.tv_usec - t0.tv_usec);

    env->ReleaseByteArrayElements(input, inputBytes, 0);
    env->ReleaseShortArrayElements(output, outputShorts, 0);

    if (samples < 0) {
        LOGE("opus_decode failed: %d (inputLen=%d, decodeUs=%lld, firstBytes=%02X %02X %02X)",
             samples, inputLen, (long long)decodeUs,
             inputLen > 0 ? (unsigned char)inputBytes[0] : 0,
             inputLen > 1 ? (unsigned char)inputBytes[1] : 0,
             inputLen > 2 ? (unsigned char)inputBytes[2] : 0);
        return 0;
    }

    if (samples == 0) {
        LOGW("opus_decode 返回0 (静音帧? inputLen=%d, firstBytes=%02X %02X %02X)",
             inputLen,
             inputLen > 0 ? (unsigned char)inputBytes[0] : 0,
             inputLen > 1 ? (unsigned char)inputBytes[1] : 0,
             inputLen > 2 ? (unsigned char)inputBytes[2] : 0);
    }

    if (decodeUs > 10000) {
        LOGW("opus_decode 慢: %lldus, samples=%d, inputLen=%d", (long long)decodeUs, samples, inputLen);
    }

    return samples;
}

JNIEXPORT void JNICALL
Java_com_xiaozhi_aiservice_data_audio_NativeOpusDecoder_nativeDestroyDecoder(
    JNIEnv *env, jobject thiz, jlong decoderPtr) {
    
    if (decoderPtr != 0) {
        OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(decoderPtr);
        opus_decoder_destroy(decoder);
        LOGI("Opus decoder destroyed");
    }
}

} // extern "C"
