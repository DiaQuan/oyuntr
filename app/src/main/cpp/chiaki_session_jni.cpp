#include <jni.h>

#include <chiaki/controller.h>
#include <chiaki/log.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/regist.h>
#include <chiaki/session.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>

namespace {

constexpr jint EVENT_CONNECTED = 1;
constexpr jint EVENT_PIN_REQUESTED = 2;
constexpr jint EVENT_SESSION_ENDED = 3;
constexpr jint EVENT_SESSION_FAILED = 4;

JavaVM *java_vm = nullptr;

struct NativeSession {
    ChiakiSession session{};
    ChiakiOpusDecoder audio_decoder{};
    ChiakiLog log{};
    jobject callback = nullptr;
    jmethodID callback_method = nullptr;
    jmethodID video_sample_method = nullptr;
    jmethodID audio_format_method = nullptr;
    jmethodID audio_pcm_method = nullptr;
    uint32_t audio_channels = 0;
    bool audio_decoder_initialized = false;
    bool initialized = false;
    bool started = false;
};

struct NativeRegistration {
    ChiakiRegist registration{};
    ChiakiLog log{};
    jobject callback = nullptr;
    jmethodID result_method = nullptr;
};

void discard_log(ChiakiLogLevel, const char *, void *) {}

JNIEnv *current_env(bool *attached) {
    *attached = false;
    JNIEnv *env = nullptr;
    if (java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return env;
}

void notify_java(NativeSession *native, jint event, const char *message) {
    bool attached = false;
    JNIEnv *env = current_env(&attached);
    if (!env) return;
    jstring text = message ? env->NewStringUTF(message) : nullptr;
    env->CallVoidMethod(native->callback, native->callback_method, event, text);
    if (text) env->DeleteLocalRef(text);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) java_vm->DetachCurrentThread();
}

void session_event(ChiakiEvent *event, void *user) {
    auto *native = static_cast<NativeSession *>(user);
    switch (event->type) {
        case CHIAKI_EVENT_CONNECTED:
            notify_java(native, EVENT_CONNECTED, nullptr);
            break;
        case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
            notify_java(native, EVENT_PIN_REQUESTED, nullptr);
            break;
        case CHIAKI_EVENT_QUIT: {
            const auto reason = event->quit.reason;
            const bool failed = chiaki_quit_reason_is_error(reason);
            const char *detail = event->quit.reason_str;
            notify_java(native, failed ? EVENT_SESSION_FAILED : EVENT_SESSION_ENDED,
                        detail ? detail : chiaki_quit_reason_string(reason));
            break;
        }
        default:
            break;
    }
}

void registration_event(ChiakiRegistEvent *event, void *user) {
    auto *native = static_cast<NativeRegistration *>(user);
    if (!native || !event) return;
    bool success = event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS && event->registered_host;
    bool attached = false;
    JNIEnv *env = current_env(&attached);
    if (!env) return;
    jbyteArray registration_key = nullptr;
    jbyteArray morning = nullptr;
    if (success) {
        registration_key = env->NewByteArray(CHIAKI_SESSION_AUTH_SIZE);
        morning = env->NewByteArray(CHIAKI_SESSION_AUTH_SIZE);
        if (registration_key && morning) {
            env->SetByteArrayRegion(registration_key, 0, CHIAKI_SESSION_AUTH_SIZE,
                    reinterpret_cast<const jbyte *>(event->registered_host->rp_regist_key));
            env->SetByteArrayRegion(morning, 0, CHIAKI_SESSION_AUTH_SIZE,
                    reinterpret_cast<const jbyte *>(event->registered_host->rp_key));
        } else {
            success = false;
        }
    }
    if (!env->ExceptionCheck()) {
        env->CallVoidMethod(native->callback, native->result_method,
                success ? JNI_TRUE : JNI_FALSE, registration_key, morning);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (registration_key) env->DeleteLocalRef(registration_key);
    if (morning) env->DeleteLocalRef(morning);
    if (attached) java_vm->DetachCurrentThread();
}

bool session_video_sample(uint8_t *buffer, size_t size, int32_t frames_lost,
                          bool frame_recovered, void *user) {
    auto *native = static_cast<NativeSession *>(user);
    if (!native || !native->callback || size > static_cast<size_t>(std::numeric_limits<jsize>::max())) return false;
    bool attached = false;
    JNIEnv *env = current_env(&attached);
    if (!env) return false;
    jbyteArray sample = env->NewByteArray(static_cast<jsize>(size));
    if (!sample) {
        if (attached) java_vm->DetachCurrentThread();
        return false;
    }
    env->SetByteArrayRegion(sample, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte *>(buffer));
    jboolean accepted = JNI_FALSE;
    if (!env->ExceptionCheck()) {
        accepted = env->CallBooleanMethod(native->callback, native->video_sample_method,
                                          sample, static_cast<jint>(frames_lost),
                                          frame_recovered ? JNI_TRUE : JNI_FALSE);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        accepted = JNI_FALSE;
    }
    env->DeleteLocalRef(sample);
    if (attached) java_vm->DetachCurrentThread();
    return accepted == JNI_TRUE;
}

void session_audio_format(uint32_t channels, uint32_t sample_rate, void *user) {
    auto *native = static_cast<NativeSession *>(user);
    if (!native || !native->callback) return;
    native->audio_channels = channels;
    bool attached = false;
    JNIEnv *env = current_env(&attached);
    if (!env) return;
    env->CallVoidMethod(native->callback, native->audio_format_method,
                        static_cast<jint>(channels), static_cast<jint>(sample_rate));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) java_vm->DetachCurrentThread();
}

void session_audio_pcm(int16_t *samples, size_t samples_per_channel, void *user) {
    auto *native = static_cast<NativeSession *>(user);
    if (!native || !native->callback || !samples || native->audio_channels == 0) return;
    const size_t sample_count = samples_per_channel * native->audio_channels;
    if (sample_count > static_cast<size_t>(std::numeric_limits<jsize>::max()) / sizeof(int16_t)) return;
    const jsize byte_count = static_cast<jsize>(sample_count * sizeof(int16_t));
    bool attached = false;
    JNIEnv *env = current_env(&attached);
    if (!env) return;
    jbyteArray pcm = env->NewByteArray(byte_count);
    if (pcm) {
        env->SetByteArrayRegion(pcm, 0, byte_count, reinterpret_cast<const jbyte *>(samples));
        if (!env->ExceptionCheck()) env->CallVoidMethod(native->callback, native->audio_pcm_method, pcm);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(pcm);
    }
    if (attached) java_vm->DetachCurrentThread();
}

void throw_java(JNIEnv *env, const char *class_name, const char *message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class) env->ThrowNew(exception_class, message);
}

NativeSession *from_handle(jlong handle) {
    return reinterpret_cast<NativeSession *>(static_cast<intptr_t>(handle));
}

void stop_and_join(NativeSession *native) {
    if (!native || !native->initialized || !native->started) return;
    chiaki_session_stop(&native->session);
    chiaki_session_join(&native->session);
    native->started = false;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    java_vm = vm;
    const ChiakiErrorCode result = chiaki_lib_init();
    return result == CHIAKI_ERR_SUCCESS ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeCreateSession(
        JNIEnv *env, jobject, jstring host_string, jbyteArray registration_key,
        jbyteArray morning, jobject callback) {
    if (!host_string || !registration_key || !morning || !callback ||
        env->GetArrayLength(registration_key) != CHIAKI_SESSION_AUTH_SIZE ||
        env->GetArrayLength(morning) != CHIAKI_SESSION_AUTH_SIZE) {
        throw_java(env, "java/lang/IllegalArgumentException", "PS4 registration fields are invalid");
        return 0;
    }

    const char *host = env->GetStringUTFChars(host_string, nullptr);
    if (!host || !*host) {
        if (host) env->ReleaseStringUTFChars(host_string, host);
        throw_java(env, "java/lang/IllegalArgumentException", "A PS4 host address is required");
        return 0;
    }

    auto *native = new (std::nothrow) NativeSession();
    if (!native) {
        env->ReleaseStringUTFChars(host_string, host);
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate a PS4 session");
        return 0;
    }

    ChiakiConnectInfo info{};
    info.ps5 = false;
    info.host = host;
    info.video_profile_auto_downgrade = true;
    info.enable_idr_on_fec_failure = true;
    info.packet_loss_max = 0.05;
    chiaki_connect_video_profile_preset(
            &info.video_profile, CHIAKI_VIDEO_RESOLUTION_PRESET_720p, CHIAKI_VIDEO_FPS_PRESET_30);
    env->GetByteArrayRegion(registration_key, 0, CHIAKI_SESSION_AUTH_SIZE,
                            reinterpret_cast<jbyte *>(info.regist_key));
    env->GetByteArrayRegion(morning, 0, CHIAKI_SESSION_AUTH_SIZE,
                            reinterpret_cast<jbyte *>(info.morning));
    env->ReleaseStringUTFChars(host_string, host);

    chiaki_log_init(&native->log, CHIAKI_LOG_ERROR, discard_log, nullptr);
    native->callback = env->NewGlobalRef(callback);
    jclass callback_class = env->GetObjectClass(callback);
    native->callback_method = env->GetMethodID(callback_class, "onNativeEvent", "(ILjava/lang/String;)V");
    native->video_sample_method = env->GetMethodID(callback_class, "onNativeVideoSample", "([BIZ)Z");
    native->audio_format_method = env->GetMethodID(callback_class, "onNativeAudioFormat", "(II)V");
    native->audio_pcm_method = env->GetMethodID(callback_class, "onNativeAudioPcm", "([B)V");
    env->DeleteLocalRef(callback_class);
    if (!native->callback || !native->callback_method || !native->video_sample_method ||
        !native->audio_format_method || !native->audio_pcm_method || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (native->callback) env->DeleteGlobalRef(native->callback);
        delete native;
        throw_java(env, "java/lang/IllegalStateException", "OyunTR native callback could not be initialized");
        return 0;
    }

    const ChiakiErrorCode result = chiaki_session_init(&native->session, &info, &native->log);
    if (result != CHIAKI_ERR_SUCCESS) {
        env->DeleteGlobalRef(native->callback);
        delete native;
        throw_java(env, "java/lang/IllegalStateException", chiaki_error_string(result));
        return 0;
    }
    native->initialized = true;
    chiaki_session_set_event_cb(&native->session, session_event, native);
    chiaki_session_set_video_sample_cb(&native->session, session_video_sample, native);
    chiaki_opus_decoder_init(&native->audio_decoder, &native->log);
    native->audio_decoder_initialized = true;
    chiaki_opus_decoder_set_cb(&native->audio_decoder, session_audio_format, session_audio_pcm, native);
    ChiakiAudioSink audio_sink{};
    chiaki_opus_decoder_get_sink(&native->audio_decoder, &audio_sink);
    chiaki_session_set_audio_sink(&native->session, &audio_sink);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(native));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeStartSession(JNIEnv *, jobject, jlong handle) {
    auto *native = from_handle(handle);
    if (!native || !native->initialized || native->started) return CHIAKI_ERR_UNINITIALIZED;
    const ChiakiErrorCode result = chiaki_session_start(&native->session);
    native->started = result == CHIAKI_ERR_SUCCESS;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeStopSession(JNIEnv *, jobject, jlong handle) {
    stop_and_join(from_handle(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeDestroySession(JNIEnv *env, jobject, jlong handle) {
    auto *native = from_handle(handle);
    if (!native) return;
    stop_and_join(native);
    if (native->audio_decoder_initialized) chiaki_opus_decoder_fini(&native->audio_decoder);
    if (native->initialized) chiaki_session_fini(&native->session);
    if (native->callback) env->DeleteGlobalRef(native->callback);
    delete native;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeSetControllerState(
        JNIEnv *, jobject, jlong handle, jint buttons, jint left_x, jint left_y,
        jint right_x, jint right_y, jint left_trigger, jint right_trigger) {
    auto *native = from_handle(handle);
    if (!native || !native->started) return;
    // OyunTR buttons are a domain bitset. Map each position to chiaki-ng's named PS button.
    static constexpr std::array<uint32_t, 16> button_map = {
        CHIAKI_CONTROLLER_BUTTON_CROSS, CHIAKI_CONTROLLER_BUTTON_MOON,
        CHIAKI_CONTROLLER_BUTTON_BOX, CHIAKI_CONTROLLER_BUTTON_PYRAMID,
        CHIAKI_CONTROLLER_BUTTON_DPAD_LEFT, CHIAKI_CONTROLLER_BUTTON_DPAD_RIGHT,
        CHIAKI_CONTROLLER_BUTTON_DPAD_UP, CHIAKI_CONTROLLER_BUTTON_DPAD_DOWN,
        CHIAKI_CONTROLLER_BUTTON_L1, CHIAKI_CONTROLLER_BUTTON_R1,
        CHIAKI_CONTROLLER_BUTTON_L3, CHIAKI_CONTROLLER_BUTTON_R3,
        CHIAKI_CONTROLLER_BUTTON_OPTIONS, CHIAKI_CONTROLLER_BUTTON_SHARE,
        CHIAKI_CONTROLLER_BUTTON_TOUCHPAD, CHIAKI_CONTROLLER_BUTTON_PS,
    };
    ChiakiControllerState state{};
    chiaki_controller_state_set_idle(&state);
    for (size_t bit = 0; bit < button_map.size(); ++bit) {
        if ((static_cast<uint32_t>(buttons) & (1u << bit)) != 0) state.buttons |= button_map[bit];
    }
    state.left_x = static_cast<int16_t>(left_x);
    state.left_y = static_cast<int16_t>(left_y);
    state.right_x = static_cast<int16_t>(right_x);
    state.right_y = static_cast<int16_t>(right_y);
    state.l2_state = static_cast<uint8_t>(left_trigger);
    state.r2_state = static_cast<uint8_t>(right_trigger);
    chiaki_session_set_controller_state(&native->session, &state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeSubmitConsolePin(
        JNIEnv *env, jobject, jlong handle, jstring pin_string) {
    auto *native = from_handle(handle);
    if (!native || !native->started || !pin_string) return;
    const char *pin = env->GetStringUTFChars(pin_string, nullptr);
    if (!pin) return;
    chiaki_session_set_login_pin(&native->session,
                                 reinterpret_cast<const uint8_t *>(pin), std::strlen(pin));
    env->ReleaseStringUTFChars(pin_string, pin);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeStartRegistration(
        JNIEnv *env, jobject, jstring host_string, jint pin, jint console_pin,
        jbyteArray psn_account_id, jobject callback) {
    if (!host_string || !callback || pin < 0 || console_pin < 0 ||
        !psn_account_id || env->GetArrayLength(psn_account_id) != CHIAKI_PSN_ACCOUNT_ID_SIZE) {
        throw_java(env, "java/lang/IllegalArgumentException", "PS4 registration details are invalid");
        return 0;
    }
    const char *host = env->GetStringUTFChars(host_string, nullptr);
    if (!host || !*host) {
        if (host) env->ReleaseStringUTFChars(host_string, host);
        throw_java(env, "java/lang/IllegalArgumentException", "A PS4 host address is required");
        return 0;
    }
    auto *native = new (std::nothrow) NativeRegistration();
    if (!native) {
        env->ReleaseStringUTFChars(host_string, host);
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate PS4 registration");
        return 0;
    }
    ChiakiRegistInfo info{};
    info.target = CHIAKI_TARGET_PS4_10;
    info.host = host;
    info.pin = static_cast<uint32_t>(pin);
    info.console_pin = static_cast<uint32_t>(console_pin);
    info.broadcast = false;
    info.psn_online_id = nullptr;
    info.holepunch_info = nullptr;
    info.rudp = nullptr;
    env->GetByteArrayRegion(psn_account_id, 0, CHIAKI_PSN_ACCOUNT_ID_SIZE,
            reinterpret_cast<jbyte *>(info.psn_account_id));
    env->ReleaseStringUTFChars(host_string, host);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        delete native;
        throw_java(env, "java/lang/IllegalArgumentException", "PSN account ID could not be read");
        return 0;
    }
    chiaki_log_init(&native->log, CHIAKI_LOG_ERROR, discard_log, nullptr);
    native->callback = env->NewGlobalRef(callback);
    jclass callback_class = env->GetObjectClass(callback);
    native->result_method = env->GetMethodID(callback_class, "onNativeRegistrationResult", "(Z[B[B)V");
    env->DeleteLocalRef(callback_class);
    if (!native->callback || !native->result_method || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (native->callback) env->DeleteGlobalRef(native->callback);
        delete native;
        throw_java(env, "java/lang/IllegalStateException", "Registration callback could not be initialized");
        return 0;
    }
    const ChiakiErrorCode result = chiaki_regist_start(
            &native->registration, &native->log, &info, registration_event, native);
    if (result != CHIAKI_ERR_SUCCESS) {
        env->DeleteGlobalRef(native->callback);
        delete native;
        throw_java(env, "java/lang/IllegalStateException", chiaki_error_string(result));
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(native));
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeStopRegistration(JNIEnv *, jobject, jlong handle) {
    auto *native = reinterpret_cast<NativeRegistration *>(static_cast<intptr_t>(handle));
    if (native) chiaki_regist_stop(&native->registration);
}

extern "C" JNIEXPORT void JNICALL
Java_com_oyuntr_remoteplay_ChiakiNativeBridge_nativeDestroyRegistration(JNIEnv *env, jobject, jlong handle) {
    auto *native = reinterpret_cast<NativeRegistration *>(static_cast<intptr_t>(handle));
    if (!native) return;
    chiaki_regist_fini(&native->registration);
    if (native->callback) env->DeleteGlobalRef(native->callback);
    delete native;
}
