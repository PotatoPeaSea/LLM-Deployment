// JNI bridge onto QAIRT's Genie C API.
//
// The Python chatbot (scripts/chatbot/llama.py) shells out to genie-t2t-run once
// per turn, which reloads the 1.3GB context binary every time. Holding a
// GenieDialog open instead is the whole reason this app exists: the model is
// loaded once and, just as importantly, the KV cache SURVIVES between queries,
// so each turn only has to prefill the new text rather than the entire
// transcript. See ChatEngine.kt for what that means for the prompt format.
//
// Threading contract: query() is called from a background thread and blocks
// until generation finishes. The Genie response callback fires on that same
// thread, so the JNIEnv* captured in QueryCtx is valid inside it -- no
// AttachCurrentThread needed. abort() is the one call made from another thread
// (the UI), and GenieDialog_signal is documented as safe for that.

#include <jni.h>

#include <android/log.h>
#include <cstdarg>
#include <cstdlib>
#include <string>

#include <Genie/GenieCommon.h>
#include <Genie/GenieDialog.h>
#include <Genie/GenieLog.h>

#define LOG_TAG "GenieBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
  GenieDialogConfig_Handle_t config = nullptr;
  GenieDialog_Handle_t dialog = nullptr;
  GenieLog_Handle_t log = nullptr;
};

// Genie writes its diagnostics to stdout by default, which on Android goes
// nowhere. Everything useful about a failed model load -- a missing context
// binary, an HTP allocation that didn't fit -- is in there, so route it to
// logcat under the "Genie" tag.
void genieLogCallback(const GenieLog_Handle_t,
                      const char* fmt,
                      GenieLog_Level_t level,
                      uint64_t,
                      va_list args) {
  int priority;
  switch (level) {
    case GENIE_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
    case GENIE_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
    case GENIE_LOG_LEVEL_VERBOSE: priority = ANDROID_LOG_VERBOSE; break;
    default: priority = ANDROID_LOG_INFO; break;
  }
  __android_log_vprint(priority, "Genie", fmt, args);
}

// Per-query state handed to the Genie response callback as userData.
struct QueryCtx {
  JNIEnv* env = nullptr;
  jobject sink = nullptr;      // com.qcs.geniechat.TokenSink
  jmethodID onToken = nullptr;
  std::string response;
  bool aborted = false;
};

const char* statusName(Genie_Status_t status) {
  switch (status) {
    case GENIE_STATUS_SUCCESS: return "SUCCESS";
    case GENIE_STATUS_WARNING_ABORTED: return "WARNING_ABORTED";
    case GENIE_STATUS_WARNING_BOUND_HANDLE: return "WARNING_BOUND_HANDLE";
    case GENIE_STATUS_WARNING_PAUSED: return "WARNING_PAUSED";
    case GENIE_STATUS_WARNING_CONTEXT_EXCEEDED: return "WARNING_CONTEXT_EXCEEDED";
    case GENIE_STATUS_ERROR_GENERAL: return "ERROR_GENERAL";
    case GENIE_STATUS_ERROR_INVALID_ARGUMENT: return "ERROR_INVALID_ARGUMENT";
    case GENIE_STATUS_ERROR_MEM_ALLOC: return "ERROR_MEM_ALLOC";
    case GENIE_STATUS_ERROR_INVALID_CONFIG: return "ERROR_INVALID_CONFIG";
    case GENIE_STATUS_ERROR_INVALID_HANDLE: return "ERROR_INVALID_HANDLE";
    case GENIE_STATUS_ERROR_QUERY_FAILED: return "ERROR_QUERY_FAILED";
    case GENIE_STATUS_ERROR_JSON_FORMAT: return "ERROR_JSON_FORMAT";
    case GENIE_STATUS_ERROR_JSON_SCHEMA: return "ERROR_JSON_SCHEMA";
    case GENIE_STATUS_ERROR_JSON_VALUE: return "ERROR_JSON_VALUE";
    case GENIE_STATUS_ERROR_GENERATE_FAILED: return "ERROR_GENERATE_FAILED";
    case GENIE_STATUS_ERROR_GET_HANDLE_FAILED: return "ERROR_GET_HANDLE_FAILED";
    case GENIE_STATUS_ERROR_APPLY_CONFIG_FAILED: return "ERROR_APPLY_CONFIG_FAILED";
    case GENIE_STATUS_ERROR_SET_PARAMS_FAILED: return "ERROR_SET_PARAMS_FAILED";
    case GENIE_STATUS_ERROR_BOUND_HANDLE: return "ERROR_BOUND_HANDLE";
    default: return "UNKNOWN";
  }
}

void throwGenieError(JNIEnv* env, const char* what, Genie_Status_t status) {
  std::string msg = std::string(what) + " failed: " + statusName(status) + " (" +
                    std::to_string(static_cast<int>(status)) + ")";
  LOGE("%s", msg.c_str());
  jclass cls = env->FindClass("com/qcs/geniechat/GenieException");
  if (cls != nullptr) {
    env->ThrowNew(cls, msg.c_str());
  } else {
    env->ExceptionClear();
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), msg.c_str());
  }
}

std::string toStdString(JNIEnv* env, jstring value) {
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars != nullptr ? chars : "");
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return out;
}

void queryCallback(const char* response,
                   const GenieDialog_SentenceCode_t sentenceCode,
                   const void* userData) {
  auto* ctx = const_cast<QueryCtx*>(static_cast<const QueryCtx*>(userData));
  if (ctx == nullptr) return;

  if (sentenceCode == GENIE_DIALOG_SENTENCE_ABORT) {
    ctx->aborted = true;
    return;
  }
  if (response == nullptr || *response == '\0') return;

  ctx->response.append(response);

  // Stream the fragment up to Kotlin. If the JVM is already unwinding an
  // exception, stop calling in -- but let Genie finish its own loop cleanly
  // rather than longjmp'ing out of vendor code.
  if (ctx->env->ExceptionCheck()) return;
  jstring chunk = ctx->env->NewStringUTF(response);
  if (chunk == nullptr) return;
  ctx->env->CallVoidMethod(ctx->sink, ctx->onToken, chunk);
  ctx->env->DeleteLocalRef(chunk);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeCreate(JNIEnv* env,
                                                jclass,
                                                jstring configJson,
                                                jstring nativeLibDir) {
  // The HTP backend hands the skel path to the FastRPC driver, which loads it
  // onto the DSP. Inside an app the only directory we are allowed to exec/load
  // vendor code from is nativeLibraryDir, where the APK's .so files are
  // extracted -- that is where 09_stage_qairt_for_app.sh puts libQnnHtpV73Skel.so.
  // Must be set before the backend library initialises, i.e. before create().
  const std::string libDir = toStdString(env, nativeLibDir);
  const std::string adspPath =
      libDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1);
  setenv("LD_LIBRARY_PATH", libDir.c_str(), 1);
  LOGI("ADSP_LIBRARY_PATH=%s", adspPath.c_str());

  const std::string json = toStdString(env, configJson);

  auto* session = new Session();
  Genie_Status_t status = GenieDialogConfig_createFromJson(json.c_str(), &session->config);
  if (status != GENIE_STATUS_SUCCESS) {
    delete session;
    throwGenieError(env, "GenieDialogConfig_createFromJson", status);
    return 0;
  }

  if (GenieLog_create(nullptr, genieLogCallback, GENIE_LOG_LEVEL_INFO, &session->log) ==
      GENIE_STATUS_SUCCESS) {
    GenieDialogConfig_bindLogger(session->config, session->log);
  }

  // This is the expensive call: it maps the context binaries and allocates the
  // KV cache on the DSP (~170MB at ctx4096). Tens of seconds on first run.
  status = GenieDialog_create(session->config, &session->dialog);
  if (status != GENIE_STATUS_SUCCESS) {
    GenieDialogConfig_free(session->config);
    if (session->log != nullptr) GenieLog_free(session->log);
    delete session;
    throwGenieError(env, "GenieDialog_create", status);
    return 0;
  }

  LOGI("dialog created");
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeQuery(JNIEnv* env,
                                               jclass,
                                               jlong handle,
                                               jstring prompt,
                                               jobject sink) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->dialog == nullptr) {
    throwGenieError(env, "query (no dialog)", GENIE_STATUS_ERROR_INVALID_HANDLE);
    return nullptr;
  }

  QueryCtx ctx;
  ctx.env = env;
  ctx.sink = sink;
  jclass sinkClass = env->GetObjectClass(sink);
  ctx.onToken = env->GetMethodID(sinkClass, "onToken", "(Ljava/lang/String;)V");
  if (ctx.onToken == nullptr) return nullptr;  // NoSuchMethodError already pending

  const std::string text = toStdString(env, prompt);
  Genie_Status_t status = GenieDialog_query(session->dialog,
                                            text.c_str(),
                                            GENIE_DIALOG_SENTENCE_COMPLETE,
                                            queryCallback,
                                            &ctx);

  if (env->ExceptionCheck()) return nullptr;  // a TokenSink callback threw

  // A context overflow still produced usable text; the caller decides whether
  // to reset. Same for a user abort -- keep the partial answer.
  if (status != GENIE_STATUS_SUCCESS && status != GENIE_STATUS_WARNING_CONTEXT_EXCEEDED &&
      status != GENIE_STATUS_WARNING_ABORTED) {
    throwGenieError(env, "GenieDialog_query", status);
    return nullptr;
  }
  if (status != GENIE_STATUS_SUCCESS) LOGI("query returned %s", statusName(status));

  return env->NewStringUTF(ctx.response.c_str());
}

JNIEXPORT void JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeAbort(JNIEnv*, jclass, jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session != nullptr && session->dialog != nullptr) {
    GenieDialog_signal(session->dialog, GENIE_DIALOG_ACTION_ABORT);
  }
}

JNIEXPORT void JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeReset(JNIEnv* env, jclass, jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->dialog == nullptr) return;
  Genie_Status_t status = GenieDialog_reset(session->dialog);
  if (status != GENIE_STATUS_SUCCESS) throwGenieError(env, "GenieDialog_reset", status);
}

// Tokens of context the dialog has consumed. This is Genie's own count, so it
// beats the char/3.2 estimate the Python version has to use -- but it is only
// available AFTER a query, so ChatEngine still needs an estimate to decide
// whether the NEXT turn fits.
JNIEXPORT jint JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeContextOccupancy(JNIEnv*, jclass, jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->dialog == nullptr) return -1;
  Genie_DataType_t dataType;
  Genie_Value_t value;
  Genie_Status_t status = GenieDialog_getValue(
      session->dialog, GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY, nullptr, &dataType, &value);
  if (status != GENIE_STATUS_SUCCESS) return -1;
  return static_cast<jint>(value.uint32Value);
}

JNIEXPORT void JNICALL
Java_com_qcs_geniechat_GenieBridge_nativeFree(JNIEnv*, jclass, jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return;
  if (session->dialog != nullptr) GenieDialog_free(session->dialog);
  if (session->config != nullptr) GenieDialogConfig_free(session->config);
  if (session->log != nullptr) GenieLog_free(session->log);
  delete session;
  LOGI("dialog freed");
}

}  // extern "C"
