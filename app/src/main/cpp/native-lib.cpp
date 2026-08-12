/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <jni.h>
#include <string>
#include <vector>
#include "node.h"

extern "C" {

/**
 * Starts the Node.js engine with the given arguments.
 * Runs node::Start() which is the entry point from nodejs-mobile.
 *
 * @param env JNI environment
 * @param thiz Java object
 * @param jArgs Java String[] of arguments (e.g., ["node", "-e", "code"])
 * @return exit code from Node.js
 */
JNIEXPORT jint JNICALL
Java_com_example_aicode_nodejs_NodeJsEngine_startNodeWithArguments(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray jArgs) {

    jsize argc = env->GetArrayLength(jArgs);
    if (argc == 0) return -1;

    // Convert Java String[] to C char*[]
    std::vector<std::string> args_str(argc);
    std::vector<char*> argv(argc);

    for (jsize i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(jArgs, i);
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        args_str[i] = std::string(cstr);
        argv[i] = const_cast<char*>(args_str[i].c_str());
        env->ReleaseStringUTFChars(jstr, cstr);
    }

    // Start Node.js engine (this call blocks until Node.js exits)
    int result = node::Start(argc, argv.data());
    return (jint)result;
}

/**
 * Returns the Node.js version string.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aicode_nodejs_NodeJsEngine_getNodeVersion(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF(NODE_VERSION);
}

} // extern "C"
