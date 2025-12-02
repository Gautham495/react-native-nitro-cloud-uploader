#include <jni.h>
#include "nitroclouduploaderOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::nitroclouduploader::initialize(vm);
}
