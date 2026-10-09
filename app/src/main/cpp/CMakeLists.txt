# Minimal JNI bridge: PTY allocation via posix_openpt/forkpty and process exec.
cmake_minimum_required(VERSION 3.22.1)
project(emxnative)

add_library(emxnative SHARED
        ../c/emxpty.c)

find_library(log-lib log)
target_link_libraries(emxnative ${log-lib})
